package com.iptv.tv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.iptv.tv.core.playervlc.LibVlcCompatibilityEvidence
import com.iptv.tv.core.playervlc.LibVlcPlaybackConfig
import com.iptv.tv.core.playervlc.LibVlcPlaybackController
import com.iptv.tv.core.playervlc.LibVlcPlaybackListener
import com.iptv.tv.core.playervlc.LibVlcVideoScale
import com.iptv.tv.core.playervlc.LibVlcVideoView
import com.iptv.tv.core.playervlc.toDiagnosticMessage
import com.iptv.tv.core.utils.FileLogger

@Composable
internal fun StableLibVlcVideoSurface(
    session: InternalPlaybackSession,
    scale: PlayerVideoScale,
    expanded: Boolean,
    volume: Float,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    onToggleMute: () -> Unit,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val currentOnReady by rememberUpdatedState(onReady)
    val currentOnError by rememberUpdatedState(onError)
    val currentToggleControls by rememberUpdatedState(onToggleControls)
    val currentToggleFullscreen by rememberUpdatedState(onToggleFullscreen)
    val currentPreviousChannel by rememberUpdatedState(onPreviousChannel)
    val currentNextChannel by rememberUpdatedState(onNextChannel)
    val currentVolumeUp by rememberUpdatedState(onVolumeUp)
    val currentVolumeDown by rememberUpdatedState(onVolumeDown)
    val currentToggleMute by rememberUpdatedState(onToggleMute)

    val controllerResult = remember(session.sessionId) {
        runCatching {
            LibVlcPlaybackController(
                context = context,
                config = LibVlcPlaybackConfig(
                    networkCachingMs = session.bufferConfig.bufferForPlaybackAfterRebufferMs,
                    liveCachingMs = session.bufferConfig.bufferForPlaybackAfterRebufferMs,
                    enableHardwareDecoding = true
                ),
                listener = object : LibVlcPlaybackListener {
                    override fun onReady() = currentOnReady()

                    override fun onEnded() {
                        currentOnError("LibVLC: поток завершён")
                    }

                    override fun onError(message: String) = currentOnError(message)

                    override fun onCompatibilityEvidence(evidence: LibVlcCompatibilityEvidence) {
                        FileLogger.write(
                            context = context,
                            level = "INFO",
                            tag = "PlaybackCompatibility",
                            message = "event=ready, sessionId=${session.sessionId}, " +
                                evidence.toDiagnosticMessage()
                        )
                    }
                }
            )
        }
    }
    val controller = controllerResult.getOrNull()
    val initError = controllerResult.exceptionOrNull()

    if (controller == null) {
        LaunchedEffect(initError) {
            currentOnError("Не удалось создать LibVLC: ${initError?.message ?: "неизвестная ошибка"}")
        }
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Ошибка инициализации LibVLC", color = Color.White)
        }
        return
    }

    val inputHandler = remember(context) { StablePlayerInputHandler(context) }
    inputHandler.update(
        expanded = expanded,
        controlsVisible = showControls,
        callbacks = StablePlayerInputCallbacks(
            onToggleControls = { currentToggleControls() },
            onToggleFullscreen = { currentToggleFullscreen() },
            onPreviousChannel = { currentPreviousChannel() },
            onNextChannel = { currentNextChannel() },
            onVolumeUp = { currentVolumeUp() },
            onVolumeDown = { currentVolumeDown() },
            onToggleMute = { currentToggleMute() },
            onTogglePlayback = controller::togglePlayPause
        )
    )

    LaunchedEffect(controller, volume) {
        controller.setVolume(volume)
    }

    LaunchedEffect(controller, scale) {
        controller.setScale(scale.toLibVlcScale())
    }

    DisposableEffect(controller) {
        onDispose { controller.release() }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                LibVlcVideoView(viewContext).apply {
                    keepScreenOn = true
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    inputHandler.attachTo(this)
                    runCatching {
                        controller.attach(this)
                        controller.setVolume(volume)
                        controller.setScale(scale.toLibVlcScale())
                        controller.play(session.streamUrl, session.requestHeaders)
                    }.onFailure { error ->
                        currentOnError("LibVLC: ${error.message ?: error.javaClass.simpleName}")
                    }
                    if (expanded) post { requestFocus() }
                }
            },
            onReset = { },
            update = { view ->
                inputHandler.attachTo(view)
                controller.setVolume(volume)
                controller.setScale(scale.toLibVlcScale())
                if (expanded && !view.hasFocus()) view.post { view.requestFocus() }
            },
            onRelease = { view ->
                inputHandler.detachFrom(view)
                controller.detach()
            }
        )

        Card(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)) {
            Text(
                text = "Резервный декодер LibVLC",
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (showControls && !expanded) {
            StableFullscreenButton(
                expanded = false,
                onClick = currentToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            )
        }
    }
}

private fun PlayerVideoScale.toLibVlcScale(): LibVlcVideoScale = when (this) {
    PlayerVideoScale.FIT -> LibVlcVideoScale.FIT
    PlayerVideoScale.FILL -> LibVlcVideoScale.FILL
    PlayerVideoScale.ZOOM -> LibVlcVideoScale.ZOOM
}
