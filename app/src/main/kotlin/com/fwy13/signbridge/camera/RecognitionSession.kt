package com.fwy13.signbridge.camera

import android.content.Context
import android.os.SystemClock
import com.fwy13.signbridge.models.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

data class CaptureState(
    val hands: List<HandDetection> = emptyList(),
    val width: Int = 1,
    val height: Int = 1,
    val countdown: Int = 0,
    val recording: Boolean = false,
    val predicting: Boolean = false,
    val count: Int = 0,
    val total: Int = 60,
    val prediction: Prediction? = null,
    val error: String = "",
    val snapshot: JSONObject? = null,
    val modelId: String = "",
    val modelName: String = "",
    val labels: List<String> = emptyList()
)

private data class InferenceOutcome(
    val epoch: Long,
    val prediction: Prediction? = null,
    val snapshot: JSONObject? = null,
    val error: String = ""
)

/** Detection and temporal state stay on the camera executor; inference has its own serial executor. */
class RecognitionSession(context: Context, val mode: String, private val result: (String) -> Unit) :
    Closeable {
    private val storage = ModelStorage(context)
    private var alphabet: AlphabetEngine? = null
    private var word: WordEngine? = null
    private val inference = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)
    private val predicting = AtomicBoolean(false)

    @Volatile
    private var pending: InferenceOutcome? = null
    private var epoch = 0L
    private val tracker = HandTracker()
    private var sampler = FrameSampler()
    private var wordIntervalMs = 50L
    private var activeLabels = emptySet<String>()
    private val wordFrames = mutableListOf<FloatArray>()
    private val events = ArrayDeque<JSONObject>()
    private var lastPredictionTime = 0L
    private var lastWordFrame = 0L
    private var lastHandSeen = 0L
    private var candidate = ""
    private var stableCount = 0
    private var committed = ""
    private var hadHand = false
    private var current = CaptureState()

    @Volatile
    private var recordRequested = false
    private var recordAt = 0L

    init {
        try {
            val (id, config) = storage.configuration(mode)
            android.util.Log.d(
                "AlphabetConfig",
                "model=$id, " +
                        "normalization=${config.optString("normalizationVersion")}, " +
                        "swapHandedness=${config.optBoolean("swapHandedness")}, " +
                        "mirrorInput=${config.optBoolean("mirrorInput")}"
            )
            require(config.getString("task") == mode) { "Model task does not match capture mode" }
            if (mode == "alphabet") {
                alphabet = AlphabetEngine(context, id)

                // Temporary diagnostic only.
                debugAlphabetFixture(context)
            } else {
                word = WordEngine(storage, id, config)
            }


            val loadedLabels = if (mode == "alphabet") requireNotNull(alphabet).labels else requireNotNull(word).labels
            activeLabels = loadedLabels.toSet()
            sampler = FrameSampler(config.getInt("sequenceLength"), config.getDouble("sampleFps"))
            wordIntervalMs = (1000.0 / config.getDouble("sampleFps")).toLong().coerceAtLeast(1L)
            current = current.copy(
                labels = loadedLabels,
                total = config.getInt("sequenceLength"),
                modelId = id,
                modelName = config.optString("displayName")
                    .ifBlank { id }
            )
        } catch (failure: Throwable) {
            alphabet?.close(); word?.close(); inference.shutdown(); throw failure
        }
    }


    private fun debugAlphabetFixture(context: Context) {
        val engine = alphabet ?: return

        try {
            // Fixture is already normalized.
            val fixtureFrames = AlphabetFixture.load(context)

            // Run inference directly, without preprocessing.
            val logits = engine.predictRaw(fixtureFrames)

            require(logits.size == 30) {
                "Expected 30 logits, got ${logits.size}"
            }

            val predictedIndex = logits.indices.maxByOrNull {
                logits[it]
            } ?: error("Empty model output")

            val top3 = logits.indices
                .sortedByDescending { logits[it] }
                .take(3)
                .joinToString(" | ") { index ->
                    "${engine.labels[index]}: ${logits[index]}"
                }

            android.util.Log.d(
                "FixtureDebug",
                """
            Model: ${engine.id}
            Input: [1, 60, 126]
            Predicted index: $predictedIndex
            Predicted label: ${engine.labels[predictedIndex]}
            Logit19: ${logits[19]}
            Top 3: $top3
            """.trimIndent()
            )

        } catch (error: Exception) {
            android.util.Log.e(
                "FixtureDebug",
                "Fixture inference failed",
                error
            )
        }
    }

    fun requestRecord() {
        if (!predicting.get()) recordRequested = true
    }

    private fun consumeOutcome() {
        val outcome = pending ?: return
        pending = null; predicting.set(false); lastPredictionTime = SystemClock.elapsedRealtime()
        current = current.copy(predicting = false)
        if (outcome.epoch != epoch) return
        if (outcome.error.isNotBlank()) {
            current = current.copy(error = outcome.error); return
        }
        val prediction = outcome.prediction ?: return
        current = current.copy(prediction = prediction, snapshot = outcome.snapshot, error = "")
        if (mode == "word") {
            result(prediction.label); return
        }
        if (prediction.label in activeLabels && prediction.confidence >= .65) {
            if (candidate == prediction.label) stableCount++ else {
                candidate = prediction.label; stableCount = 1
            }
            if (stableCount >= 2 && committed != prediction.label) {
                committed = prediction.label; result(prediction.label)
            }
        } else {
            candidate = ""; stableCount = 0
        }
    }

    private fun submit(frames: Array<FloatArray>) {
        if (closed.get() || !predicting.compareAndSet(false, true)) return
        val generation = epoch
        val history = events.toList()
        current = current.copy(predicting = true, error = "")
        inference.execute {
            val outcome = try {
                if (mode == "word") InferenceOutcome(
                    generation,
                    requireNotNull(word).predict(frames)
                ) else {
                    val engine = requireNotNull(alphabet)
                    val logits = engine.predictRaw(frames)
                    val top3 = logits.indices
                        .sortedByDescending { logits[it] }
                        .take(3)
                        .joinToString(" | ") { index ->
                            "${engine.labels[index]}: ${"%.3f".format(logits[index])}"
                        }

                    android.util.Log.d("AlphabetDebug", "Top 3 logits: $top3")
                    val index = engine.classIndex(logits)
                    val prediction = Prediction(
                        engine.id,
                        index,
                        engine.labels[index],
                        engine.confidence(logits),
                        logits
                    )
                    val snapshot = JSONObject().put("schemaVersion", 1)
                        .put("pipelineVersion", "alphabet-camera-v2")
                        .put("capturedAt", Instant.now().toString())
                        .put("frames", JSONArray(frames.map { JSONArray(it.toList()) }))
                        .put("events", JSONArray(history))
                        .put(
                            "prediction",
                            JSONObject().put("modelId", prediction.modelId)
                                .put("modelSha256", engine.modelSha256).put("classIndex", index)
                                .put("label", prediction.label)
                                .put("confidence", prediction.confidence)
                                .put("logits", JSONArray(logits.toList()))
                        )
                    InferenceOutcome(generation, prediction, snapshot)
                }
            } catch (failure: Exception) {
                InferenceOutcome(generation, error = failure.message ?: "Inference failed")
            }
            if (!closed.get()) pending = outcome
        }
    }

    fun process(hands: List<HandDetection>, time: Long, width: Int, height: Int): CaptureState {
        if (closed.get()) return current
        val now = SystemClock.elapsedRealtime()
        if (hands.isNotEmpty()) lastHandSeen = now
        val visibleHands =
            if (mode == "word" && hands.isEmpty() && now - lastHandSeen <= 150) current.hands else hands
        current = current.copy(hands = visibleHands, width = width, height = height)
        if (mode == "word") {
            consumeOutcome()
            if (recordRequested && !predicting.get()) {
                recordRequested = false; recordAt = now + 3000; wordFrames.clear(); current =
                    current.copy(prediction = null, count = 0, recording = false, error = "")
            }
            if (recordAt == 0L) return current
            if (now < recordAt) return current.copy(countdown = ceil((recordAt - now) / 1000.0).toInt())
            current = current.copy(countdown = 0, recording = true)
            // Use the configured word sampling interval; empty detections do not advance capture.
            if (hands.isEmpty() || now - lastWordFrame < wordIntervalMs) return current
            lastWordFrame = now
            val raw = FloatArray(126)
            for (hand in hands) {
                val offset = when (hand.label) {
                    "Left" -> 0; "Right" -> 63; else -> continue
                }
                hand.points.forEachIndexed { i, p ->
                    raw[offset + i * 3] = p.x; raw[offset + i * 3 + 1] =
                    p.y; raw[offset + i * 3 + 2] = p.z
                }
            }
            wordFrames.add(raw); current = current.copy(count = wordFrames.size)
            if (wordFrames.size >= current.total) {
                recordAt = 0; current = current.copy(recording = false)
                submit(wordFrames.take(current.total).map { it.copyOf() }
                    .toTypedArray()); wordFrames.clear()
            }
            return current
        }
        val frame = tracker.encode(hands, time)
        events.addLast(
            JSONObject().put("timestampMs", time).put("handCount", hands.size)
                .put("imageWidth", width).put("imageHeight", height)
                .put("frame", JSONArray(frame.toList()))
                .put("detections", JSONArray(hands.map { h ->
                    JSONObject().put("label", h.label).put("score", h.score)
                        .put(
                            "landmarks",
                            JSONArray(h.points.map { p ->
                                JSONObject().put("x", p.x).put("y", p.y).put("z", p.z)
                            })
                        )
                }))
        )
        while (events.size > maxOf(150, current.total)) events.removeFirst()
        val (accepted, restarted) = sampler.append(frame, time)
        current = current.copy(count = sampler.count)
        if (restarted) {
            epoch++; candidate = ""; stableCount = 0; current =
                current.copy(prediction = null, snapshot = null)
        }
        if (hands.isEmpty()) {
            if (hadHand) epoch++
            hadHand = false; candidate = ""; stableCount = 0; committed = ""; sampler.reset()
            current = current.copy(count = 0)
            consumeOutcome()
            current = current.copy(prediction = null, snapshot = null); return current
        }
        hadHand = true
        consumeOutcome()
        if (accepted && !predicting.get() && now - lastPredictionTime >= 250) sampler.snapshot()
            ?.let(::submit)
        return current
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        inference.execute { alphabet?.close(); word?.close() }
        inference.shutdown()
    }
}


