package com.fwy13.signbridge.models

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

data class ReleaseModel(
    val id: String,
    val displayName: String,
    val runtime: String,
    val modelFile: String,
    val configurationJson: String,
    val problem: String? = null
)

private data class RequiredArtifact(
    val fileKey: String,
    val name: String,
    val sha256: String?
)

class ReleaseModelInstaller(
    private val storage: ModelStorage
) {
    companion object {
        private const val MANIFEST = "tflite_models.json"
        private const val MAX_MANIFEST_BYTES = 1024L * 1024L
        private val installationMutex = Mutex()

        private val fileNamePattern =
            Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,239}")

        private val sha256Pattern =
            Regex("[a-fA-F0-9]{64}")
    }

    suspend fun readRelease(
        release: GitHubRelease
    ): List<ReleaseModel> = withContext(Dispatchers.IO) {
        val manifests = release.assets.filter {
            it.name == MANIFEST
        }

        require(manifests.size == 1) {
            "Release cần một tệp tflite_models.json."
        }

        val staging = temporaryDirectory("manifest-")

        try {
            GitHubReleaseDownloader.downloadAssets(
                assets = manifests,
                targetDir = staging,
                onProgress = { _, _, _, _ -> }
            )

            currentCoroutineContext().ensureActive()

            val file = File(staging, MANIFEST)

            require(
                file.isFile &&
                        file.length() in 1L..MAX_MANIFEST_BYTES
            ) {
                "Manifest không hợp lệ hoặc quá lớn."
            }

            val manifest = JSONObject(
                file.readText(Charsets.UTF_8)
            )

            require(manifest.optInt("version", -1) == 1) {
                "Phiên bản manifest chưa được hỗ trợ."
            }

            val models = manifest.getJSONObject("models")

            require(models.length() in 1..200) {
                "Danh sách model không hợp lệ."
            }

            models.keys().asSequence().map { id ->
                val raw = models.optJSONObject(id) ?: JSONObject()
                try {
                    require(id.isNotBlank() && id.length <= 200)
                    val config = ModelContracts.normalize(id, raw, manifest.optJSONObject("modes"))
                    validateConfiguration(config)
                    requiredArtifacts(config).forEach { dependency ->
                        require(release.assets.count { it.name == dependency.name } == 1) {
                            "Missing release asset: ${dependency.name}"
                        }
                    }
                    ReleaseModel(id, config.optString("displayName").ifBlank { id },
                        config.getString("runtime"), config.getString("modelFile"), config.toString())
                } catch (failure: Exception) {
                    android.util.Log.w("ReleaseManifest", "Invalid entry: $id", failure)
                    ReleaseModel(id, raw.optString("displayName").ifBlank { id },
                        raw.optString("runtime", if (raw.optString("modelFile").endsWith(".pte")) "executorch" else "tflite"),
                        raw.optString("modelFile"), raw.toString(), "Missing assets or unsupported configuration")
                }
            }.toList()
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Install the complete release and activate exactly the modes declared in its manifest. */
    suspend fun installRelease(
        release: GitHubRelease,
        onProgress: (status: String, fraction: Float) -> Unit
    ) = installationMutex.withLock {
        withContext(Dispatchers.IO) {
            require(release.assets.any { it.name == MANIFEST }) { "Missing $MANIFEST in ${release.tagName}" }
            require(release.assets.map { it.name }.distinct().size == release.assets.size) {
                "Duplicate asset filename in release"
            }
            val staging = temporaryDirectory("release-")
            var destination: File? = null
            var committed = false
            try {
                GitHubReleaseDownloader.downloadAssets(release.assets, staging) { name, index, total, percent ->
                    onProgress("$name · $index/$total · $percent%",
                        ((index - 1 + percent / 100f) / total.coerceAtLeast(1)).coerceIn(0f, 1f))
                }
                val manifestFile = File(staging, MANIFEST)
                require(manifestFile.length() in 1L..MAX_MANIFEST_BYTES) { "Invalid model manifest" }
                val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
                require(manifest.getInt("version") == 1) { "Unsupported manifest version" }
                val declared = manifest.getJSONObject("models")
                val modes = manifest.getJSONObject("modes")
                require(declared.length() in 1..200) { "Invalid model count" }
                val sourceByMode = mapOf(
                    "word" to modes.getString("word"),
                    "alphabet" to modes.getString("alphabet")
                )
                val configurations = linkedMapOf<String, JSONObject>()
                val resolvedIds = mutableMapOf<String, String>()
                // Only the two active configurations need registration. Every release asset is still downloaded.
                sourceByMode.forEach { (mode, sourceId) ->
                    require(sourceId.isNotBlank() && declared.has(sourceId)) { "Missing $mode model in manifest" }
                    val config = ModelContracts.normalize(sourceId, declared.getJSONObject(sourceId), modes)
                    require(config.getString("task") == mode) { "$sourceId is not a $mode model" }
                    validateConfiguration(config)
                    requiredArtifacts(config).forEach { artifact ->
                        val file = File(staging, artifact.name)
                        require(file.isFile && file.length() > 0) { "Missing model asset: ${artifact.name}" }
                        val hash = fileSha256(file)
                        artifact.sha256?.let { require(it == hash) { "SHA-256 mismatch: ${artifact.name}" } }
                        config.put(artifact.fileKey.removeSuffix("File") + "Sha256", hash)
                    }
                    validateLabels(staging, config)
                    config.optString("metadataFile").takeIf { it.isNotBlank() }?.let {
                        JSONObject(File(staging, it).readText(Charsets.UTF_8))
                    }
                    val id = "$sourceId@${digest((release.tagName + config.toString()).toByteArray()).take(16)}"
                    configurations[id] = config
                    resolvedIds[mode] = id
                }
                require(File(staging, "hand_landmarker.task").isFile) {
                    "Missing hand_landmarker.task in release"
                }
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val packages = File(storage.root, "model-packages")
                    check(packages.isDirectory || packages.mkdirs())
                    val directoryName = UUID.randomUUID().toString()
                    destination = File(packages, directoryName)
                    Files.move(staging.toPath(), requireNotNull(destination).toPath(), StandardCopyOption.ATOMIC_MOVE)
                    configurations.forEach { (id, config) ->
                        val sourceId = id.substringBefore('@')
                        for (key in listOf("modelFile", "labelsFile", "metadataFile")) {
                            if (config.has(key)) config.put(key, "model-packages/$directoryName/${config.getString(key)}")
                        }
                        config.put("sourceModelId", sourceId)
                        config.put("releaseTag", release.tagName)
                        config.put("releasePublishedAt", release.publishedAt)
                    }
                    storage.registerRelease(configurations, resolvedIds)
                    committed = true
                }
            } finally {
                staging.deleteRecursively()
                if (!committed) destination?.deleteRecursively()
            }
        }
    }

    /**
     * Returns the installed registry ID.
     *
     * Registration does not activate the model.
     * Runtime validation remains in the existing "Use model" action.
     */
    suspend fun install(
        release: GitHubRelease,
        model: ReleaseModel,
        onProgress: (status: String, fraction: Float) -> Unit
    ): String = installationMutex.withLock {
        withContext(Dispatchers.IO) {
            require(model.problem == null) { "Model is unavailable" }
            val config = ModelContracts.normalize(model.id, JSONObject(model.configurationJson))

            validateConfiguration(config)

            val required = requiredArtifacts(config)

            // Different configurations get separate IDs, even if the publisher
            // reuses the same source model ID.
            val fingerprint = digest(
                model.configurationJson.toByteArray(Charsets.UTF_8)
            )

            var installedId = "${model.id}@$fingerprint"

            val existing = synchronized(ModelStorage.registryLock) {
                storage.registry()
                    .getJSONObject("models")
                    .optJSONObject(installedId)
                    ?.let { JSONObject(it.toString()) }
            }

            // A release is a single distribution unit: download every asset,
            // regardless of whether the selected engine uses TFLite or PTE.
            // The selected model still determines the configuration registered
            // and the engine used when it is activated.
            require(release.assets.isNotEmpty()) { "Release has no downloadable assets" }
            require(release.assets.map { it.name }.distinct().size == release.assets.size) {
                "Duplicate release asset filenames"
            }
            val assets = release.assets
            required.forEach { artifact ->
                require(assets.any { it.name == artifact.name }) {
                    "Release thiếu tệp: ${artifact.name}"
                }
            }

            val staging = temporaryDirectory("model-")
            var movedDirectory: File? = null
            var committed = false

            try {
                GitHubReleaseDownloader.downloadAssets(
                    assets = assets,
                    targetDir = staging,
                    onProgress = { name, current, total, percent ->
                        onProgress(
                            "$name · $current/$total · $percent%",
                            ((current - 1 + percent / 100f) / total.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        )
                    }
                )

                for (artifact in required) {
                    currentCoroutineContext().ensureActive()

                    val file = File(staging, artifact.name)

                    require(file.isFile && file.length() > 0L) {
                        "Tải tệp chưa hoàn tất: ${artifact.name}"
                    }

                    val actualHash = fileSha256(file)
                    artifact.sha256?.let { expectedHash ->
                        require(actualHash == expectedHash) { "SHA-256 mismatch: ${artifact.name}" }
                    }
                    config.put(artifact.fileKey.removeSuffix("File") + "Sha256", actualHash)
                }

                installedId = "${model.id}@${digest(config.toString().toByteArray(Charsets.UTF_8))}"
                validateLabels(staging, config)

                if (config.has("metadataFile")) {
                    // Confirm that metadata is valid JSON.
                    // Engine-specific contract checks happen before activation.
                    JSONObject(
                        File(
                            staging,
                            config.getString("metadataFile")
                        ).readText(Charsets.UTF_8)
                    )
                }

                currentCoroutineContext().ensureActive()

                /*
                 * Finish the short local commit even if the screen closes.
                 * Downloads and hashing above remain cancellable.
                 */
                withContext(NonCancellable) {
                    val packages = File(storage.root, "model-packages")

                    check(packages.isDirectory || packages.mkdirs()) {
                        "Không thể tạo thư mục model."
                    }

                    val directoryName = UUID.randomUUID().toString()
                    val destination = File(packages, directoryName)

                    // Directory moves within the same external-files filesystem.
                    Files.move(
                        staging.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE
                    )

                    movedDirectory = destination

                    val installedConfig = JSONObject(config.toString())
                    installedConfig.put("publisherChecksumsComplete", required.all { it.sha256 != null })

                    required.forEach { artifact ->
                        installedConfig.put(
                            artifact.fileKey,
                            "model-packages/$directoryName/${artifact.name}"
                        )
                    }

                    installedConfig.put("sourceModelId", model.id)
                    installedConfig.put("releaseTag", release.tagName)

                    /*
                     * Registry is written last.
                     * Until this succeeds, the new files are not registered.
                     */
                    storage.registerInstalledModel(
                        installedId,
                        installedConfig
                    )

                    committed = true
                }

                installedId
            } finally {
                staging.deleteRecursively()

                if (!committed) {
                    movedDirectory?.deleteRecursively()
                }
            }
        }
    }

    private fun validateConfiguration(config: JSONObject) {
        ModelContracts.normalize(config.optString("sourceModelId", "release-model"), config)
        requiredArtifacts(config)
    }

    private fun requiredArtifacts(
        config: JSONObject
    ): List<RequiredArtifact> {
        val fields = mutableListOf(
            "modelFile" to "modelSha256",
            "labelsFile" to "labelsSha256"
        )

        if (config.has("metadataFile")) {
            fields += "metadataFile" to "metadataSha256"
        }

        val result = fields.map { (fileKey, hashKey) ->
            val name = config.getString(fileKey)
            val hash = if (config.has(hashKey)) {
                config.getString(hashKey).also {
                    require(sha256Pattern.matches(it)) { "Invalid SHA-256 for $name" }
                }
            } else null

            require(fileNamePattern.matches(name)) {
                "Tên tệp trong manifest không hợp lệ."
            }

            require(name != MANIFEST) {
                "Manifest không thể là tệp phụ thuộc."
            }

            if (fileKey != "modelFile") {
                require(name.endsWith(".json")) {
                    "Nhãn và metadata phải là JSON."
                }
            }

            RequiredArtifact(
                fileKey = fileKey,
                name = name,
                sha256 = hash?.lowercase()
            )
        }

        require(result.map { it.name }.distinct().size == result.size) {
            "Tên các tệp phụ thuộc bị trùng."
        }

        return result
    }

    private fun validateLabels(directory: File, config: JSONObject) {
        val document = JSONObject(File(directory, config.getString("labelsFile")).readText(Charsets.UTF_8))
        val labels = ModelContracts.labels(document, config.getInt("classCount"))
        if (config.getString("task") == "alphabet") require(labels.distinct().size == labels.size) {
            "Duplicate alphabet labels"
        }
    }

    private fun temporaryDirectory(prefix: String): File {
        val root = storage.root

        check(root.isDirectory || root.mkdirs()) {
            "Không thể mở thư mục model."
        }

        return Files.createTempDirectory(
            root.toPath(),
            ".$prefix"
        ).toFile()
    }

    private suspend fun fileSha256(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")

        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)

            while (true) {
                currentCoroutineContext().ensureActive()

                val size = input.read(buffer)
                if (size < 0) break

                hash.update(buffer, 0, size)
            }
        }

        return hash.digest().toHex()
    }

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        }
}