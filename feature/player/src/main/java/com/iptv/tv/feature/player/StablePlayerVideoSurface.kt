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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.tv.core.player.Media3CompatibilityEvidenceTracker
import com.iptv.tv.core.player.p2pMedia3BufferConfig
import com.iptv.tv.core.player.toDiagnosticMessage
import com.iptv.tv.core.player.toLoadControl
import com.iptv.tv.core.playervlc.LibVlcFallbackPolicy
import com.iptv.tv.core.utils.FileLogger
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val STABLE_IPTV_USER_AGENT = "Rinat-IPTV/1.0 (Android TV; Media3)"
private const val FIRST_VIDEO_FRAME_TIMEOUT_MS = 8_000L
private const val VIDEO_RECOVERY_TIMEOUT_MS = 6_000L
private const val MAX_LOAD_ERROR_DIAGNOSTIC_CHARS = 220

internal fun stablePictureConfirmed(
    firstFrameRendered: Boolean,
    videoWidth: Int,
    videoHeight: Int
): Boolean = firstFrameRendered && videoWidth > 0 && videoHeight > 0

internal fun stableMedia3LoadErrorDiagnostic(error: IOException): String {
    val type = error.javaClass.name.ifBlank { error.javaClass.simpleName }
    val message = error.message
        ?.replace('\n', ' ')
        ?.replace('\r', ' ')
        ?.trim()
        .orEmpty()
    val cause = error.cause?.let { value ->
        val causeType = value.javaClass.name.ifBlank { value.javaClass.simpleName }
        val causeMessage = value.message
            ?.replace('\n', ' ')
            ?.replace('\r', ' ')
            ?.trim()
            .orEmpty()
        if (causeMessage.isBlank()) causeType else "$causeType:$causeMessage"
    }
    return buildString {
        append(type)
        if (message.isNotBlank()) append(':').append(message)
        if (!cause.isNullOrBlank()) append(" <- ").append(cause)
    }.take(MAX_LOAD_ERROR_DIAGNOSTIC_CHARS)
}

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
    onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit = {},
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
                onP2pBoundaryTelemetry = onP2pBoundaryTelemetry,
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
    onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit,
    onError: (String) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val media3BufferConfig = remember(session.bufferConfig, session.isP2pPlayback) {
        if (session.isP2pPlayback) {
            p2pMedia3BufferConfig(session.bufferConfig)
        } else {
            session.bufferConfig
        }
    }
    val playerLifecycleKey = StableP2pMedia3RecoveryPolicy.playerLifecycleKey(
        isP2pPlayback = session.isP2pPlayback,
        sessionId = session.sessionId
    )

    // Ordinary IPTV keeps the expensive Media3/MediaCodec stack across zaps. A localhost P2P URL,
    // however, belongs to exactly one prepared embedded runtime. A re-prepared P2P session must not
    // inherit a loader/codec stack that still owns callbacks for the previous localhost server.
    val playerResult = remember(
        session.requestHeaders,
        media3BufferConfig,
        session.isP2pPlayback,
        playerLifecycleKey
    ) {
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
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory).apply {
                if (session.isP2pPlayback) {
                    // The embedded runtime owns recovery. Retrying a closed 127.0.0.1 URL only
                    // reopens a dead generation and was observed immediately before process exits.
                    setLoadErrorHandlingPolicy(
                        DefaultLoadErrorHandlingPolicy(
                            StableP2pMedia3RecoveryPolicy.MIN_LOADABLE_RETRY_COUNT
                        )
                    )
                }
            }
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

            val trackSelector = DefaultTrackSelector(context).apply {
                val parameters = buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setTunnelingEnabled(false)
                setParameters(parameters)
            }

            ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(media3BufferConfig.toLoadControl())
                .setMediaSourceFactory(mediaSourceFactory)
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
        controlsVisible = showControls,
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
    var terminalFailureReported by remember(session.sessionId) { mutableStateOf(false) }
    var diagnosticMessage by remember(session.sessionId) { mutableStateOf<String?>(null) }

    fun reportTerminalFailure(message: String) {
        if (terminalFailureReported) return
        terminalFailureReported = true
        bufferingSinceMs = 0L
        runCatching {
            player.playWhenReady = false
            player.stop()
        }
        onError(message)
    }

    val p2pBoundaryTelemetryTracker = remember(
        session.sessionId,
        session.playbackStartedAtMillis,
        session.isP2pPlayback
    ) {
        if (session.isP2pPlayback) {
            P2pPlayerBoundaryTelemetryTracker(
                sessionId = session.sessionId,
                playbackStartedAtMillis = session.playbackStartedAtMillis
                    .takeIf { it > 0L }
                    ?: System.currentTimeMillis()
            )
        } else {
            null
        }
    }

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
        val compatibilityEvidenceTracker = Media3CompatibilityEvidenceTracker()
        var compatibilityEvidenceReported = false

        fun emitMedia3CompatibilityEvidence(event: String, audioOnly: Boolean = false) {
            if (compatibilityEvidenceReported) return
            val evidence = compatibilityEvidenceTracker.snapshot()
            if (audioOnly && evidence.videoTrackPresent) return
            if (evidence.selectedVideoTracks.isEmpty() && evidence.selectedAudioTracks.isEmpty()) return
            if (evidence.videoDecoderName == null && evidence.audioDecoderName == null) return

            compatibilityEvidenceReported = true
            FileLogger.write(
                context = context,
                level = "INFO",
                tag = "PlaybackCompatibility",
                message = "event=$event, sessionId=${session.sessionId}, " +
                    evidence.toDiagnosticMessage()
            )
        }

        fun emitP2pBoundaryTelemetry(telemetry: P2pPlayerBoundaryTelemetry) {
            onP2pBoundaryTelemetry(telemetry)
            if (telemetry.event == P2pPlayerBoundaryEventType.TERMINAL) {
                FileLogger.write(
                    context = context,
                    level = "INFO",
                    tag = "P2pBoundarySession",
                    message = "event=terminal, sessionId=${telemetry.sessionId}, " +
                        "requestId=${session.requestId}, elapsed_ms=${telemetry.elapsedSincePlaybackStartMillis}, " +
                        "ready=${telemetry.readySeen}, first_audio=${telemetry.firstAudioSeen}, " +
                        "first_video_frame=${telemetry.firstVideoFrameSeen}, " +
                        "rebuffer_count=${telemetry.rebufferCount}, " +
                        "rebuffer_ms=${telemetry.totalRebufferDurationMillis}, " +
                        "load_attempts=${telemetry.loadAttemptCount}, " +
                        "load_completed=${telemetry.loadCompletedCount}, " +
                        "load_errors=${telemetry.loadErrorCount}, load_retries=${telemetry.loadRetryCount}"
                )
            }
            val evidence = telemetry.loadEvidence ?: return
            FileLogger.write(
                context = context,
                level = "INFO",
                tag = "P2pBoundaryLoad",
                message = "event=${telemetry.event.wireName}, sessionId=${telemetry.sessionId}, " +
                    "requestId=${session.requestId}, elapsed_ms=${telemetry.elapsedSincePlaybackStartMillis}, " +
                    "load_attempts=${telemetry.loadAttemptCount}, " +
                    "load_completed=${telemetry.loadCompletedCount}, " +
                    "load_errors=${telemetry.loadErrorCount}, load_retries=${telemetry.loadRetryCount}, " +
                    "load_event_ms=${telemetry.loadEventDurationMillis}, task=${evidence.taskId}, " +
                    "position=${evidence.positionBytes}, length=${evidence.lengthBytes ?: "none"}, " +
                    "bytes=${evidence.bytesLoaded}, canceled=${evidence.wasCanceled ?: "none"}, " +
                    "error_type=${evidence.errorType ?: "none"}"
            )
        }

        fun loadEvidence(
            loadEventInfo: LoadEventInfo,
            wasCanceled: Boolean? = null,
            errorType: String? = null
        ) = P2pLoadBoundaryEvidence(
            taskId = loadEventInfo.loadTaskId,
            positionBytes = loadEventInfo.dataSpec.position,
            lengthBytes = loadEventInfo.dataSpec.length.takeIf { it >= 0L },
            bytesLoaded = loadEventInfo.bytesLoaded.coerceAtLeast(0L),
            wasCanceled = wasCanceled,
            errorType = errorType?.take(MAX_LOAD_ERROR_DIAGNOSTIC_CHARS)
        )

        val analyticsListener = object : AnalyticsListener {
            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val tracker = p2pBoundaryTelemetryTracker ?: return
                tracker.onLoadStarted(
                    nowMillis = System.currentTimeMillis(),
                    evidence = loadEvidence(loadEventInfo)
                )?.let(::emitP2pBoundaryTelemetry)
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData
            ) {
                val tracker = p2pBoundaryTelemetryTracker ?: return
                tracker.onLoadCompleted(
                    nowMillis = System.currentTimeMillis(),
                    loadDurationMillis = loadEventInfo.loadDurationMs.coerceAtLeast(0L),
                    evidence = loadEvidence(loadEventInfo)
                )?.let(::emitP2pBoundaryTelemetry)
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                val tracker = p2pBoundaryTelemetryTracker ?: return
                val now = System.currentTimeMillis()
                val diagnostic = stableMedia3LoadErrorDiagnostic(error)
                val evidence = loadEvidence(
                    loadEventInfo = loadEventInfo,
                    wasCanceled = wasCanceled,
                    errorType = diagnostic
                )
                tracker.onLoadError(
                    nowMillis = now,
                    loadDurationMillis = loadEventInfo.loadDurationMs.coerceAtLeast(0L),
                    evidence = evidence
                ).let(::emitP2pBoundaryTelemetry)

                FileLogger.write(
                    context = context,
                    level = "WARN",
                    tag = "P2pBoundaryLoadError",
                    message = "sessionId=${session.sessionId}, requestId=${session.requestId}, " +
                        "task=${loadEventInfo.loadTaskId}, canceled=$wasCanceled, error=$diagnostic"
                )

                // Keep telemetry honest if Media3 itself still reports a retry decision. The P2P
                // media-source policy above has zero retry budget, so a non-cancelled retry here is
                // diagnostic evidence of an unexpected policy path rather than permission to call
                // prepare() again ourselves.
                if (!wasCanceled) {
                    tracker.onLoadRetry(
                        nowMillis = now,
                        evidence = evidence
                    ).let(::emitP2pBoundaryTelemetry)
                }
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                compatibilityEvidenceTracker.onVideoDecoderInitialized(decoderName)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                compatibilityEvidenceTracker.onAudioDecoderInitialized(decoderName)
            }

            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long
            ) {
                p2pBoundaryTelemetryTracker
                    ?.onFirstAudio(System.currentTimeMillis())
                    ?.let(::emitP2pBoundaryTelemetry)
                emitMedia3CompatibilityEvidence(event = "first_audio", audioOnly = true)
            }
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        val now = System.currentTimeMillis()
                        bufferingSinceMs = 0L
                        if (readySinceMs == 0L) readySinceMs = now
                        p2pBoundaryTelemetryTracker
                            ?.onReady(now)
                            ?.let(::emitP2pBoundaryTelemetry)
                        if (!readyReported) {
                            readyReported = true
                            onReady()
                        }
                    }

                    Player.STATE_BUFFERING -> {
                        val now = System.currentTimeMillis()
                        if (bufferingSinceMs == 0L) bufferingSinceMs = now
                        p2pBoundaryTelemetryTracker
                            ?.onBuffering(now)
                            ?.let(::emitP2pBoundaryTelemetry)
                    }

                    else -> bufferingSinceMs = 0L
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                compatibilityEvidenceTracker.onTracksChanged(tracks)
                audioTrackSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
                videoTrackSelected = tracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
                videoTrackSupported = tracks.isTypeSupported(C.TRACK_TYPE_VIDEO)
            }

            override fun onRenderedFirstFrame() {
                firstVideoFrameRendered = true
                p2pBoundaryTelemetryTracker
                    ?.onFirstVideoFrame(System.currentTimeMillis())
                    ?.let(::emitP2pBoundaryTelemetry)
                emitMedia3CompatibilityEvidence(event = "first_video_frame")
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
                emitMedia3CompatibilityEvidence(event = "player_error")
                val diagnostic = "${error.errorCodeName}: ${error.message ?: "ошибка воспроизведения"}"
                FileLogger.write(
                    context = context,
                    level = "WARN",
                    tag = "P2pPlayerError",
                    message = "sessionId=${session.sessionId}, requestId=${session.requestId}, " +
                        "p2p=${session.isP2pPlayback}, error=$diagnostic"
                )
                if (
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW &&
                    StableP2pMedia3RecoveryPolicy.shouldReprepareSameSource(session.isP2pPlayback)
                ) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    reportTerminalFailure(diagnostic)
                }
            }
        }

        player.addListener(listener)
        player.addAnalyticsListener(analyticsListener)
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
        }.onFailure { reportTerminalFailure(it.message ?: it.javaClass.simpleName) }

        onDispose {
            p2pBoundaryTelemetryTracker
                ?.onTerminal(System.currentTimeMillis())
                ?.let(::emitP2pBoundaryTelemetry)
            player.removeAnalyticsListener(analyticsListener)
            player.removeListener(listener)
        }
    }

    LaunchedEffect(session.sessionId, player) {
        while (isActive) {
            delay(2_000L)
            val now = System.currentTimeMillis()

            val bufferingStarted = bufferingSinceMs
            if (bufferingStarted > 0L && !terminalFailureReported) {
                val elapsed = now - bufferingStarted
                val firstRecoveryAt = (session.bufferConfig.bufferForPlaybackAfterRebufferMs * 4L)
                    .coerceIn(10_000L, 24_000L)
                if (elapsed >= firstRecoveryAt && softRecoveryCount < 2) {
                    softRecoveryCount += 1
                    bufferingSinceMs = now
                    if (
                        StableP2pMedia3RecoveryPolicy.shouldReprepareSameSource(
                            session.isP2pPlayback
                        )
                    ) {
                        runCatching {
                            if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
                            player.prepare()
                            player.playWhenReady = true
                        }
                    } else {
                        FileLogger.write(
                            context = context,
                            level = "WARN",
                            tag = "P2pStaleSourceRecoveryBlocked",
                            message = "sessionId=${session.sessionId}, requestId=${session.requestId}, " +
                                "buffering_ms=$elapsed; escalating_to_p2p_reprepare=true"
                        )
                        reportTerminalFailure(
                            "P2P localhost-поток перестал отдавать данные; требуется новая P2P-сессия"
                        )
                    }
                } else if (elapsed >= 45_000L && softRecoveryCount >= 2) {
                    reportTerminalFailure("Поток не отвечает после автоматического восстановления буфера")
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

            if (!audioWithoutPicture || videoFailureReported || terminalFailureReported) continue

            when {
                !videoTrackSelected && !videoTrackSupported -> {
                    diagnosticMessage = "Звук получен, но видеокодек не поддерживается этим устройством"
                    videoFailureReported = true
                    reportTerminalFailure(
                        "Видеодорожка найдена, но устройство не поддерживает её кодек или профиль"
                    )
                }

                !videoTrackSelected -> {
                    diagnosticMessage = "В потоке выбрана аудиодорожка, но видеодорожка отсутствует"
                    videoFailureReported = true
                    reportTerminalFailure("Поток передал звук без доступной видеодорожки")
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
                    reportTerminalFailure(
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
            onReset = { },
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
