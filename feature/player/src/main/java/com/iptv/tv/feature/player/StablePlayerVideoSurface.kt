package com.iptv.tv.feature.player

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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
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
import com.iptv.tv.core.playervlc.LibVlcFallbackPolicy
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val STABLE_IPTV_USER_AGENT = "Rinat-IPTV/1.0 (Android TV; Media3)"
private const val FIRST_VIDEO_FRAME_TIMEOUT_MS = 8_000L
private const val VIDEO_RECOVERY_TIMEOUT_MS = 6_000L

internal fun stablePictureConfirmed(
    firstFrameRendered: Boolean,
    videoWidth: Int,
    videoHeight: Int
): Boolean = firstFrameRendered && videoWidth > 0 && videoHeight > 0

private enum class StablePlaybackBackend {
    MEDIA3,
    LIBVLC
}

@Composable
@UnstableApi
internal fun StableVideoSurface(
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
    var backend by remember(session.sessionId) { mutableStateOf(StablePlaybackBackend.MEDIA3) }
    var media3Failure by remember(session.sessionId) { mutableStateOf<String?>(null) }

    Box(modifier = modifier.background(Color.Black)) {
        when (backend) {
            StablePlaybackBackend.MEDIA3 -> StableMedia3VideoSurface(
                session = session,
                scale = scale,
                expanded = expanded,
                volume = volume,
                showControls = showControls,
                onToggleControls = onToggleControls,
                onVolumeUp = onVolumeUp,
                onVolumeDown = onVolumeDown,
                onToggleMute = onToggleMute,
                onReady = onReady,
                onError = { message ->
                    val decision = LibVlcFallbackPolicy.evaluate(message)
                    if (decision.shouldFallback) {
                        media3Failure = "$message (${decision.diagnostic})"
                        backend = StablePlaybackBackend.LIBVLC
                    } else {
                        onError(message)
                    }
                },
                onToggleFullscreen = onToggleFullscreen,
                onPreviousChannel = onPreviousChannel,
                onNextChannel = onNextChannel,
                modifier = Modifier.fillMaxSize()
            )

            StablePlaybackBackend.LIBVLC -> StableLibVlcVideoSurface(
                session = session,
                scale = scale,
                expanded = expanded,
                volume = volume,
                showControls = showControls,
                onToggleControls = onToggleControls,
                onVolumeUp = onVolumeUp,
                onVolumeDown = onVolumeDown,
                onToggleMute = onToggleMute,
                onReady = onReady,
                onError = { vlcMessage ->
                    val original = media3Failure ?: "неизвестная ошибка Media3"
                    onError("Media3: $original; LibVLC: $vlcMessage")
                },
                onToggleFullscreen = onToggleFullscreen,
                onPreviousChannel = onPreviousChannel,
                onNextChannel = onNextChannel,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (showControls && backend == StablePlaybackBackend.MEDIA3) {
            OutlinedButton(
                onClick = {
                    media3Failure = "Ручное переключение: изображение Media3 не отображается"
                    backend = StablePlaybackBackend.LIBVLC
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Text("LibVLC")
            }
        }
    }

}

@Composable
@UnstableApi
private fun StableMedia3VideoSurface(
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
    // Keep the expensive Media3/MediaCodec stack alive while zapping between ordinary IPTV
    // channels. Rebuild only when the HTTP transport or buffering policy actually changes.
    val playerResult = remember(session.requestHeaders, session.bufferConfig) {
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

            val trackSelector = DefaultTrackSelector(context).apply {
                val parameters = buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    // The heap limit is not a decoder capability signal. Capping a 256 MB TV box
                    // at 720p made Media3 discard otherwise supported 1080p video tracks and play
                    // audio only. MediaCodec owns its native buffers, so keep the primary player
                    // eligible for the source resolution and let renderer capability checks decide.
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setTunnelingEnabled(false)
                setParameters(parameters)
            }

            ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(session.bufferConfig.toLoadControl())
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                            .build(),
                        true
                    )
                }
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

    val inputHandler = remember(context) { StablePlayerInputHandler(context) }
    inputHandler.update(
        expanded = expanded,
        callbacks = StablePlayerInputCallbacks(
            onToggleControls = onToggleControls,
            onToggleFullscreen = onToggleFullscreen,
            onPreviousChannel = onPreviousChannel,
            onNextChannel = onNextChannel,
            onVolumeUp = onVolumeUp,
            onVolumeDown = onVolumeDown,
            onToggleMute = onToggleMute,
            onTogglePlayback = {
                if (player.isPlaying) player.pause() else player.play()
            }
        )
    )

    LaunchedEffect(player, volume) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    var readyReported by remember(session.sessionId) { mutableStateOf(false) }
    var bufferingSinceMs by remember(session.sessionId) { mutableLongStateOf(0L) }
    var readySinceMs by remember(session.sessionId) { mutableLongStateOf(0L) }
    var softRecoveryCount by remember(session.sessionId) { mutableIntStateOf(0) }
    var firstVideoFrameRendered by remember(session.sessionId) { mutableStateOf(false) }
    var videoWidth by remember(session.sessionId) { mutableIntStateOf(0) }
    var videoHeight by remember(session.sessionId) { mutableIntStateOf(0) }
    var audioTrackSelected by remember(session.sessionId) { mutableStateOf(false) }
    var videoTrackSelected by remember(session.sessionId) { mutableStateOf(false) }
    var videoTrackSupported by remember(session.sessionId) { mutableStateOf(true) }
    var videoRecoveryStartedAt by remember(session.sessionId) { mutableLongStateOf(0L) }
    var videoFailureReported by remember(session.sessionId) { mutableStateOf(false) }
    var diagnosticMessage by remember(session.sessionId) { mutableStateOf<String?>(null) }

    DisposableEffect(player) {
        onDispose {
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
                player.clearVideoSurface()
                player.release()
            }
        }
    }

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
                if (videoWidth > 0 && videoHeight > 0) diagnosticMessage = null
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                if (firstVideoFrameRendered && videoWidth > 0 && videoHeight > 0) {
                    diagnosticMessage = null
                }
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
            player.playWhenReady = false
            player.stop()
            player.clearMediaItems()
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
            val pictureConfirmed = stablePictureConfirmed(
                firstFrameRendered = firstVideoFrameRendered,
                videoWidth = videoWidth,
                videoHeight = videoHeight
            )
            val audioWithoutPicture = readyStarted > 0L &&
                audioTrackSelected &&
                !pictureConfirmed &&
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
                    diagnosticMessage = "Media3 не подтвердил изображение ${videoWidth}×${videoHeight}; переход на LibVLC"
                    videoFailureReported = true
                    onError(
                        "Звук воспроизводится, но Media3 не вывел подтверждённое изображение " +
                            "после перезапуска (video=${videoWidth}x${videoHeight})"
                    )
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
                    inputHandler.attachTo(this)
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
                inputHandler.attachTo(view)
                if (expanded && !view.hasFocus()) view.post { view.requestFocus() }
            },
            onRelease = { view ->
                inputHandler.detachFrom(view)
                view.player = null
            }
        )

        diagnosticMessage?.let { message ->
            Card(modifier = Modifier.align(Alignment.TopCenter).padding(12.dp)) {
                Text(
                    text = message,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        if (showControls && !expanded) {
            StableFullscreenButton(
                expanded = false,
                onClick = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
            )
        }
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
