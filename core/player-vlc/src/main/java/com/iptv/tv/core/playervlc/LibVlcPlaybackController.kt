package com.iptv.tv.core.playervlc

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia

private const val DEFAULT_NETWORK_CACHING_MS = 1_500
private const val MIN_CACHING_MS = 500
private const val MAX_CACHING_MS = 5_000

data class LibVlcPlaybackConfig(
    val networkCachingMs: Int = DEFAULT_NETWORK_CACHING_MS,
    val liveCachingMs: Int = DEFAULT_NETWORK_CACHING_MS,
    val enableHardwareDecoding: Boolean = true
)

enum class LibVlcVideoScale {
    FIT,
    FILL,
    ZOOM
}

interface LibVlcPlaybackListener {
    fun onReady()
    fun onEnded()
    fun onError(message: String)

    fun onCompatibilityEvidence(evidence: LibVlcCompatibilityEvidence) = Unit
}

/**
 * Минимальный жизненный цикл встроенного LibVLC fallback.
 *
 * Контроллер создаётся только после ошибки Media3 и освобождает все native-ресурсы вместе
 * с Compose surface. Он не участвует в получении каналов, сканировании или поиске.
 */
class LibVlcPlaybackController(
    context: Context,
    config: LibVlcPlaybackConfig = LibVlcPlaybackConfig(),
    private val listener: LibVlcPlaybackListener
) {
    private val boundedNetworkCachingMs = config.networkCachingMs.coerceIn(MIN_CACHING_MS, MAX_CACHING_MS)
    private val boundedLiveCachingMs = config.liveCachingMs.coerceIn(MIN_CACHING_MS, MAX_CACHING_MS)
    private val applicationContext = context.applicationContext
    private val libVlc = LibVLC(
        applicationContext,
        arrayListOf(
            "--audio-time-stretch",
            "--network-caching=$boundedNetworkCachingMs",
            "--live-caching=$boundedLiveCachingMs",
            "--clock-jitter=0",
            "--clock-synchro=1",
            "--no-snapshot-preview"
        )
    )
    private val mediaPlayer = MediaPlayer(libVlc)
    private val hardwareDecodingEnabled = config.enableHardwareDecoding
    private var attachedView: LibVlcVideoView? = null
    private var released = false
    private var compatibilityEvidenceReported = false

    init {
        mediaPlayer.setEventListener(
            MediaPlayer.EventListener { event ->
                when (event?.type) {
                    MediaPlayer.Event.Playing -> {
                        if (!compatibilityEvidenceReported) {
                            compatibilityEvidenceReported = true
                            runCatching(::captureCompatibilityEvidence)
                                .getOrNull()
                                ?.let(listener::onCompatibilityEvidence)
                        }
                        listener.onReady()
                    }
                    MediaPlayer.Event.EndReached -> listener.onEnded()
                    MediaPlayer.Event.EncounteredError -> listener.onError("LibVLC не смог открыть или декодировать поток")
                }
            }
        )
    }

    fun attach(view: LibVlcVideoView) {
        check(!released) { "LibVLC controller is released" }
        if (attachedView === view) return
        detach()
        mediaPlayer.attachViews(view.videoLayout, null, false, false)
        attachedView = view
    }

    fun play(
        streamUrl: String,
        requestHeaders: Map<String, String> = emptyMap()
    ) {
        check(!released) { "LibVLC controller is released" }
        val media = Media(libVlc, Uri.parse(streamUrl))
        try {
            media.setHWDecoderEnabled(hardwareDecodingEnabled, false)
            media.addOption(":network-caching=$boundedNetworkCachingMs")
            media.addOption(":live-caching=$boundedLiveCachingMs")
            media.addOption(":http-reconnect")
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=1")
            requestHeaders.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                ?.takeIf(String::isNotBlank)
                ?.let { media.addOption(":http-user-agent=$it") }
            requestHeaders.entries
                .firstOrNull {
                    it.key.equals("Referer", ignoreCase = true) ||
                        it.key.equals("Referrer", ignoreCase = true)
                }
                ?.value
                ?.takeIf(String::isNotBlank)
                ?.let { media.addOption(":http-referrer=$it") }
            mediaPlayer.media = media
        } finally {
            media.release()
        }
        mediaPlayer.play()
    }

    fun togglePlayPause() {
        if (released) return
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    fun setVolume(volume: Float) {
        if (released) return
        mediaPlayer.volume = (volume.coerceIn(0f, 1f) * 100f).toInt()
    }

    fun setScale(scale: LibVlcVideoScale) {
        if (released) return
        mediaPlayer.videoScale = when (scale) {
            LibVlcVideoScale.FIT -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            LibVlcVideoScale.FILL -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            LibVlcVideoScale.ZOOM -> MediaPlayer.ScaleType.SURFACE_FILL
        }
    }

    fun detach() {
        if (attachedView == null || released) return
        runCatching { mediaPlayer.detachViews() }
        attachedView = null
    }

    fun release() {
        if (released) return
        released = true
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        attachedView = null
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }

    private fun captureCompatibilityEvidence(): LibVlcCompatibilityEvidence {
        val selectedVideoTrackId = runCatching { mediaPlayer.videoTrack }.getOrDefault(-1)
        val selectedAudioTrackId = runCatching { mediaPlayer.audioTrack }.getOrDefault(-1)
        val tracks = mediaPlayer.media?.let { media ->
            try {
                (0 until media.trackCount)
                    .mapNotNull { index -> media.getTrack(index)?.toCompatibilityEvidence() }
            } finally {
                runCatching { media.release() }
            }
        }.orEmpty()

        return LibVlcCompatibilityEvidence(
            hardwareDecodingPreferred = hardwareDecodingEnabled,
            selectedVideoTrack = selectedLibVlcTrackEvidence(
                selectedTrackId = selectedVideoTrackId,
                kind = LibVlcTrackKind.VIDEO,
                tracks = tracks
            ),
            selectedAudioTrack = selectedLibVlcTrackEvidence(
                selectedTrackId = selectedAudioTrackId,
                kind = LibVlcTrackKind.AUDIO,
                tracks = tracks
            )
        )
    }

    private fun IMedia.Track.toCompatibilityEvidence(): LibVlcTrackEvidence? = when (this) {
        is IMedia.VideoTrack -> LibVlcTrackEvidence(
            kind = LibVlcTrackKind.VIDEO,
            id = id,
            codec = codec,
            originalCodec = originalCodec,
            profile = profile.takeIf { it >= 0 },
            level = level.takeIf { it >= 0 },
            bitrate = bitrate.takeIf { it > 0 },
            language = language,
            width = width.takeIf { it > 0 },
            height = height.takeIf { it > 0 },
            frameRateNumerator = frameRateNum.takeIf { it > 0 },
            frameRateDenominator = frameRateDen.takeIf { it > 0 }
        )

        is IMedia.AudioTrack -> LibVlcTrackEvidence(
            kind = LibVlcTrackKind.AUDIO,
            id = id,
            codec = codec,
            originalCodec = originalCodec,
            profile = profile.takeIf { it >= 0 },
            level = level.takeIf { it >= 0 },
            bitrate = bitrate.takeIf { it > 0 },
            language = language,
            channels = channels.takeIf { it > 0 },
            sampleRate = rate.takeIf { it > 0 }
        )

        else -> null
    }
}
