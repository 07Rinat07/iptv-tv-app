package com.iptv.tv.feature.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.tv.core.player.toLoadControl
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val STABLE_IPTV_USER_AGENT = "Rinat-IPTV/1.0 (Android TV; Media3)"
private const val FIRST_VIDEO_FRAME_TIMEOUT_MS = 12_000L
private const val VIDEO_RECOVERY_TIMEOUT_MS = 14_000L

@Composable
@UnstableApi
internal fun StableVideoSurface(
    session: InternalPlaybackSession,
    scale: PlayerVideoScale,
    expanded: Boolean,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playerResult = remember(session.sessionId, session.streamUrl, session.requestHeaders) {
        runCatching {
            val requestHeaders = session.requestHeaders
                .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            val userAgent = session.requestHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?: STABLE_IPTV_USER_AGENT

            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(requestHeaders)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            val trackSelector = DefaultTrackSelector(context).apply {
                val parameters = buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setExceedVideoConstraintsIfNecessary(maxHeapMb > 256L)
                    .setTunnelingEnabled(false)
                when {
                    maxHeapMb <= 256L -> parameters.setMaxVideoSize(1280, 720)
                    maxHeapMb <= 512L -> parameters.setMaxVideoSize(1920, 1080)
                }
                setParameters(parameters)
            }

            ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(session.bufferConfig.toLoadControl())
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
        }
    }

    val player = playerResult.getOrNull()
    val initError = playerResult.exceptionOrNull()
    if (player == null) {
        LaunchedEffect(initError) {
            onError("Не удалось создать Media3: ${initError?.message ?: "неизвестная ошибка"}")
        }
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Ошибка инициализации видеоплеера", color = Color.White)
        }
        return
    }

    var readyReported by remember(session.sessionId) { mutableStateOf(false) }
    var bufferingSinceMs by remember(session.sessionId) { mutableLongStateOf(0L) }
    var readySinceMs by remember(session.sessionId) { mutableLongStateOf(0L) }
    var softRecoveryCount by remember(session.sessionId) { mutableIntStateOf(0) }
    var firstVideoFrameRendered by remember(session.sessionId) { mutableStateOf(false) }
    var audioTrackSelected by remember(session.sessionId) { mutableStateOf(false) }
    var videoTrackSelected by remember(session.sessionId) { mutableStateOf(false) }
    var videoTrackSupported by remember(session.sessionId) { mutableStateOf(true) }
    var videoRecoveryStartedAt by remember(session.sessionId) { mutableLongStateOf(0L) }
    var videoFailureReported by remember(session.sessionId) { mutableStateOf(false) }
    var diagnosticMessage by remember(session.sessionId) { mutableStateOf<String?>(null) }

    DisposableEffect(session.sessionId, player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        bufferingSinceMs = 0L
                        if (readySinceMs == 0L) readySinceMs = System.currentTimeMillis()
                        if (!readyReported) {
                            readyReported = true
                            onReady()
                        }
                    }

                    Player.STATE_BUFFERING -> {
                        if (bufferingSinceMs == 0L) bufferingSinceMs = System.currentTimeMillis()
                    }

                    else -> bufferingSinceMs = 0L
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                audioTrackSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
                videoTrackSelected = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                videoTrackSupported = tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
            }

            override fun onRenderedFirstFrame() {
                firstVideoFrameRendered = true
                diagnosticMessage = null
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    onError("${error.errorCodeName}: ${error.message ?: "ошибка воспроизведения"}")
                }
            }
        }

        player.addListener(listener)
        runCatching {
            val mediaItem = MediaItem.Builder()
                .setUri(session.streamUrl)
                .also { builder -> stableInferMimeType(session.streamUrl)?.let(builder::setMimeType) }
                .build()
            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        }.onFailure { onError(it.message ?: it.javaClass.simpleName) }

        onDispose {
            player.removeListener(listener)
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
                player.clearVideoSurface()
                player.release()
            }
        }
    }

    LaunchedEffect(session.sessionId, player) {
        while (isActive) {
            delay(2_000L)
            val now = System.currentTimeMillis()

            val bufferingStarted = bufferingSinceMs
            if (bufferingStarted > 0L) {
                val elapsed = now - bufferingStarted
                val firstRecoveryAt = (session.bufferConfig.bufferForPlaybackAfterRebufferMs * 4L)
                    .coerceIn(10_000L, 24_000L)
                if (elapsed >= firstRecoveryAt && softRecoveryCount < 2) {
                    softRecoveryCount += 1
                    bufferingSinceMs = now
                    runCatching {
                        if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
                        player.prepare()
                        player.playWhenReady = true
                    }
                } else if (elapsed >= 45_000L && softRecoveryCount >= 2) {
                    onError("Поток не отвечает после автоматического восстановления буфера")
                    bufferingSinceMs = 0L
                }
            }

            val readyStarted = readySinceMs
            val audioWithoutPicture = readyStarted > 0L &&
                audioTrackSelected &&
                !firstVideoFrameRendered &&
                now - readyStarted >= FIRST_VIDEO_FRAME_TIMEOUT_MS

            if (!audioWithoutPicture || videoFailureReported) continue

            when {
                !videoTrackSelected && !videoTrackSupported -> {
                    diagnosticMessage = "Звук получен, но видеокодек не поддерживается этим устройством"
                    videoFailureReported = true
                    onError("Видеодорожка найдена, но устройство не поддерживает её кодек или профиль")
                }

                !videoTrackSelected -> {
                    diagnosticMessage = "В потоке выбрана аудиодорожка, но видеодорожка отсутствует"
                    videoFailureReported = true
                    onError("Поток передал звук без доступной видеодорожки")
                }

                videoRecoveryStartedAt == 0L -> {
                    diagnosticMessage = "Восстановление видеодекодера…"
                    videoRecoveryStartedAt = now
                    runCatching {
                        player.pause()
                        player.seekToDefaultPosition()
                        player.prepare()
                        player.playWhenReady = true
                    }
                }

                now - videoRecoveryStartedAt >= VIDEO_RECOVERY_TIMEOUT_MS -> {
                    diagnosticMessage = "Видеодорожка выбрана, но декодер не вывел первый кадр"
                    videoFailureReported = true
                    onError("Звук воспроизводится, но видеодекодер не вывел изображение после перезапуска")
                }
            }
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    controllerAutoShow = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    keepScreenOn = true
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnClickListener { onToggleFullscreen() }
                    setOnKeyListener { _: View, keyCode: Int, event: AndroidKeyEvent ->
                        if (event.action != AndroidKeyEvent.ACTION_UP) return@setOnKeyListener false
                        when (stableRemoteActionForKey(keyCode)) {
                            StableRemoteAction.TOGGLE_FULLSCREEN -> {
                                onToggleFullscreen()
                                true
                            }

                            StableRemoteAction.NEXT_CHANNEL -> {
                                onNextChannel()
                                true
                            }

                            StableRemoteAction.PREVIOUS_CHANNEL -> {
                                onPreviousChannel()
                                true
                            }

                            StableRemoteAction.NONE -> false
                        }
                    }
                    this.player = player
                    if (expanded) post { requestFocus() }
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = when (scale) {
                    PlayerVideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PlayerVideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    PlayerVideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                view.setOnClickListener { onToggleFullscreen() }
                if (expanded && !view.hasFocus()) view.post { view.requestFocus() }
            },
            onRelease = { view ->
                view.setOnClickListener(null)
                view.setOnKeyListener(null)
                view.player = null
            }
        )

        diagnosticMessage?.let { message ->
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        StableFullscreenButton(
            expanded = expanded,
            onClick = onToggleFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        )
    }
}

@Composable
internal fun StableFullscreenButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(if (expanded) "⤢" else "⛶")
    }
}

private fun stableInferMimeType(url: String): String? {
    val normalized = url.substringBefore('?').lowercase(Locale.ROOT)
    return when {
        normalized.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        normalized.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        normalized.endsWith(".ism") || normalized.endsWith(".isml") ||
            normalized.contains(".ism/manifest") -> MimeTypes.APPLICATION_SS
        normalized.endsWith(".mp4") || normalized.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        normalized.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        normalized.endsWith(".ts") || normalized.endsWith(".mpegts") -> MimeTypes.VIDEO_MP2T
        else -> null
    }
}
