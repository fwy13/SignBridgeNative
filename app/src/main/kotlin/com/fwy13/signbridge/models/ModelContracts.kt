package com.fwy13.signbridge.models

import org.json.JSONObject

/** Model task, tensor contract and ordered labels are independent of preprocessing. */
object ModelContracts {
    private val legacyWordIds = setOf(
        "tcn_20260624_232642", "tcn_20260721_115258", "tcn_20260814_104243",
        "tcn_dialect-hoa-de_20260721_160609"
    )
    private val legacyAlphabetIds = setOf(
        "tcn_alphabet_20260912_121104", "bigru_attention_alphabet_20260818_114440",
        "bigru_attention_alphabet_20260818_114440_fp32"
    )

    fun normalize(id: String, original: JSONObject, modes: JSONObject? = null): JSONObject {
        val config = JSONObject(original.toString())
        val sourceId = config.optString("sourceModelId").ifBlank { id.substringBefore('@') }
        val preprocessing = config.getString("normalizationVersion")
        val task = config.optString("task").ifBlank {
            when {
                modes?.optString("word") == id -> "word"
                modes?.optString("alphabet") == id ||
                    modes?.optString("alphabetHandgcn") == id ||
                    modes?.optString("alphabetX86Fallback") == id -> "alphabet"
                sourceId in legacyWordIds -> "word"
                sourceId in legacyAlphabetIds -> "alphabet"
                preprocessing in setOf(HandPreprocessor.VERSION, HandPreprocessor.HANDGCN_VERSION) -> "alphabet"
                else -> error("$id: declare task as word or alphabet")
            }
        }
        require(task in setOf("word", "alphabet")) { "$id: invalid task" }
        require(preprocessing in setOf("hands126_v1", HandPreprocessor.VERSION, HandPreprocessor.HANDGCN_VERSION)) {
            "$id: unsupported preprocessing"
        }
        fun default(key: String, value: Any) { if (!config.has(key)) config.put(key, value) }
        config.put("task", task)
        default("runtime", if (config.getString("modelFile").endsWith(".pte")) "executorch" else "tflite")
        default("displayName", sourceId)
        default("sampleFps", if (task == "word") 20 else 30)
        default("numThreads", 4)
        default("mirrorInput", false)
        default("swapHandedness", false)
        default("applySoftmax", true)
        default("outputType", if (config.getBoolean("applySoftmax")) "logits" else "probabilities")
        default("signatureKey", "")
        default("frameInputName", "inputs")
        default("lengthInputName", "")
        default("outputName", "output_0")
        default("methodName", "forward")
        default("lengthDtype", "int32")
        val runtime = config.getString("runtime")
        require(runtime in setOf("tflite", "executorch"))
        require(config.getString("modelFile").endsWith(if (runtime == "executorch") ".pte" else ".tflite"))
        require(config.getInt("featureDimension") == 126) { "Only 126-feature landmark models are supported" }
        require(config.getInt("sequenceLength") in 1..4096)
        require(config.getInt("classCount") in 1..10000)
        require(config.getDouble("sampleFps") in 1.0..120.0)
        require(config.getInt("numThreads") in 1..32)
        require(config.getString("lengthDtype") in setOf("int32", "int64"))
        val outputType = config.getString("outputType")
        require((outputType == "logits" && config.getBoolean("applySoftmax")) ||
            (outputType == "probabilities" && !config.getBoolean("applySoftmax"))) { "Invalid output configuration" }
        require(runtime != "executorch" || config.getString("methodName") == "forward") {
            "The ExecuTorch adapter supports forward only"
        }
        if (preprocessing == HandPreprocessor.HANDGCN_VERSION) {
            require(!config.getBoolean("mirrorInput")) { "HandGCN requires visual-only mirroring" }
        }
        return config
    }

    fun labels(document: JSONObject, classCount: Int): List<String> {
        val mapping = document.optJSONObject("idx_to_label") ?: document
        require(mapping.length() == classCount) { "Label count does not match classCount" }
        return (0 until classCount).map { index ->
            val label = when (val value = mapping.get(index.toString())) {
                is String -> value
                is JSONObject -> value.getString("label_original")
                else -> error("Invalid label at index $index")
            }
            require(label.isNotBlank()) { "Empty label at index $index" }
            label // Never sort, trim or uppercase the trained class mapping.
        }
    }
}
