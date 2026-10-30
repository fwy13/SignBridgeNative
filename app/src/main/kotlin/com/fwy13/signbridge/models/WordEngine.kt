package com.fwy13.signbridge.models

import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.DataType
import org.pytorch.executorch.Module
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Tensor
import java.io.Closeable
import kotlin.math.exp

/** Shared FP32 landmark classifier; retained name preserves existing call sites. */
class WordEngine(private val storage: ModelStorage, val id: String, config: JSONObject) : Closeable {
    val config = ModelContracts.normalize(id, config)
    val length = this.config.getInt("sequenceLength")
    private val classes = this.config.getInt("classCount")
    private val key = this.config.getString("signatureKey")
    private val inputName = this.config.getString("frameInputName")
    private val outputName = this.config.getString("outputName")
    private val lengthName = this.config.getString("lengthInputName")
    private val softmax = this.config.getBoolean("applySoftmax")
    private var interpreter: Interpreter? = null
    private var module: Module? = null
    val labels: List<String>
    val modelSha256: String

    init {
        val settings = this.config
        modelSha256 = storage.sha(settings.getString("modelFile"))
        for ((fileKey, hashKey) in listOf(
            "modelFile" to "modelSha256", "labelsFile" to "labelsSha256", "metadataFile" to "metadataSha256"
        )) {
            if (settings.has(fileKey) && settings.has(hashKey)) {
                require(storage.sha(settings.getString(fileKey)).equals(settings.getString(hashKey), ignoreCase = true)) {
                    "$id: $fileKey SHA-256 mismatch"
                }
            }
        }
        val labelDocument = JSONObject(storage.open(settings.getString("labelsFile"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        labels = ModelContracts.labels(labelDocument, classes)
        if (settings.getString("task") == "alphabet") require(labels.distinct().size == classes) {
            "$id: duplicate alphabet labels"
        }
        validateMetadata(labelDocument)
        try {
            if (settings.getString("runtime") == "executorch") {
                module = Module.load(storage.required(settings.getString("modelFile")).absolutePath)
                module!!.loadMethod("forward")
                require(run(Array(length) { FloatArray(126) }).size == classes)
            } else {
                interpreter = Interpreter(storage.map(settings.getString("modelFile")),
                    Interpreter.Options().setNumThreads(settings.getInt("numThreads")))
                validateTflite(requireNotNull(interpreter))
            }
        } catch (failure: Throwable) { close(); throw failure }
    }

    private fun validateMetadata(labelDocument: JSONObject) {
        if (!config.has("metadataFile")) return
        val metadata = JSONObject(storage.open(config.getString("metadataFile"))
            .bufferedReader(Charsets.UTF_8).use { it.readText() })
        val contract = metadata.optJSONObject("training_contract") ?: metadata
        for ((name, expected) in listOf("seq_len" to length, "feature_dim" to 126, "num_classes" to classes)) {
            if (contract.has(name)) require(contract.getInt(name) == expected) { "$id: metadata $name mismatch" }
        }
        fun checkShape(key: String, expected: IntArray) {
            val shape = metadata.optJSONObject(key)?.optJSONArray("shape") ?: return
            require(shape.length() == expected.size && expected.indices.all { shape.getInt(it) == expected[it] }) {
                "$id: metadata $key shape mismatch"
            }
        }
        checkShape("input", intArrayOf(1, length, 126))
        checkShape("output", intArrayOf(1, classes))
        val mapping = contract.optJSONObject("idx_to_label") ?: metadata.optJSONObject("idx_to_label")
        if (mapping != null) {
            require(ModelContracts.labels(mapping, classes) == labels) { "$id: metadata label order mismatch" }
            val actual = labelDocument.optJSONObject("idx_to_label") ?: labelDocument
            repeat(classes) { index ->
                val expectedUid = mapping.optJSONObject(index.toString())?.optString("class_uid").orEmpty()
                if (expectedUid.isNotBlank()) require(
                    actual.optJSONObject(index.toString())?.optString("class_uid") == expectedUid
                ) { "$id: metadata class identity mismatch" }
            }
        }
        if (metadata.has("method_name")) require(metadata.getString("method_name") == config.getString("methodName")) {
            "$id: metadata method mismatch"
        }
        if (config.getString("normalizationVersion") == HandPreprocessor.HANDGCN_VERSION) {
            val prep = contract.optJSONObject("preprocess_contract")
            if (prep != null) {
                require(prep.getString("landmark_order") == "MP_Left(63)+MP_Right(63)")
                require(prep.getString("coordinate_space") == "mediapipe_normalized")
                require(prep.getString("coordinate_order") == "xyz")
                require(prep.getString("frontend_mirroring") == "visual_only")
            }
        }
    }

    private fun validateTflite(runtime: Interpreter) {
        val hasLength = lengthName.isNotBlank()
        val input = if (key.isNotBlank()) {
            require(key in runtime.signatureKeys) { "$id: signature mismatch" }
            val expectedInputs = setOf(inputName) + if (hasLength) setOf(lengthName) else emptySet()
            require(runtime.getSignatureInputs(key).toSet() == expectedInputs)
            require(runtime.getSignatureOutputs(key).toSet() == setOf(outputName))
            runtime.getInputTensorFromSignature(inputName, key)
        } else {
            require(runtime.inputTensorCount == if (hasLength) 2 else 1)
            require(runtime.outputTensorCount == 1)
            runtime.getInputTensor(0)
        }
        val output = if (key.isNotBlank()) runtime.getOutputTensorFromSignature(outputName, key) else runtime.getOutputTensor(0)
        require(input.shape().contentEquals(intArrayOf(1, length, 126))) { "$id: input shape mismatch" }
        require(output.shape().contentEquals(intArrayOf(1, classes))) { "$id: output shape mismatch" }
        for (tensor in listOf(input, output)) {
            require(tensor.dataType() == DataType.FLOAT32) { "$id: expected FP32" }
            require(tensor.quantizationParams().scale == 0f && tensor.quantizationParams().zeroPoint == 0) {
                "$id: unexpected quantization"
            }
        }
        val expectedInput = config.optString("inputTensorName")
        val expectedOutput = config.optString("outputTensorName")
        require(expectedInput.isBlank() || input.name() == expectedInput)
        require(expectedOutput.isBlank() || output.name() == expectedOutput)
        if (hasLength) {
            val tensor = if (key.isNotBlank()) runtime.getInputTensorFromSignature(lengthName, key) else runtime.getInputTensor(1)
            val expectedType = if (config.getString("lengthDtype") == "int64") DataType.INT64 else DataType.INT32
            require(tensor.shape().contentEquals(intArrayOf(1)))
            require(tensor.dataType() == expectedType)
        }
    }

    private fun run(frames: Array<FloatArray>): FloatArray {
        module?.let { runtime ->
            val flattened = FloatArray(length * 126)
            frames.forEachIndexed { i, frame -> frame.copyInto(flattened, i * 126) }
            val inputs = mutableListOf(EValue.from(Tensor.fromBlob(flattened, longArrayOf(1, length.toLong(), 126))))
            if (lengthName.isNotBlank()) inputs += if (config.optString("lengthDtype", "int32") == "int64")
                EValue.from(Tensor.fromBlob(longArrayOf(length.toLong()), longArrayOf(1))) else EValue.from(Tensor.fromBlob(intArrayOf(length), longArrayOf(1)))
            val values = runtime.forward(*inputs.toTypedArray())
            require(values.size == 1 && values[0].isTensor)
            val output = values[0].toTensor()
            require(output.shape().contentEquals(longArrayOf(1, classes.toLong())))
            return output.dataAsFloatArray
        }

        val output = arrayOf(FloatArray(classes))
        val runtime = requireNotNull(interpreter)
        val lengthInput: Any = if (config.getString("lengthDtype") == "int64") longArrayOf(length.toLong()) else intArrayOf(length)
        if (key.isNotBlank()) {
            val inputs = mutableMapOf<String, Any>(inputName to arrayOf(frames))
            if (lengthName.isNotBlank()) inputs[lengthName] = lengthInput
            runtime.runSignature(inputs, mapOf(outputName to output), key)
        } else if (lengthName.isBlank()) {
            runtime.run(arrayOf(frames), output)
        } else {
            runtime.runForMultipleInputsOutputs(arrayOf<Any>(arrayOf(frames), lengthInput), mutableMapOf<Int, Any>(0 to output))
        }
        return output[0]
    }

    @Synchronized
    fun predict(raw: Array<FloatArray>): Prediction {
        require(raw.size == length)
        val output = predictNormalized(Array(length) { prepare(raw[it]) })
        val index = classIndex(output)
        return Prediction(id, index, labels[index], confidence(output), output)
    }

    @Synchronized
    fun predictNormalized(frames: Array<FloatArray>): FloatArray {
        require(frames.size == length && frames.all { it.size == 126 && it.all(Float::isFinite) }) {
            "$id: invalid input sequence"
        }
        val output = run(frames)
        require(output.size == classes && output.all(Float::isFinite))
        if (!softmax) require(output.all { it in 0f..1f } && kotlin.math.abs(output.sum() - 1f) <= 1e-3f) {
            "$id: invalid probability output"
        }
        return output
    }

    fun classIndex(output: FloatArray): Int = requireNotNull(output.indices.maxByOrNull { output[it] })
    fun confidence(output: FloatArray): Double {
        val index = classIndex(output)
        if (!softmax) return output[index].toDouble()
        val maximum = output[index].toDouble()
        return 1.0 / output.sumOf { exp(it.toDouble() - maximum) }
    }

    fun prepare(input: FloatArray): FloatArray {
        require(input.size == 126 && input.all(Float::isFinite))
        return when (config.getString("normalizationVersion")) {
            HandPreprocessor.VERSION -> HandPreprocessor.prepare(input, config.getBoolean("swapHandedness"), config.getBoolean("mirrorInput"))
            HandPreprocessor.HANDGCN_VERSION -> HandPreprocessor.prepareHandGcn(input, config.getBoolean("swapHandedness"))
            "hands126_v1" -> prepareLegacyWord(input)
            else -> error("Unsupported preprocessing")
        }
    }

    private fun prepareLegacyWord(input: FloatArray): FloatArray {
        var raw = input
        if (config.optBoolean("swapHandedness", false)) raw = FloatArray(126) { input[if (it < 63) it + 63 else it - 63] }
        if (config.optBoolean("mirrorInput", false)) {
            val source = raw
            raw = FloatArray(126)
            for (targetHand in 0..1) for (point in 0..20) {
                val from = (1 - targetHand) * 63 + point * 3
                val to = targetHand * 63 + point * 3
                if ((0..2).any { source[from + it] != 0f }) { raw[to] = 1f - source[from]; raw[to+1] = source[from+1]; raw[to+2] = source[from+2] }
            }
        }
        val result = FloatArray(126)
        for (hand in 0..1) {
            val offset = hand * 63
            val present = (0..20).filter { point -> (0..2).any { raw[offset + point * 3 + it] != 0f } }
            val points = present.filter { it > 0 }.map { (raw[offset+it*3] - raw[offset]) to (raw[offset+it*3+1] - raw[offset+1]) }
                .filter { (x,y) -> x*x+y*y > 1e-12f }
            val scale = if (points.isEmpty()) 1f else maxOf(points.maxOf { it.first } - points.minOf { it.first }, points.maxOf { it.second } - points.minOf { it.second }).takeIf { it > 1e-6f } ?: 1f
            for (point in present) { val at=offset+point*3; result[at]=(raw[at]-raw[offset])/scale; result[at+1]=(raw[at+1]-raw[offset+1])/scale; result[at+2]=raw[at+2] }
        }
        return result
    }
    @Synchronized override fun close() { interpreter?.close(); interpreter=null; module?.close(); module=null }
}

data class Prediction(val modelId: String, val index: Int, val label: String, val confidence: Double, val logits: FloatArray)
