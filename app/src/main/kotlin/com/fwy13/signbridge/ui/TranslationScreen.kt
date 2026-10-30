package com.fwy13.signbridge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fwy13.signbridge.*
import com.fwy13.signbridge.camera.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

@Composable
fun TranslationScreen(vm: BridgeViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ElevatedCard(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val video = vm.translationVideo
            if (video != null) Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(
                        onClick = {
                            vm.translationVideo = null; vm.textInput = ""
                        }) { Icon(Icons.Default.Close, tr("conversation.close")) }
                }
                VideoPlayer(
                    video, vm.videos, Modifier
                        .fillMaxWidth()
                        .weight(1f), loop = false
                )
            } else Column(
                Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.SignLanguage,
                    null,
                    Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(20.dp)); Text(
                tr("translation.greeting"),
                style = MaterialTheme.typography.headlineMedium
            )
                Text(tr("translation.greetingSubtitle"), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openCapture() }) {
                Icon(
                    Icons.Default.CameraAlt,
                    bilingual("Mở camera", "Open camera")
                )
            }
            OutlinedTextField(
                vm.textInput,
                { vm.textInput = it },
                modifier = Modifier.weight(1f),
                label = { Text(tr("translation.enterContent")) },
                singleLine = true
            )
            IconButton(onClick = vm::translateText, enabled = vm.textInput.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    tr("translation.typeHint")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(vm: BridgeViewModel) {
    var state by remember(vm.captureMode) { mutableStateOf(CaptureState()) }
    var recordSignal by remember { mutableIntStateOf(0) }
    var cameraAttempt by remember { mutableIntStateOf(0) }
    var pronouns by remember { mutableStateOf(false) }
    val english = vm.texts.language == "en"

    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) {
            Log.w("SignBridgeCapture", state.error)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = vm::closeCapture) {
                Icon(
                    Icons.Default.Close,
                    tr("conversation.close")
                )
            }
            Text(
                if (vm.captureForRoom) tr("tabs.chat") else tr("translation.screenSubtitle"),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = vm::resetCapture) { Text(tr("translation.clear")) }
        }
        if (!vm.captureForRoom) Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = vm.captureMode == "word",
                onClick = { vm.mode("word") },
                label = { Text(tr("translation.wordMode")) })
            FilterChip(
                selected = vm.captureMode == "alphabet",
                onClick = { vm.mode("alphabet") },
                label = { Text(tr("translation.alphabetMode")) })
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            key(vm.captureMode, cameraAttempt) {
                NativeCamera(
                    mode = vm.captureMode,
                    recordSignal = recordSignal,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state = it },
                    onResult = vm::result
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = 14.dp,
                        vertical = 8.dp
                    ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (state.modelId.isNotBlank()) {
                            bilingual("Model đang dùng", "Current model")
                        } else {
                            bilingual("Model chưa sẵn sàng", "Model not ready")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.modelId.isNotBlank()) {
                        Text(
                            text = state.modelName.ifBlank { state.modelId },
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (state.error.isBlank()) {
                CenteredRecognitionResult(
                    state = state,
                    alphabet = vm.captureMode == "alphabet",
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (state.error.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = conciseError(
                                raw = state.error,
                                english = english,
                                fallbackVi = "Không thể nhận diện. Hãy thử lại.",
                                fallbackEn = "Recognition failed. Please try again."
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    vm.resetCapture()
                                    vm.page = Page.Models
                                }
                            ) {
                                Text(bilingual("Model", "Models"))
                            }

                            TextButton(
                                onClick = {
                                    state = CaptureState()
                                    cameraAttempt++
                                }
                            ) {
                                Text(bilingual("Thử lại", "Retry"))
                            }
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 310.dp)
                .verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.error.isBlank()) {
                Text(
                    text = if (state.hands.isEmpty()) {
                        tr("camera.noHand")
                    } else {
                        bilingual(
                            "Đã phát hiện bàn tay",
                            "Hand detected"
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.recording) Text(
                    tr(
                        "camera.recording",
                        "current" to state.count,
                        "total" to state.total
                    )
                )
                if (vm.captureMode == "word") Button(
                    onClick = { recordSignal++ },
                    enabled = !state.recording && !state.predicting && state.countdown == 0,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(tr("camera.pressToStart")) }
            }
            if (vm.captureMode == "alphabet") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            bilingual("Ký tự đã ghi nhận", "Recognized characters"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Text(
                            vm.recognized.joinToString("").ifBlank { "—" },
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Diagnostics(state.snapshot, vm, state.labels)
            } else if (!vm.captureForRoom) {
                OutlinedCard(onClick = {
                    vm.signsDraft = vm.recognized.joinToString(" · "); vm.editingSigns = true
                }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            tr("translation.recognizedSigns"),
                            style = MaterialTheme.typography.labelLarge
                        ); Text(
                        vm.recognized.joinToString(" · ").ifBlank { "—" }); Text(
                        tr("translation.editRecognizedSignsHelper"),
                        style = MaterialTheme.typography.bodySmall
                    )
                    }
                }
                if (BuildConfig.SENTENCE_BUILDING_AVAILABLE) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("translation.autoBuildSentence"),
                            Modifier.weight(1f)
                        ); Switch(vm.autoSentence, vm::toggleSentence)
                    }
                    if (vm.autoSentence) {
                        TextButton(onClick = {
                            pronouns = true
                        }) {
                            Text(
                                tr("translation.quickPronouns") + ": " + if (vm.pronouns.addSubject) vm.pronouns.label else tr(
                                    "translation.noAutomaticSubjectShort"
                                )
                            )
                        }
                        OutlinedTextField(
                            vm.completedSentence,
                            vm::manualSentence,
                            label = { Text(tr("translation.completedSentence")) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (vm.building) {
                            LinearProgressIndicator(Modifier.fillMaxWidth()); Text(tr("translation.buildingSentence"))
                        }
                        if (vm.sentenceError.isNotBlank()) {
                            Text(
                                conciseError(
                                    raw = vm.sentenceError,
                                    english = english,
                                    fallbackVi = "Không thể tạo câu. Hãy thử lại.",
                                    fallbackEn = "Unable to create a sentence. Try again."
                                ),
                                color = MaterialTheme.colorScheme.error
                            );
                            TextButton(onClick = vm::buildSentence) { Text(tr("translation.retrySentence")) }
                        }
                    }
                }
            }
        }
    }
    if (vm.editingSigns) AlertDialog(
        onDismissRequest = { vm.editingSigns = false },
        title = { Text(tr("translation.recognizedSignsEditorTitle")) },
        text = {
            Column {
                Text(tr(if (vm.autoSentence) "translation.recognizedSignsEditorDescription" else "translation.recognizedSignsEditorDescriptionWithoutBuild"))
                OutlinedTextField(
                    vm.signsDraft,
                    { vm.signsDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = { TextButton(onClick = vm::confirmSigns) { Text(tr("translation.confirmRecognizedSigns")) } },
        dismissButton = {
            TextButton(onClick = {
                vm.editingSigns = false
            }) { Text(tr("translation.cancelRecognizedSigns")) }
        })
    if (pronouns) PronounPicker(
        vm.pronouns,
        { vm.pronounOverride = it; pronouns = false },
        { pronouns = false },
        allowDefault = true,
        onDefault = { vm.pronounOverride = null; pronouns = false })
}

@Composable
private fun Diagnostics(snapshot: JSONObject?, vm: BridgeViewModel, labels: List<String>) {
    val context = LocalContext.current
    var open by rememberSaveable { mutableStateOf(false) }
    var expected by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(labels) {
        if (expected !in labels) expected = labels.firstOrNull().orEmpty()
    }
    val clips = remember { mutableStateListOf<JSONObject>() }
    var export by remember { mutableStateOf<String?>(null) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) vm.action {
                val payload = requireNotNull(export)
                withContext(Dispatchers.IO) {
                    requireNotNull(
                        context.contentResolver.openOutputStream(
                            uri
                        )
                    ).bufferedWriter().use { it.write(payload) }
                }
                clips.clear(); export = null
                vm.notice =
                    if (vm.texts.language == "en") "Capture samples saved." else "Đã lưu mẫu nhận diện."
            }
        }
    TextButton(onClick = { open = !open }) {
        Text(
            if (open) bilingual(
                "Đóng ghi mẫu lỗi",
                "Close diagnostics"
            ) else bilingual("Ghi mẫu nhận diện sai", "Capture recognition errors")
        )
    }
    if (open) {
        Text(
            bilingual(
                "Chỉ lưu tọa độ tay, không lưu ảnh/video. Giữ ký hiệu 3 giây rồi ghi mẫu.",
                "Only hand coordinates are saved, not images or video. Hold a sign for 3 seconds before recording."
            ), style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            expected,
            { expected = it.take(128) },
            label = { Text(bilingual("Ký tự đúng", "Expected letter")) },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val label = expected
                when {
                    label !in labels -> vm.error = "Nhập một ký tự trong bảng chữ cái."
                    snapshot == null -> vm.error =
                        "Đưa tay vào khung, giữ ký hiệu 3 giây rồi bấm Ghi mẫu."

                    clips.size >= 30 -> vm.error = "Đã đủ 30 mẫu. Lưu file trước khi ghi tiếp."
                    else -> clips.add(JSONObject(snapshot.toString()).put("expectedLabel", label))
                }
            }, enabled = !vm.busy) { Text(bilingual("Ghi mẫu", "Record") + " (${clips.size}/30)") }
            OutlinedButton(onClick = {
                export =
                    JSONObject().put("schemaVersion", 1).put("clips", JSONArray(clips.toList()))
                        .toString(); launcher.launch("alphabet-errors-${System.currentTimeMillis()}.json")
            }, enabled = clips.isNotEmpty() && !vm.busy) {
                Text(
                    bilingual(
                        "Lưu JSON",
                        "Save JSON"
                    )
                )
            }
        }
    }
}

@Composable
private fun CenteredRecognitionResult(
    state: CaptureState,
    alphabet: Boolean,
    modifier: Modifier = Modifier
) {
    val prediction = state.prediction

    val resultText = when {
        state.countdown > 0 -> state.countdown.toString()
        prediction != null -> prediction.label
        else -> null
    }

    if (resultText == null) return

    val confidence = prediction?.confidence
        ?.takeIf { it.isFinite() }
        ?.coerceIn(0.0, 1.0)

    val lowConfidence =
        alphabet &&
                state.countdown == 0 &&
                confidence != null &&
                confidence < 0.65

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Font follows available camera space instead of a fixed oversized size.
        val availableSize = minOf(maxWidth.value, maxHeight.value)
        val fontSize = if (alphabet || state.countdown > 0) {
            (availableSize * 0.23f).coerceIn(32f, 88f).sp
        } else {
            (availableSize * 0.10f).coerceIn(20f, 36f).sp
        }

        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .widthIn(max = 300.dp),
            shape = RoundedCornerShape(28.dp),
            color = if (lowConfidence) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.94f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            },
            tonalElevation = 6.dp,
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = 28.dp,
                    vertical = 16.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = resultText,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.1f,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = if (alphabet) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.primary
                )

                if (state.countdown == 0 && confidence != null) {
                    Text(
                        text = "${(confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (lowConfidence) {
                        Text(
                            text = bilingual(
                                "Chưa chắc chắn",
                                "Low confidence"
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}