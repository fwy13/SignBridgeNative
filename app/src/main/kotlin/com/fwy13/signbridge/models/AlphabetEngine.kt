package com.fwy13.signbridge.models

import android.content.Context
import java.io.Closeable

/** Alphabet adapter with dimensions and class labels supplied by the selected model. */
class AlphabetEngine(context: Context, requestedModelId: String? = null) : Closeable {
    private val storage = ModelStorage(context)
    private val selected = if (requestedModelId == null) {
        storage.configuration("alphabet")
    } else {
        val registry = storage.registry()
        val resolvedId = registry.getJSONObject("modes").optString(requestedModelId, requestedModelId)
        resolvedId to registry.getJSONObject("models").getJSONObject(resolvedId)
    }
    val id = selected.first
    val config = selected.second
    private val delegate: WordEngine
    init {
        require(config.getString("task") == "alphabet") { "Selected model is not an alphabet model" }
        delegate = WordEngine(storage, id, config)
    }
    val labels: List<String> get() = delegate.labels
    val modelSha256: String get() = delegate.modelSha256
    fun prepare(raw: FloatArray): FloatArray = delegate.prepare(raw)
    fun predictRaw(frames: Array<FloatArray>): FloatArray = delegate.predict(frames).logits
    fun predictNormalized(frames: Array<FloatArray>): FloatArray = delegate.predictNormalized(frames)
    fun classIndex(output: FloatArray): Int = delegate.classIndex(output)
    fun confidence(output: FloatArray): Double = delegate.confidence(output)
    override fun close() { delegate.close() }
}
