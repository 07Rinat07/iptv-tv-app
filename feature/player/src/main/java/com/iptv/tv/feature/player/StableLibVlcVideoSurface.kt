package com.iptv.tv.feature.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
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
import com.iptv.tv.core.playervlc.LibVlcPlaybackConfig
import com.iptv.tv.core.playervlc.LibVlcPlaybackController
import com.iptv.tv.core.playervlc.LibVlcPlaybackListener
import com.iptv.tv.core.playervlc.LibVlcVideoScale
import org.videolan.libvlc.util.VLCVideoLayout

@Composable
internal fun StableLibVlcVideoSurface(
    session: InternalPlaybackSession,
    scale: PlayerVideoScale,
    expanded: Boolean,
    volume: Float,
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
                VLCVideoLayout(viewContext).apply {
                    keepScreenOn = true
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnClickListener { currentToggleFullscreen() }
                    setOnKeyListener { _: View, keyCode: Int, event: AndroidKeyEvent ->
                        if (event.action != AndroidKeyEvent.ACTION_UP) return@setOnKeyListener false
                        when (stableRemoteActionForKey(keyCode)) {
                            StableRemoteAction.TOGGLE_FULLSCREEN -> {
                                currentToggleFullscreen()
                                true
                            }

                            StableRemoteAction.NEXT_CHANNEL -> {
                                currentNextChannel()
                                true
                            }

                            StableRemoteAction.PREVIOUS_CHANNEL -> {
                                currentPreviousChannel()
                                true
                            }

                            StableRemoteAction.VOLUME_UP -> {
                                currentVolumeUp()
                                true
                            }

                            StableRemoteAction.VOLUME_DOWN -> {
                                currentVolumeDown()
                                true
                            }

                            StableRemoteAction.TOGGLE_MUTE -> {
                                currentToggleMute()
                                true
                            }

                            StableRemoteAction.NONE -> false
                        }
                    }
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
            update = { view ->
                view.setOnClickListener { currentToggleFullscreen() }
                controller.setVolume(volume)
                controller.setScale(scale.toLibVlcScale())
                if (expanded && !view.hasFocus()) view.post { view.requestFocus() }
            },
            onRelease = { view ->
                view.setOnClickListener(null)
                view.setOnKeyListener(null)
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

        StableFullscreenButton(
            expanded = expanded,
            onClick = currentToggleFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        )
    }
}

private fun PlayerVideoScale.toLibVlcScale(): LibVlcVideoScale = when (this) {
    PlayerVideoScale.FIT -> LibVlcVideoScale.FIT
    PlayerVideoScale.FILL -> LibVlcVideoScale.FILL
    PlayerVideoScale.ZOOM -> LibVlcVideoScale.ZOOM
}
