package com.fwy13.signbridge.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest




class ModelStorage(private val context: Context) {
    val root: File get() = requireNotNull(context.getExternalFilesDir(null)) { "App-specific external storage is unavailable" }.canonicalFile

    companion object {
        internal val registryLock = Any()
    }

    internal fun registerInstalledModel(
        id: String,
        configuration: JSONObject
    ) {
        synchronized(registryLock) {
            val registryFile = File(root, "tflite_models.json")

            val custom = if (registryFile.isFile) {
                JSONObject(registryFile.readText(Charsets.UTF_8))
            } else {
                JSONObject().put("version", 1)
            }

            require(custom.optInt("version", 1) == 1) {
                "Unsupported registry version"
            }

            val models = if (custom.has("models")) {
                custom.getJSONObject("models")
            } else {
                JSONObject().also { custom.put("models", it) }
            }

            models.put(
                id,
                JSONObject(configuration.toString())
            )

            // Existing modes, defaultModel and other models remain unchanged.
            writeAtomic(
                registryFile,
                custom.toString(2).toByteArray(Charsets.UTF_8)
            )
        }
    }


    /** Register a release as one atomic configuration update, including its mode mapping. */
    internal fun registerRelease(configurations: Map<String, JSONObject>, modeIds: Map<String, String>) {
        synchronized(registryLock) {
            val registryFile = File(root, "tflite_models.json")
            val registry = if (registryFile.isFile) JSONObject(registryFile.readText(Charsets.UTF_8))
                else JSONObject().put("version", 1)
            require(registry.optInt("version", 1) == 1)
            val models = registry.optJSONObject("models") ?: JSONObject().also { registry.put("models", it) }
            val modes = registry.optJSONObject("modes") ?: JSONObject().also { registry.put("modes", it) }
            configurations.forEach { (id, config) -> models.put(id, JSONObject(config.toString())) }
            modeIds.forEach { (mode, id) ->
                require(configurations[id]?.optString("task") == mode) { "Invalid $mode model mapping" }
                modes.put(mode, id)
            }
            writeAtomic(registryFile, registry.toString(2).toByteArray(Charsets.UTF_8))
        }
    }

    /** The MediaPipe task is a release asset stored beside the installed models. */
    fun requiredHandLandmarker(): File {
        local("hand_landmarker.task")?.let { return it } // manual import compatibility
        val config = runCatching { configuration("alphabet").second }.getOrNull()
            ?: runCatching { configuration("word").second }.getOrNull()
        if (config != null) {
            val packageDirectory = File(required(config.getString("modelFile")).parentFile, "hand_landmarker.task")
            val relative = packageDirectory.relativeTo(root).invariantSeparatorsPath
            local(relative)?.let { return it }
        }
        error("hand_landmarker.task was not found in the installed release. Download the release again.")
    }

    fun local(name: String): File? {
        require(name.isNotBlank() && !File(name).isAbsolute) { "A relative artifact path is required" }
        val base = root
        val file = File(base, name).canonicalFile
        require(file.path.startsWith(base.path + File.separator)) { "Artifact path escapes app storage" }
        return if (file.exists()) {
            require(file.isFile && file.canRead() && file.length() > 0) { "Empty or unreadable artifact: $name" }; file
        } else null
    }
    fun required(name: String) = local(name) ?: error("Model file not found. Download it again.")
    fun open(name: String): InputStream = required(name).inputStream()
    fun map(name: String): MappedByteBuffer {
        val file = required(name)
        return FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()) }
    }
    fun registry(): JSONObject = synchronized(registryLock) {
        val result = JSONObject()
            .put("version", 1)
            .put("models", JSONObject())
            .put("modes", JSONObject())
        val file = local("tflite_models.json") ?: return@synchronized result
        val saved = JSONObject(file.readText(Charsets.UTF_8))
        require(saved.optInt("version", -1) == 1) { "Unsupported registry version" }
        val models = result.getJSONObject("models")
        val modes = result.getJSONObject("modes")
        val savedModels = saved.optJSONObject("models") ?: JSONObject()
        val savedModes = saved.optJSONObject("modes") ?: JSONObject()
        savedModels.keys().forEach { id ->
            val entry = savedModels.getJSONObject(id)
            // Only entries installed from a release are eligible.
            if (entry.optString("releaseTag").isNotBlank() &&
                entry.optString("sourceModelId").isNotBlank()) {
                val config = ModelContracts.normalize(id, entry, savedModes)
                for (key in listOf("modelFile", "labelsFile", "metadataFile")) {
                    if (config.has(key)) local(config.getString(key))
                }
                models.put(id, config)
            }
        }
        for (mode in listOf("word", "alphabet")) {
            val id = savedModes.optString(mode)
            val config = models.optJSONObject(id)
            if (config != null && config.getString("task") == mode) modes.put(mode, id)
        }
        result
    }
    fun configuration(mode: String): Pair<String, JSONObject> {
        require(mode in setOf("word", "alphabet")) { "Unknown recognition mode" }
        val registry = registry()
        val id = registry.getJSONObject("modes").optString(mode)
        val config = registry.getJSONObject("models").optJSONObject(id)
            ?: error("No model configured for $mode. Install a GitHub release containing both modes.")
        return id to config
    }
    fun select(mode: String, id: String) {
        synchronized(registryLock) {
            require(mode in setOf("word", "alphabet"))

            val config = registry()
                .getJSONObject("models")
                .getJSONObject(id)

            require(config.getString("task") == mode) {
                "Model does not support mode $mode"
            }

            required(config.getString("modelFile"))

            val file = File(root, "tflite_models.json")

            val custom = if (file.isFile) {
                JSONObject(file.readText(Charsets.UTF_8))
            } else {
                JSONObject().put("version", 1)
            }

            val modes = custom.optJSONObject("modes")
                ?: JSONObject().also { custom.put("modes", it) }

            modes.put(mode, id)

            if (mode == "alphabet") {
                modes.put("alphabetHandgcn", id)
            }

            writeAtomic(
                file,
                custom.toString(2).toByteArray(Charsets.UTF_8)
            )
        }
    }
    fun files(): List<File> = root.apply { mkdirs() }.listFiles()?.filter { it.isFile && it.extension in setOf("pte", "tflite", "json", "task") }?.sortedBy { it.name }.orEmpty()
    suspend fun importFiles(uris: List<Uri>) = withContext(Dispatchers.IO) {
        root.mkdirs()
        for (uri in uris) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: error("Missing filename")
            require(name == File(name).name && '/' !in name && '\\' !in name && name.substringAfterLast('.') in setOf("pte", "tflite", "json", "task", "png", "jpg", "webp")) { "Unsupported filename: $name" }
            val target = File(root, name).canonicalFile
            require(target.parentFile == root) { "Path escapes app storage" }
            val temporary = File.createTempFile("import-", ".part", root)
            try {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                require(temporary.length() > 0) { "Empty file: $name" }
                if (name.endsWith(".json")) JSONObject(temporary.readText())
                synchronized(registryLock) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally { temporary.delete() }
        }
    }
    private fun writeAtomic(file: File, bytes: ByteArray) {
        root.mkdirs()
        val temporary = File.createTempFile("registry-", ".part", root)
        try { temporary.writeBytes(bytes); Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { temporary.delete() }
    }
    fun sha(name: String): String = open(name).use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(65536)
        while (true) { val count = stream.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
