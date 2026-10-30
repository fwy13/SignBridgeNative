package com.fwy13.signbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.AspectRatioFrameLayout
import com.fwy13.signbridge.network.VideoRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive




@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String,
    repository: VideoRepository,
    modifier: Modifier = Modifier,
    loop: Boolean = true
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var attempt by remember(url) { mutableIntStateOf(0) }
    var resolved by remember(url, attempt) { mutableStateOf<String?>(null) }
    var error by remember(url, attempt) { mutableStateOf("") }
    var loading by remember(url, attempt) { mutableStateOf(true) }
    var fullscreen by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url, attempt) {
        loading = true
        error = ""

        try {
            val source = withContext(Dispatchers.IO) {
                repository.playable(url)
            }

            ensureActive()
            resolved = source
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w("SignBridgeVideo", "Unable to resolve video", failure)
            error = "video_load_failed"
            loading = false
        }
    }
    val player = remember(context, url, resolved, attempt) {
        resolved?.let { source ->
            ExoPlayer.Builder(context).build().apply {
                repeatMode = if (loop) {
                    Player.REPEAT_MODE_ONE
                } else {
                    Player.REPEAT_MODE_OFF
                }

                setMediaItem(MediaItem.fromUri(source))
                prepare()

                playWhenReady = owner.lifecycle.currentState
                    .isAtLeast(Lifecycle.State.STARTED)
            }
        }
    }

    LaunchedEffect(player, loop) {
        player?.repeatMode = if (loop) {
            Player.REPEAT_MODE_ONE
        } else {
            Player.REPEAT_MODE_OFF
        }
    }

    var playing by remember(player) { mutableStateOf(true) }
    var duration by remember(player) { mutableLongStateOf(0L) }
    var position by remember(player) { mutableLongStateOf(0L) }
    var muted by remember(player) { mutableStateOf(false) }
    var speed by remember(player) { mutableFloatStateOf(1f) }
    var showSpeeds by remember { mutableStateOf(false) }
    var seeking by remember(player) { mutableStateOf(false) }
    var seekFraction by remember(player) { mutableFloatStateOf(0f) }
    val playbackBinding = remember(player) { PlaybackBinding() }

    DisposableEffect(player, owner) {
        val currentPlayer = player

        var resumeOnStart = currentPlayer?.let {
            it.playWhenReady ||
                    !owner.lifecycle.currentState.isAtLeast(
                        Lifecycle.State.STARTED
                    )
        } ?: false

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    resumeOnStart = currentPlayer?.playWhenReady == true
                    currentPlayer?.pause()
                }

                Lifecycle.Event.ON_START -> {
                    if (resumeOnStart) {
                        currentPlayer?.play()
                        resumeOnStart = false
                    }
                }

                else -> Unit
            }
        }

        owner.lifecycle.addObserver(observer)

        onDispose {
            owner.lifecycle.removeObserver(observer)
        }
    }
    DisposableEffect(player, owner) {
        var resume = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                resume = player?.playWhenReady == true; player?.pause()
            }
            if (event == Lifecycle.Event.ON_START && resume) {
                player?.play(); resume = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player) {
        while (player != null) {
            duration = player.duration.coerceAtLeast(0); position = player.currentPosition; delay(
                400
            )
        }
    }
    val controls: @Composable () -> Unit = {
        if (player != null && error.isBlank()) Column {
            Slider(
                value = if (seeking) {
                    seekFraction
                } else if (duration > 0L) {
                    (position.toFloat() / duration).coerceIn(0f, 1f)
                } else {
                    0f
                },
                onValueChange = { fraction ->
                    seeking = true
                    seekFraction = fraction
                },
                onValueChangeFinished = {
                    player?.let { currentPlayer ->
                        if (duration > 0L) {
                            val target = (seekFraction * duration)
                                .toLong()
                                .coerceIn(0L, duration)

                            currentPlayer.seekTo(target)
                            position = target
                        }
                    }
                    seeking = false
                },
                enabled = duration > 0L,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (player.isPlaying) player.pause() else {
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0); player.play()
                    }
                }) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        bilingual("Phát / Tạm dừng", "Play / Pause")
                    )
                }
                IconButton(onClick = {
                    muted = !muted; player.volume = if (muted) 0f else 1f
                }) {
                    Icon(
                        if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        bilingual("Âm thanh", "Audio")
                    )
                }
                Box {
                    TextButton(
                        onClick = { showSpeeds = true },
                        contentPadding = PaddingValues(2.dp)
                    ) { Text("${speed}×") }
                    DropdownMenu(expanded = showSpeeds, onDismissRequest = { showSpeeds = false }) {
                        listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                            DropdownMenuItem(
                                text = { Text("${value}×") },
                                onClick = {
                                    speed = value; player.setPlaybackSpeed(value); showSpeeds =
                                    false
                                })
                        }
                    }
                }
                Text(
                    "${position / 60000}:${(position / 1000 % 60).toString().padStart(2, '0')}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall
                )
                IconButton(onClick = {
                    fullscreen = !fullscreen
                }) {
                    Icon(
                        if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        bilingual("Toàn màn hình", "Full screen")
                    )
                }
            }
        }
    }
    val content: @Composable ColumnScope.() -> Unit = {
        Box(Modifier
            .fillMaxWidth()
            .weight(1f), contentAlignment = Alignment.Center) {
            if (player != null) PlaybackSurface(
                player = player,
                binding = playbackBinding,
                modifier = Modifier.fillMaxSize()
            )
            if (loading) CircularProgressIndicator()
            if (error.isNotBlank()) Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(tr("library.videoError")); TextButton(onClick = { attempt++ }) { Text(tr("library.retry")) }
                }
            }
        }
        controls()
    }
    if (fullscreen) {
        Box(modifier)
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .systemBarsPadding()
                        .padding(8.dp), content = content
                )
            }
        }
    } else Column(modifier, content = content)
}

@androidx.annotation.OptIn(
    androidx.media3.common.util.UnstableApi::class
)
@Composable
private fun PlaybackSurface(
    player: ExoPlayer,
    binding: PlaybackBinding,
    modifier: Modifier
) {
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier,
        onReset = null,
        onRelease = { view ->
            if (binding.view === view) {
                PlayerView.switchTargetView(player, view, null)
                binding.view = null
            } else {
                view.player = null
            }
        },
        update = { view ->
            if (binding.view !== view) {
                PlayerView.switchTargetView(
                    player,
                    binding.view,
                    view
                )
                binding.view = view
            }
        }
    )
}


private class PlaybackBinding {
    var view: PlayerView? = null
}