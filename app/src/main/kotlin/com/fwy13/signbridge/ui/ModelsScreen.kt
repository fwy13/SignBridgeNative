package com.fwy13.signbridge.ui

import android.net.Uri
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.fwy13.signbridge.BridgeViewModel
import com.fwy13.signbridge.models.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

private data class AvailableModel(
    val id: String,
    val config: JSONObject,
    val present: Boolean
)

private data class DeviceModelSnapshot(
    val directory: String,
    val modes: JSONObject,
    val models: List<AvailableModel>,
    val unregistered: List<String>
)

@Composable
fun ModelsScreen(vm: BridgeViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val english = bilingual("vi", "en") == "en"

    fun text(vi: String, en: String): String =
        if (english) en else vi

    var activeTab by rememberSaveable { mutableIntStateOf(0) }

    // GitHub catalogue state.
    var releases by remember { mutableStateOf<List<GitHubRelease>>(emptyList()) }
    var hasFetched by remember { mutableStateOf(false) }
    var isFetching by remember { mutableStateOf(false) }
    var fetchError by remember { mutableStateOf("") }


    // Download state is independent of catalogue loading.
    var isDownloading by remember { mutableStateOf(false) }
    var downloadingAsset by remember { mutableStateOf("") }
    var downloadStatus by remember { mutableStateOf("") }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadError by remember { mutableStateOf("") }
    var downloadSuccess by remember { mutableStateOf("") }

    // Invalidates delayed progress callbacks from an older operation.
    var downloadOperation by remember { mutableIntStateOf(0) }

    // Device state.
    var revision by remember { mutableIntStateOf(0) }
    var candidates by remember {
        mutableStateOf<List<AvailableModel>>(emptyList())
    }
    var selectedModes by remember { mutableStateOf(JSONObject()) }
    var directory by remember { mutableStateOf("") }
    var showDirectory by rememberSaveable { mutableStateOf(false) }
    var relativeNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            relativeNow = System.currentTimeMillis()
        }
    }
    var unregistered by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoadingLocal by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf("") }

    var pendingImport by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val operationBusy = vm.busy || isDownloading
    val controlsEnabled = !operationBusy && !isFetching

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            pendingImport = uris
        }
    }

    val releaseInstaller = remember(vm.models) {
        ReleaseModelInstaller(vm.models)
    }

    var releaseModels by remember {
        mutableStateOf<Map<String, List<ReleaseModel>>>(emptyMap())
    }

    var releaseManifestErrors by remember {
        mutableStateOf<Map<String, String>>(emptyMap())
    }


    fun refreshReleases() {
        if (isFetching || isDownloading || vm.busy) return

        activeTab = 0
        isFetching = true
        fetchError = ""
        revision++

        scope.launch {
            try {
                val fetched = withContext(Dispatchers.IO) {
                    GitHubReleaseDownloader.fetchReleases()
                }.distinctBy { it.tagName }

                val parsed = linkedMapOf<String, List<ReleaseModel>>()
                val errors = linkedMapOf<String, String>()

                for (release in fetched) {
                    try {
                        val models = releaseInstaller.readRelease(release)

                        parsed[release.tagName] = models
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        android.util.Log.w(
                            "GitHubModels",
                            "Invalid manifest: ${release.tagName}",
                            failure
                        )

                        errors[release.tagName] = text(
                            "Không thể đọc cấu hình của release.",
                            "Unable to read this release's configuration."
                        )
                    }
                }

                releases = fetched
                releaseModels = parsed
                releaseManifestErrors = errors
                hasFetched = true

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w(
                    "GitHubModels",
                    "Unable to fetch releases",
                    failure
                )

                fetchError = text(
                    "Không thể lấy danh sách model.",
                    "Unable to load the model list."
                )
            } finally {
                isFetching = false
            }
        }
    }

    fun downloadRelease(release: GitHubRelease) {
        if (isDownloading || isFetching || vm.busy) return

        isDownloading = true
        downloadingAsset = release.tagName
        downloadProgress = 0f
        downloadStatus = text("Đang chuẩn bị…", "Preparing…")
        downloadError = ""
        downloadSuccess = ""

        downloadOperation++
        val operation = downloadOperation

        scope.launch {
            try {
                releaseInstaller.installRelease(
                    release = release,
                    onProgress = { status, fraction ->
                        scope.launch(Dispatchers.Main.immediate) {
                            if (
                                isDownloading &&
                                downloadOperation == operation
                            ) {
                                downloadStatus = status
                                downloadProgress = fraction
                            }
                        }
                    }
                )

                downloadSuccess = text(
                    "Đã cài release và tự cấu hình model cho từ và chữ cái.",
                    "Release installed; word and alphabet models configured automatically."
                )

                activeTab = 1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.w(
                    "GitHubModels",
                    "Model installation failed",
                    failure
                )

                downloadError = conciseError(
                    raw = failure.message.orEmpty().ifBlank {
                        "installation_failed"
                    },
                    english = english,
                    fallbackVi = "Không thể cài model. Hãy thử lại.",
                    fallbackEn = "Unable to install this model. Try again."
                )
            } finally {
                if (downloadOperation == operation) {
                    isDownloading = false
                    downloadingAsset = ""
                    downloadStatus = ""
                    revision++
                }
            }
        }
    }

    LaunchedEffect(revision) {
        isLoadingLocal = true
        localError = ""

        try {
            val snapshot = withContext(Dispatchers.IO) {
                val registry = vm.models.registry()
                val configs = registry.getJSONObject("models")

                val models = configs.keys().asSequence().map { id ->
                    val config = configs.getJSONObject(id)

                    AvailableModel(
                        id = id,
                        config = config,
                        present = vm.models.local(
                            config.getString("modelFile")
                        ) != null
                    )
                }.toList()

                val registeredFiles = models.map {
                    it.config.getString("modelFile")
                }.toSet()

                val unknownFiles = vm.models.files()
                    .filter {
                        it.extension.lowercase() in setOf("pte", "tflite") &&
                                it.name !in registeredFiles
                    }
                    .map { it.name }

                DeviceModelSnapshot(
                    directory = vm.models.root.absolutePath,
                    modes = registry.getJSONObject("modes"),
                    models = models,
                    unregistered = unknownFiles
                )
            }

            directory = snapshot.directory
            selectedModes = snapshot.modes
            candidates = snapshot.models
            unregistered = snapshot.unregistered
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            localError = error.message ?: text(
                "Không thể đọc các model trên thiết bị.",
                "Could not read models on this device."
            )
        } finally {
            isLoadingLocal = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text("Quản lý mô hình", "Model manager"),
            style = MaterialTheme.typography.headlineSmall
        )

        Text(
            text(
                "Làm mới để tải các models mới nhất.",
                "Refresh to load the latest models."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                enabled = controlsEnabled,
                onClick = { refreshReleases() }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(text("Làm mới", "Refresh"))
            }
        }

        if (isDownloading) {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text("Đang tải release", "Downloading release"),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        downloadingAsset,
                        style = MaterialTheme.typography.bodySmall
                    )
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        downloadStatus,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (downloadError.isNotBlank()) {
            ModelNotice(downloadError, isError = true)
        }

        if (downloadSuccess.isNotBlank()) {
            ModelNotice(downloadSuccess)
        }

        TabRow(selectedTabIndex = activeTab) {
            Tab(
                selected = activeTab == 0,
                onClick = { activeTab = 0 },
                text = { Text("GitHub Releases") }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { activeTab = 1 },
                text = { Text(text("Trên thiết bị", "On device")) }
            )
        }

        if (activeTab == 0) {
            if (isFetching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    text(
                        "Đang lấy danh sách GitHub Releases…",
                        "Loading GitHub Releases…"
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (fetchError.isNotBlank()) {
                ModelNotice(fetchError, isError = true)
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (releases.isEmpty() && !isFetching) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (hasFetched) {
                                        text(
                                            "Chưa có model trong các release",
                                            "No models found in releases"
                                        )
                                    } else {
                                        text(
                                            "Khám phá model có sẵn",
                                            "Browse available models"
                                        )
                                    },
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text(
                                        "Nhấn Làm mới để lấy danh sách từ GitHub Releases.",
                                        "Tap Refresh to fetch the GitHub Releases catalogue."
                                    )
                                )
                                TextButton(
                                    enabled = controlsEnabled,
                                    onClick = { refreshReleases() }
                                ) {
                                    Text(text("Làm mới danh sách", "Refresh catalogue"))
                                }
                            }
                        }
                    }
                }

                items(
                    items = releases,
                    key = { it.tagName }
                ) { release ->
                    val models = releaseModels[release.tagName].orEmpty()
                    val manifestError = releaseManifestErrors[release.tagName]

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Parser được thêm ở bước trước.
                            ReleaseBodyDetails(
                                body = release.body,
                                fallbackTitle = release.name.ifBlank {
                                    release.tagName
                                }
                            )

                            Text(
                                release.tagName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (release.publishedAt.isNotBlank()) {
                                val relative = remember(release.publishedAt, relativeNow) {
                                    runCatching {
                                        DateUtils.getRelativeTimeSpanString(
                                            Instant.parse(release.publishedAt).toEpochMilli(),
                                            System.currentTimeMillis(),
                                            DateUtils.MINUTE_IN_MILLIS
                                        ).toString()
                                    }.getOrDefault(release.publishedAt)
                                }
                                Text(
                                    text("Phát hành: ", "Published: ") + relative,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            // Releases carry their GitHub author; keep the link limited to GitHub profiles.
                            if (release.uploaderLogin.isNotBlank()) {
                                val profileUrl = "https://github.com/${release.uploaderLogin}"
                                Text(
                                    text = text("Người đăng: @", "Uploaded by @") + release.uploaderLogin,
                                    modifier = Modifier.clickable {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(profileUrl))
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        textDecoration = TextDecoration.Underline
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            if (manifestError != null) {
                                ModelNotice(
                                    message = manifestError,
                                    isError = true
                                )
                            } else if (models.isEmpty()) {
                                Text(
                                    text(
                                        "Release không khai báo model phù hợp.",
                                        "This release declares no matching models."
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            models.forEach { model ->
                                Text("${model.displayName} · ${model.runtime}",
                                    style = MaterialTheme.typography.bodySmall)
                                if (model.problem != null) ModelNotice(model.problem, isError = true)
                            }

                            if (models.isNotEmpty()) {
                                Text(
                                    text(
                                        "Tải toàn bộ tệp và kích hoạt hai model theo modes trong cấu hình.",
                                        "Download all assets and activate both models using the manifest modes."
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Button(
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = models.isNotEmpty() && models.all { it.problem == null } && controlsEnabled,
                                    onClick = { downloadRelease(release) }
                                ) {
                                    Icon(
                                        Icons.Default.CloudDownload,
                                        contentDescription = null
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(text("Tải toàn bộ release", "Download full release"))
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (isLoadingLocal) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    if (directory.isNotBlank()) {
                        TextButton(onClick = { showDirectory = !showDirectory }) {
                            Text(if (showDirectory) text("Ẩn đường dẫn", "Hide file path")
                                else text("Hiện đường dẫn", "Show file path"))
                        }
                        if (showDirectory) {
                            Text(
                                directory,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (localError.isNotBlank()) {
                    item {
                        ModelNotice(localError, isError = true)
                    }
                }

                if (unregistered.isNotEmpty()) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text(
                                        "Model chưa có cấu hình",
                                        "Models without configuration"
                                    ),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(unregistered.joinToString("\n"))
                                Text(
                                    text(
                                        "Cần tflite_models.json khai báo model, nhãn và cách tiền xử lý trước khi kích hoạt.",
                                        "A tflite_models.json describing the model, labels and preprocessing is required before activation."
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                items(
                    candidates.sortedByDescending { it.present },
                    key = { it.id }
                ) { model ->
                    val config = model.config

                    val mode = config.getString("task")
                    val isActive = selectedModes.optString(mode) == model.id

                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                config.optString("displayName", model.id),
                                style = MaterialTheme.typography.titleMedium
                            )
                            var showModelPath by rememberSaveable(model.id) { mutableStateOf(false) }
                            TextButton(onClick = { showModelPath = !showModelPath }) {
                                Text(if (showModelPath) text("Ẩn tên tệp", "Hide file name")
                                    else text("Hiện tên tệp", "Show file name"))
                            }
                            if (showModelPath) {
                                Text(
                                    config.getString("modelFile"),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                "${config.optString("runtime", "tflite")} · " +
                                        "[1, ${config.getInt("sequenceLength")}, " +
                                        "${config.getInt("featureDimension")}] → " +
                                        "[1, ${config.getInt("classCount")}]",
                                style = MaterialTheme.typography.bodySmall
                            )

                            if (config.has("publisherChecksumsComplete") && !config.getBoolean("publisherChecksumsComplete")) {
                                Text(
                                    text("Release chưa cung cấp đầy đủ checksum.", "This release did not provide all checksums."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                when {
                                    !model.present -> text(
                                        "Chưa tải về thiết bị",
                                        "Not downloaded"
                                    )
                                    isActive -> text("Đang hoạt động", "Active")
                                    else -> text("Đã có tệp model", "Model file available")
                                },
                                color = if (isActive && model.present) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )

                        }
                    }
                }
            }
        }
    }

    if (pendingImport.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingImport = emptyList() },
            title = { Text(text("Nhập tệp từ thiết bị", "Import device files")) },
            text = {
                Text(
                    text(
                        "Tệp sẽ được lưu trong thư mục ứng dụng. Tệp trùng tên sẽ được thay thế.",
                        "Files will be saved in the app directory. Existing files with matching names will be replaced."
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = controlsEnabled,
                    onClick = {
                        val files = pendingImport
                        pendingImport = emptyList()

                        vm.action {
                            try {
                                vm.models.importFiles(files)
                                activeTab = 1
                            } finally {
                                revision++
                            }
                        }
                    }
                ) {
                    Text(text("Nhập tệp", "Import"))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = emptyList() }) {
                    Text(text("Hủy", "Cancel"))
                }
            }
        )
    }
}

@Composable
private fun ModelNotice(
    message: String,
    isError: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}