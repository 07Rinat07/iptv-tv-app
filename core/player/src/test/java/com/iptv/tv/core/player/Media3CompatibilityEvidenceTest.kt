package com.iptv.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3CompatibilityEvidenceTest {
    @Test
    fun `tracker keeps decoder names and truthful selected adaptive track set`() {
        val tracker = Media3CompatibilityEvidenceTracker()

        tracker.onVideoDecoderInitialized("c2.android.avc.decoder")
        tracker.onAudioDecoderInitialized("c2.android.eac3.decoder")
        tracker.onTrackSelectionChanged(
            Media3TrackSelectionEvidence(
                videoTrackPresent = true,
                audioTrackPresent = true,
                selectedVideoTracks = listOf(
                    Media3TrackEvidence(
                        kind = Media3TrackKind.VIDEO,
                        sampleMimeType = "video/avc",
                        containerMimeType = "video/mp4",
                        codecs = "avc1.640028",
                        language = null,
                        width = 1920,
                        height = 1080,
                        frameRate = 25f
                    ),
                    Media3TrackEvidence(
                        kind = Media3TrackKind.VIDEO,
                        sampleMimeType = "video/avc",
                        containerMimeType = null,
                        codecs = "avc1.4d401f",
                        language = null,
                        width = 1280,
                        height = 720,
                        frameRate = 25f
                    )
                ),
                selectedAudioTracks = listOf(
                    Media3TrackEvidence(
                        kind = Media3TrackKind.AUDIO,
                        sampleMimeType = "audio/eac3",
                        containerMimeType = null,
                        codecs = "ec-3",
                        language = "rus",
                        channelCount = 6,
                        sampleRate = 48_000
                    )
                )
            )
        )
        val evidence = tracker.snapshot()

        assertEquals("c2.android.avc.decoder", evidence.videoDecoderName)
        assertEquals("c2.android.eac3.decoder", evidence.audioDecoderName)
        assertTrue(evidence.videoTrackPresent)
        assertTrue(evidence.audioTrackPresent)
        assertEquals(2, evidence.selectedVideoTracks.size)
        assertEquals(listOf(1920, 1280), evidence.selectedVideoTracks.mapNotNull { it.width })
        assertEquals(1, evidence.selectedAudioTracks.size)
        assertEquals(6, evidence.selectedAudioTracks.single().channelCount)
    }

    @Test
    fun `track presence stays distinct from current selection`() {
        val tracker = Media3CompatibilityEvidenceTracker()
        tracker.onTrackSelectionChanged(
            Media3TrackSelectionEvidence(
                videoTrackPresent = true,
                audioTrackPresent = true,
                selectedVideoTracks = emptyList(),
                selectedAudioTracks = listOf(
                    Media3TrackEvidence(
                        kind = Media3TrackKind.AUDIO,
                        sampleMimeType = "audio/mp4a-latm",
                        containerMimeType = null,
                        codecs = "mp4a.40.2",
                        language = null,
                        channelCount = 2,
                        sampleRate = 44_100
                    )
                )
            )
        )

        val evidence = tracker.snapshot()
        assertTrue(evidence.videoTrackPresent)
        assertTrue(evidence.audioTrackPresent)
        assertTrue(evidence.selectedVideoTracks.isEmpty())
        assertEquals("audio/mp4a-latm", evidence.selectedAudioTracks.single().sampleMimeType)
    }

    @Test
    fun `diagnostic output is bounded sanitized and does not invent decoder implementation class`() {
        val tracks = (0 until 6).map { index ->
            Media3TrackEvidence(
                kind = Media3TrackKind.AUDIO,
                sampleMimeType = "audio/eac3\nstream_url=https://secret.invalid/$index",
                containerMimeType = "video/mp2t,Referer:credential",
                codecs = "ec-3",
                language = "ru RU",
                channelCount = 6,
                sampleRate = 48_000
            )
        }
        val evidence = Media3CompatibilityEvidence(
            videoDecoderName = "c2.vendor.avc.decoder\nsecret=value",
            audioDecoderName = null,
            videoTrackPresent = false,
            audioTrackPresent = true,
            selectedVideoTracks = emptyList(),
            selectedAudioTracks = tracks
        )

        val message = evidence.toDiagnosticMessage()

        assertTrue(message.contains("backend=MEDIA3"))
        assertTrue(message.contains("video_decoder_observed=true"))
        assertTrue(message.contains("audio_decoder_observed=false"))
        assertTrue(message.contains("video_track_present=false"))
        assertTrue(message.contains("audio_track_present=true"))
        assertTrue(message.contains("audio_selected_count=6"))
        assertTrue(message.contains("audio_selected_reported=4"))
        assertFalse(message.contains('\n'))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("Referer:"))
        assertFalse(message.contains("hardware_decoder="))
        assertFalse(message.contains("software_decoder="))
        assertFalse(message.contains("audio4_mime="))
    }
}
