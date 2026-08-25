package com.iptv.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

private const val MAX_DIAGNOSTIC_TRACKS_PER_KIND = 4

enum class Media3TrackKind {
    VIDEO,
    AUDIO
}

data class Media3TrackEvidence(
    val kind: Media3TrackKind,
    val sampleMimeType: String?,
    val containerMimeType: String?,
    val codecs: String?,
    val language: String?,
    val width: Int? = null,
    val height: Int? = null,
    val frameRate: Float? = null,
    val channelCount: Int? = null,
    val sampleRate: Int? = null
)

data class Media3TrackSelectionEvidence(
    val videoTrackPresent: Boolean = false,
    val audioTrackPresent: Boolean = false,
    val selectedVideoTracks: List<Media3TrackEvidence> = emptyList(),
    val selectedAudioTracks: List<Media3TrackEvidence> = emptyList()
)

data class Media3CompatibilityEvidence(
    val videoDecoderName: String?,
    val audioDecoderName: String?,
    val videoTrackPresent: Boolean,
    val audioTrackPresent: Boolean,
    val selectedVideoTracks: List<Media3TrackEvidence>,
    val selectedAudioTracks: List<Media3TrackEvidence>
)

/**
 * Keeps observable Media3 compatibility state separate from the Player UI/session owner.
 *
 * Decoder names come from AnalyticsListener decoder-initialized callbacks. Track information comes
 * from Media3's selected track set. Media3 can mark more than one adaptive track as selected, so
 * this model intentionally records a selected set rather than claiming one exact currently-rendered
 * adaptive format.
 */
class Media3CompatibilityEvidenceTracker {
    private var videoDecoderName: String? = null
    private var audioDecoderName: String? = null
    private var trackSelection = Media3TrackSelectionEvidence()

    fun onVideoDecoderInitialized(decoderName: String) {
        videoDecoderName = decoderName.takeIf { it.isNotBlank() }
    }

    fun onAudioDecoderInitialized(decoderName: String) {
        audioDecoderName = decoderName.takeIf { it.isNotBlank() }
    }

    fun onTracksChanged(updatedTracks: Tracks) {
        onTrackSelectionChanged(updatedTracks.toCompatibilityTrackSelectionEvidence())
    }

    internal fun onTrackSelectionChanged(updatedSelection: Media3TrackSelectionEvidence) {
        trackSelection = updatedSelection
    }

    fun snapshot(): Media3CompatibilityEvidence = Media3CompatibilityEvidence(
        videoDecoderName = videoDecoderName,
        audioDecoderName = audioDecoderName,
        videoTrackPresent = trackSelection.videoTrackPresent,
        audioTrackPresent = trackSelection.audioTrackPresent,
        selectedVideoTracks = trackSelection.selectedVideoTracks,
        selectedAudioTracks = trackSelection.selectedAudioTracks
    )
}

fun Tracks.toCompatibilityTrackSelectionEvidence(): Media3TrackSelectionEvidence =
    Media3TrackSelectionEvidence(
        videoTrackPresent = containsType(C.TRACK_TYPE_VIDEO),
        audioTrackPresent = containsType(C.TRACK_TYPE_AUDIO),
        selectedVideoTracks = selectedMedia3TrackEvidence(this, Media3TrackKind.VIDEO),
        selectedAudioTracks = selectedMedia3TrackEvidence(this, Media3TrackKind.AUDIO)
    )

fun selectedMedia3TrackEvidence(
    tracks: Tracks,
    kind: Media3TrackKind
): List<Media3TrackEvidence> {
    val trackType = when (kind) {
        Media3TrackKind.VIDEO -> C.TRACK_TYPE_VIDEO
        Media3TrackKind.AUDIO -> C.TRACK_TYPE_AUDIO
    }

    return buildList {
        tracks.groups.forEach { group ->
            if (group.type != trackType) return@forEach
            repeat(group.length) { trackIndex ->
                if (group.isTrackSelected(trackIndex)) {
                    add(group.getTrackFormat(trackIndex).toCompatibilityEvidence(kind))
                }
            }
        }
    }
}

fun Media3CompatibilityEvidence.toDiagnosticMessage(): String = buildString {
    append("backend=MEDIA3")
    append(", video_decoder_observed=")
    append(videoDecoderName != null)
    append(", video_decoder=")
    append(videoDecoderName.toDiagnosticToken())
    append(", audio_decoder_observed=")
    append(audioDecoderName != null)
    append(", audio_decoder=")
    append(audioDecoderName.toDiagnosticToken())
    append(", video_track_present=")
    append(videoTrackPresent)
    append(", audio_track_present=")
    append(audioTrackPresent)
    appendSelectedTracks("video", selectedVideoTracks)
    appendSelectedTracks("audio", selectedAudioTracks)
}

private fun Format.toCompatibilityEvidence(kind: Media3TrackKind): Media3TrackEvidence =
    Media3TrackEvidence(
        kind = kind,
        sampleMimeType = sampleMimeType,
        containerMimeType = containerMimeType,
        codecs = codecs,
        language = language,
        width = width.takeIf { kind == Media3TrackKind.VIDEO && it > 0 },
        height = height.takeIf { kind == Media3TrackKind.VIDEO && it > 0 },
        frameRate = frameRate.takeIf { kind == Media3TrackKind.VIDEO && it > 0f },
        channelCount = channelCount.takeIf { kind == Media3TrackKind.AUDIO && it > 0 },
        sampleRate = sampleRate.takeIf { kind == Media3TrackKind.AUDIO && it > 0 }
    )

private fun StringBuilder.appendSelectedTracks(
    prefix: String,
    tracks: List<Media3TrackEvidence>
) {
    append(", ${prefix}_selected_count=")
    append(tracks.size)
    append(", ${prefix}_selected_reported=")
    append(tracks.size.coerceAtMost(MAX_DIAGNOSTIC_TRACKS_PER_KIND))

    tracks.take(MAX_DIAGNOSTIC_TRACKS_PER_KIND).forEachIndexed { index, track ->
        append(", ${prefix}${index}_mime=")
        append(track.sampleMimeType.toDiagnosticToken())
        append(", ${prefix}${index}_container=")
        append(track.containerMimeType.toDiagnosticToken())
        append(", ${prefix}${index}_codecs=")
        append(track.codecs.toDiagnosticToken())
        append(", ${prefix}${index}_language=")
        append(track.language.toDiagnosticToken())

        when (track.kind) {
            Media3TrackKind.VIDEO -> {
                append(", ${prefix}${index}_size=")
                append(
                    if (track.width != null && track.height != null) {
                        "${track.width}x${track.height}"
                    } else {
                        "none"
                    }
                )
                append(", ${prefix}${index}_fps=")
                append(track.frameRate ?: "none")
            }

            Media3TrackKind.AUDIO -> {
                append(", ${prefix}${index}_channels=")
                append(track.channelCount ?: "none")
                append(", ${prefix}${index}_sample_rate=")
                append(track.sampleRate ?: "none")
            }
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
