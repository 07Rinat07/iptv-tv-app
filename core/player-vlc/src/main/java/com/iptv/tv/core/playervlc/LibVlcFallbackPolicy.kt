package com.iptv.tv.core.playervlc

import java.util.Locale

enum class LibVlcFallbackReason {
    VIDEO_DECODER,
    UNSUPPORTED_FORMAT,
    DEMUX_OR_CONTAINER,
    MEDIA3_PLAYBACK,
    NOT_ELIGIBLE
}

data class LibVlcFallbackDecision(
    val shouldFallback: Boolean,
    val reason: LibVlcFallbackReason,
    val diagnostic: String
)

/**
 * Ограничивает автоматический переход на LibVLC случаями, где другой декодер, demuxer
 * или playback backend действительно способен помочь. Ошибки адреса, авторизации, сети
 * и неизвестные ошибки не передаются второму движку, чтобы не выполнять бесполезный
 * повторный запуск того же источника.
 */
object LibVlcFallbackPolicy {
    private val nonFallbackMarkers = listOf(
        "401",
        "403",
        "404",
        "410",
        "unknown host",
        "unable to resolve host",
        "connection refused",
        "connectexception",
        "error_code_io_",
        "io_network_connection_failed",
        "network connection failed",
        "source error",
        "источник потока недоступен",
        "sockettimeout",
        "timed out",
        "cleartext communication",
        "malformed url",
        "unsupported scheme",
        "ace stream engine",
        "engine недоступен"
    )

    private val backendRuntimeMarkers = listOf(
        "error_code_audio_track_init_failed",
        "error_code_audio_track_write_failed",
        "error_code_failed_runtime_check",
        "не удалось создать media3",
        "failed to create media3"
    )

    private val decoderMarkers = listOf(
        "decoder",
        "декодер",
        "codec",
        "кодек",
        "video track",
        "видеодорож",
        "first frame",
        "первый кадр",
        "звук воспроизводится",
        "audio without picture"
    )

    private val unsupportedMarkers = listOf(
        "unsupported",
        "не поддерж",
        "format exceeds capabilities",
        "error_code_decoding",
        "error_code_video_frame_processing"
    )

    private val demuxMarkers = listOf(
        "demux",
        "container",
        "контейнер",
        "mpeg-ts",
        "mpegts",
        "parser",
        "extractor",
        "unrecognized input format",
        "error_code_parsing"
    )

    fun evaluate(message: String): LibVlcFallbackDecision {
        val normalized = message.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) {
            return notEligible("Media3 завершился без диагностического текста")
        }
        if (nonFallbackMarkers.any(normalized::contains)) {
            return notEligible("Ошибка относится к сети, источнику, адресу или авторизации")
        }
        if (backendRuntimeMarkers.any(normalized::contains)) {
            return LibVlcFallbackDecision(
                shouldFallback = true,
                reason = LibVlcFallbackReason.MEDIA3_PLAYBACK,
                diagnostic = "Сбой локального Media3 playback backend допускает один запуск LibVLC"
            )
        }
        if (decoderMarkers.any(normalized::contains)) {
            return LibVlcFallbackDecision(
                shouldFallback = true,
                reason = LibVlcFallbackReason.VIDEO_DECODER,
                diagnostic = "Media3 не смог декодировать или вывести видеодорожку"
            )
        }
        if (unsupportedMarkers.any(normalized::contains)) {
            return LibVlcFallbackDecision(
                shouldFallback = true,
                reason = LibVlcFallbackReason.UNSUPPORTED_FORMAT,
                diagnostic = "Формат, профиль или video processing не поддержан Media3"
            )
        }
        if (demuxMarkers.any(normalized::contains)) {
            return LibVlcFallbackDecision(
                shouldFallback = true,
                reason = LibVlcFallbackReason.DEMUX_OR_CONTAINER,
                diagnostic = "Media3 не разобрал контейнер или транспортный поток"
            )
        }
        return notEligible("Ошибка Media3 не классифицирована как безопасная для fallback")
    }

    private fun notEligible(diagnostic: String): LibVlcFallbackDecision = LibVlcFallbackDecision(
        shouldFallback = false,
        reason = LibVlcFallbackReason.NOT_ELIGIBLE,
        diagnostic = diagnostic
    )
}
