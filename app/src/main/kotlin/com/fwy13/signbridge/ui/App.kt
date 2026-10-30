package com.fwy13.signbridge.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwy13.signbridge.*
import com.fwy13.signbridge.data.Texts
import androidx.core.net.toUri

val LocalTexts = staticCompositionLocalOf { Texts("vi") }
@Composable
fun tr(key: String, vararg values: Pair<String, Any>) = LocalTexts.current(key, *values)
@Composable
fun bilingual(vi: String, en: String) = if (LocalTexts.current.language == "en") en else vi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignBridgeApp(vm: BridgeViewModel, pendingLink: String?, consumeLink: () -> Unit) {
    val settings by vm.preferences.state.collectAsStateWithLifecycle()
    val signedIn by vm.account.signedIn.collectAsStateWithLifecycle()
    val room by vm.rooms.state.collectAsStateWithLifecycle()
    val colors = if (settings.dark) darkColorScheme(
        primary = Color(0xFF74C6FF),
        secondary = Color(0xFF45C8C2)
    )
    else lightColorScheme(
        primary = Color(0xFF00689E),
        secondary = Color(0xFF006B63),
        surface = Color(0xFFF9F9FC)
    )
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalTexts provides Texts(settings.language)) {
            LaunchedEffect(signedIn, settings.region, pendingLink) {
                if (signedIn && settings.region.isNotBlank() && pendingLink != null) {
                    val uri = pendingLink.toUri()
                    if (uri.scheme in listOf(
                            "signbridge",
                            "voyaapp"
                        ) && uri.pathSegments.contains("room")
                    ) {
                        uri.lastPathSegment?.takeIf { it.matches(Regex("[A-Za-z0-9_-]+")) }
                            ?.let(vm::join)
                    }
                    consumeLink()
                }
            }
            LaunchedEffect(room.ended) {
                if (room.ended) {
                    vm.page = Page.Conversation; vm.notice = vm.texts("conversation.roomEnded")
                }
            }
            val screenState = rememberSaveableStateHolder()
            val tabs = listOf(Page.Translate, Page.Conversation, Page.Library, Page.Settings)
            fun back() {
                when (vm.page) {
                    Page.Models -> vm.page = Page.Settings
                    Page.Capture -> vm.closeCapture()
                    Page.Qr -> vm.page = Page.Conversation
                    Page.Room -> vm.leaveRoom()
                    else -> vm.navigate(Page.Translate)
                }
            }
            BackHandler(enabled = signedIn && (vm.page !in tabs || vm.tab != Page.Translate)) { if (!vm.busy) back() }
            val ready = !vm.initializing && settings.region.isNotBlank() && signedIn
            val fullscreen = vm.page in listOf(Page.Capture, Page.Qr, Page.Room)
            Scaffold(
                topBar = {
                    if (ready && !fullscreen) TopAppBar(
                        title = {
                            Text(
                                when (vm.page) {
                                    Page.Translate -> tr("tabs.translate"); Page.Conversation -> tr(
                                    "tabs.chat"
                                ); Page.Library -> tr(
                                    "tabs.dictionary"
                                )

                                    Page.Models -> bilingual(
                                        "Mô hình trên thiết bị",
                                        "Local models"
                                    ); else -> tr("tabs.settings")
                                }
                            )
                        },
                        navigationIcon = {
                            if (vm.page == Page.Models) IconButton(onClick = { back() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    tr("conversation.close")
                                )
                            }
                        })
                },
                bottomBar = {
                    if (ready && vm.page in tabs) NavigationBar {
                        tabs.forEach { page ->
                            val key = when (page) {
                                Page.Translate -> "tabs.translate"; Page.Conversation -> "tabs.chat"; Page.Library -> "tabs.dictionary"; else -> "tabs.settings"
                            }
                            val icon = when (page) {
                                Page.Translate -> Icons.Default.Translate; Page.Conversation -> Icons.Default.ChatBubbleOutline; Page.Library -> Icons.Default.MenuBook; else -> Icons.Default.Settings
                            }
                            NavigationBarItem(
                                selected = vm.tab == page,
                                onClick = { vm.navigate(page) },
                                icon = { Icon(icon, tr(key)) },
                                label = { Text(tr(key)) })
                        }
                    }
                }
            ) { padding ->
                Box(Modifier
                    .fillMaxSize()
                    .padding(padding)) {
                    when {
                        vm.initializing -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = androidx.compose.ui.Alignment.Center
                        ) { CircularProgressIndicator() }

                        settings.region.isBlank() -> RegionScreen(vm)
                        !signedIn -> AuthScreen(vm)
                        else -> screenState.SaveableStateProvider(vm.page.name) {
                            when (vm.page) {
                                Page.Translate -> TranslationScreen(vm)
                                Page.Conversation -> ConversationScreen(vm)
                                Page.Library -> LibraryScreen(vm)
                                Page.Settings -> SettingsScreen(vm)
                                Page.Models -> ModelsScreen(vm)
                                Page.Capture -> CaptureScreen(vm)
                                Page.Qr -> QrScreen(vm)
                                Page.Room -> RoomScreen(vm)
                            }
                        }
                    }
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (vm.error.isNotBlank()) AlertDialog(
                onDismissRequest = { vm.error = "" },
                title = { Text(tr("auth.error")) },
                text = { Text(vm.error) },
                confirmButton = { TextButton(onClick = { vm.error = "" }) { Text("OK") } })
            if (vm.notice.isNotBlank()) AlertDialog(
                onDismissRequest = { vm.notice = "" },
                text = { Text(vm.notice) },
                confirmButton = { TextButton(onClick = { vm.notice = "" }) { Text("OK") } })
        }
    }
}
