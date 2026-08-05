package com.iptv.tv.core.playervlc

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

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
    private var attachedLayout: VLCVideoLayout? = null
    private var released = false

    init {
        mediaPlayer.setEventListener(
            MediaPlayer.EventListener { event ->
                when (event?.type) {
                    MediaPlayer.Event.Playing -> listener.onReady()
                    MediaPlayer.Event.EndReached -> listener.onEnded()
                    MediaPlayer.Event.EncounteredError -> listener.onError("LibVLC не смог открыть или декодировать поток")
                }
            }
        )
    }

    fun attach(layout: VLCVideoLayout) {
        check(!released) { "LibVLC controller is released" }
        if (attachedLayout === layout) return
        detach()
        mediaPlayer.attachViews(layout, null, false, false)
        attachedLayout = layout
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
        if (!mediaPlayer.play()) {
            listener.onError("LibVLC отклонил запуск потока")
        }
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
        if (attachedLayout == null || released) return
        runCatching { mediaPlayer.detachViews() }
        attachedLayout = null
    }

    fun release() {
        if (released) return
        released = true
        runCatching { mediaPlayer.stop() }
        runCatching { mediaPlayer.detachViews() }
        attachedLayout = null
        runCatching { mediaPlayer.release() }
        runCatching { libVlc.release() }
    }
}
