package com.fwy13.signbridge.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwy13.signbridge.*
import com.fwy13.signbridge.camera.NativeCamera
import com.fwy13.signbridge.data.signVideo
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ConversationScreen(vm: BridgeViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    ScreenColumn {
        Text(tr("conversation.subtitle"), style = MaterialTheme.typography.bodyLarge)
        Section(tr("conversation.enterCode")) {
            OutlinedTextField(
                code,
                { code = it },
                label = { Text(tr("conversation.roomCode")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = { vm.join(code) },
                enabled = code.isNotBlank() && !vm.busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(tr("conversation.joinNow")) }
        }
        FilledTonalButton(
            onClick = vm::createRoom,
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.busy
        ) {
            Icon(
                Icons.Default.AddCircle,
                null
            ); Spacer(Modifier.width(10.dp)); Text(tr("conversation.createRoom"))
        }
        OutlinedButton(
            onClick = { vm.page = Page.Qr },
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.busy
        ) {
            Icon(
                Icons.Default.QrCodeScanner,
                null
            ); Spacer(Modifier.width(10.dp)); Text(tr("conversation.scanQR"))
        }
    }
}

@Composable
fun QrScreen(vm: BridgeViewModel) {
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                vm.page = Page.Conversation
            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("conversation.close")) }
            Text(tr("conversation.scanQR"), style = MaterialTheme.typography.titleLarge)
        }
        key(attempt) {
            NativeCamera(
                "qr",
                0,
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                onState = { error = it.error },
                onResult = {},
                onQr = { value -> vm.join(value) })
        }
        if (error.isNotBlank()) Text(
            error,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp)
        )
        TextButton(onClick = { attempt++ }, enabled = !vm.busy) { Text(tr("library.retry")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(vm: BridgeViewModel) {
    val room by vm.rooms.state.collectAsStateWithLifecycle()
    val user by vm.account.backendProfile.collectAsStateWithLifecycle()
    val dictionary by vm.dictionary.data.collectAsStateWithLifecycle()
    var showQr by remember { mutableStateOf(false) }
    var showMembers by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(room.messages.size) {
        if (room.messages.isNotEmpty()) listState.animateScrollToItem(
            room.messages.lastIndex
        )
    }
    val owner = room.ownerId.isNotBlank() && user.optString("id")
        .isNotBlank() && room.ownerId == user.optString("id")
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
            Column {
                Text(room.code)
                TextButton(
                    onClick = { showMembers = true },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        tr(
                            "conversation.memberCount",
                            "count" to room.participants.size
                        ) + " · " + tr("conversation.tapToView"),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        },
            navigationIcon = {
                IconButton(onClick = { vm.leaveRoom() }, enabled = !vm.busy) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        tr("conversation.leaveRoom")
                    )
                }
            },
            actions = {
                IconButton(onClick = { showQr = true }) {
                    Icon(
                        Icons.Default.QrCode,
                        tr("conversation.qrCode")
                    )
                }; IconButton(
                onClick = { vm.leaveRoom() },
                enabled = !vm.busy
            ) { Icon(Icons.Default.Logout, tr("conversation.leaveRoom")) }
            })
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(room.messages) { _, message ->
                val sender = message.optJSONObject("sender")
                val mine = sender?.optString("participant_id") == room.participantId
                val text = message.optString("text")
                val videos = message.optJSONArray("videos")
                val videoUrls =
                    if (videos != null && videos.length() > 0) (0 until videos.length()).mapNotNull {
                        videos.optJSONObject(it)?.optString("mp4_url")?.takeIf(String::isNotBlank)
                    }
                    else listOfNotNull(remember(dictionary, text) {
                        signVideo(
                            vm.dictionary.find(
                                text
                            )
                        )
                    })
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                ) {
                    Card(
                        Modifier.fillMaxWidth(.88f),
                        colors = CardDefaults.cardColors(containerColor = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (!mine) Text(
                                sender?.optString("display_name").orEmpty(),
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(text)
                            videoUrls.forEach { url ->
                                key(url) {
                                    VideoPlayer(
                                        url,
                                        vm.videos,
                                        Modifier
                                            .fillMaxWidth()
                                            .height(280.dp)
                                    )
                                }
                            }
                            val time = runCatching {
                                Instant.ofEpochSecond(message.optLong("timestamp"))
                                    .atZone(ZoneId.systemDefault())
                                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                            }.getOrDefault("")
                            Text(
                                time,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (room.status != "connected") Text(
            tr("conversation.connection.${room.status}"),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .imePadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.openCapture(true) }) {
                Icon(
                    Icons.Default.CameraAlt,
                    bilingual("Nhận diện ký hiệu", "Recognize sign")
                )
            }
            OutlinedTextField(
                vm.chatInput,
                { vm.chatInput = it },
                label = { Text(tr("conversation.typeMessage")) },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            IconButton(
                onClick = {
                    try {
                        vm.rooms.send(vm.chatInput); vm.chatInput = ""
                    } catch (e: Exception) {
                        vm.error = e.message.orEmpty()
                    }
                },
                enabled = vm.chatInput.isNotBlank() && room.status == "connected"
            ) { Icon(Icons.AutoMirrored.Filled.Send, bilingual("Gửi", "Send")) }
        }
    }
    if (showQr) AlertDialog(
        onDismissRequest = { showQr = false },
        title = { Text(tr("conversation.qrRoomTitle")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                QrImage(room.code); Text(tr("conversation.joinCode") + " " + room.code)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showQr = false
            }) { Text(tr("conversation.close")) }
        })
    if (showMembers) ModalBottomSheet(onDismissRequest = { showMembers = false }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("conversation.membersTitle", "count" to room.participants.size),
                style = MaterialTheme.typography.titleLarge
            )
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                itemsIndexed(room.participants.values.toList()) { _, participant ->
                    val self = participant.optString("participant_id") == room.participantId
                    val isOwner =
                        participant.optString("user_id") == room.ownerId && room.ownerId.isNotBlank()
                    ListItem(
                        headlineContent = {
                            Text(
                                participant.optString("display_name") + if (self) " " + tr(
                                    "conversation.you"
                                ) else ""
                            )
                        },
                        supportingContent = { Text(tr(if (isOwner) "conversation.owner" else "conversation.member")) })
                }
            }
            if (owner) Button(
                onClick = { showMembers = false; vm.leaveRoom(true) },
                enabled = !vm.busy
            ) { Text(tr("conversation.endRoom")) }
        }
    }
}

@Composable
private fun QrImage(code: String) {
    val bitmap by produceState<Bitmap?>(null, code) {
        value = withContext(Dispatchers.Default) {
            val matrix = MultiFormatWriter().encode(code, BarcodeFormat.QR_CODE, 600, 600)
            val pixels =
                IntArray(600 * 600) { index -> if (matrix[index % 600, index / 600]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
            Bitmap.createBitmap(pixels, 600, 600, Bitmap.Config.ARGB_8888)
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), tr("conversation.qrCode"), Modifier.size(240.dp)) }
        ?: CircularProgressIndicator()
}
