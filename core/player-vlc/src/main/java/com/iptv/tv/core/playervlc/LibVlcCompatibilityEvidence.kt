package com.iptv.tv.core.playervlc

enum class LibVlcTrackKind {
    VIDEO,
    AUDIO
}

data class LibVlcTrackEvidence(
    val kind: LibVlcTrackKind,
    val id: Int,
    val codec: String?,
    val originalCodec: String?,
    val profile: Int?,
    val level: Int?,
    val bitrate: Int?,
    val language: String?,
    val width: Int? = null,
    val height: Int? = null,
    val frameRateNumerator: Int? = null,
    val frameRateDenominator: Int? = null,
    val channels: Int? = null,
    val sampleRate: Int? = null
)

data class LibVlcCompatibilityEvidence(
    val hardwareDecodingPreferred: Boolean,
    val selectedVideoTrack: LibVlcTrackEvidence?,
    val selectedAudioTrack: LibVlcTrackEvidence?
)

/**
 * LibVLC exposes media-track metadata here, but not the concrete decoder module chosen for this
 * playback. Keep that distinction explicit so field evidence never turns a hardware preference
 * into an observed hardware-decoder claim.
 */
fun LibVlcCompatibilityEvidence.toDiagnosticMessage(): String = buildString {
    append("backend=LIBVLC")
    append(", actual_decoder_observed=false")
    append(", hw_decode_preferred=")
    append(hardwareDecodingPreferred)
    appendTrack("video", selectedVideoTrack)
    appendTrack("audio", selectedAudioTrack)
}

internal fun selectedLibVlcTrackEvidence(
    selectedTrackId: Int,
    kind: LibVlcTrackKind,
    tracks: List<LibVlcTrackEvidence>
): LibVlcTrackEvidence? {
    if (selectedTrackId < 0) return null
    return tracks.firstOrNull { track -> track.kind == kind && track.id == selectedTrackId }
}

private fun StringBuilder.appendTrack(prefix: String, track: LibVlcTrackEvidence?) {
    append(", ${prefix}_selected=")
    append(track != null)
    if (track == null) return

    append(", ${prefix}_id=")
    append(track.id)
    append(", ${prefix}_codec=")
    append(track.codec.toDiagnosticToken())
    append(", ${prefix}_original_codec=")
    append(track.originalCodec.toDiagnosticToken())
    append(", ${prefix}_profile=")
    append(track.profile ?: "none")
    append(", ${prefix}_level=")
    append(track.level ?: "none")
    append(", ${prefix}_bitrate=")
    append(track.bitrate ?: "none")
    append(", ${prefix}_language=")
    append(track.language.toDiagnosticToken())

    when (track.kind) {
        LibVlcTrackKind.VIDEO -> {
            append(", video_size=")
            append(
                if (track.width != null && track.height != null) {
                    "${track.width}x${track.height}"
                } else {
                    "none"
                }
            )
            append(", video_fps=")
            append(
                if (track.frameRateNumerator != null && track.frameRateDenominator != null) {
                    "${track.frameRateNumerator}/${track.frameRateDenominator}"
                } else {
                    "none"
                }
            )
        }

        LibVlcTrackKind.AUDIO -> {
            append(", audio_channels=")
            append(track.channels ?: "none")
            append(", audio_sample_rate=")
            append(track.sampleRate ?: "none")
        }
    }
}

private fun String?.toDiagnosticToken(): String {
    if (isNullOrBlank()) return "none"
    return trim()
        .take(64)
        .map { character ->
            when {
                character.isLetterOrDigit() -> character
                character in setOf('.', '_', '+', '-') -> character
                else -> '_'
            }
        }
        .joinToString(separator = "")
        .ifBlank { "none" }
}
