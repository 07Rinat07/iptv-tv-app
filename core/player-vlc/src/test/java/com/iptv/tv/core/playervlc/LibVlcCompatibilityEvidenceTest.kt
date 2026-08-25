package com.iptv.tv.core.playervlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibVlcCompatibilityEvidenceTest {
    @Test
    fun `selected evidence requires exact current track id and kind`() {
        val tracks = listOf(
            LibVlcTrackEvidence(
                kind = LibVlcTrackKind.VIDEO,
                id = 4,
                codec = "h264",
                originalCodec = "avc1",
                profile = 100,
                level = 41,
                bitrate = 4_000_000,
                language = null
            ),
            LibVlcTrackEvidence(
                kind = LibVlcTrackKind.AUDIO,
                id = 7,
                codec = "eac3",
                originalCodec = "ec-3",
                profile = null,
                level = null,
                bitrate = 384_000,
                language = "eng"
            )
        )

        assertEquals(
            4,
            selectedLibVlcTrackEvidence(4, LibVlcTrackKind.VIDEO, tracks)?.id
        )
        assertEquals(
            7,
            selectedLibVlcTrackEvidence(7, LibVlcTrackKind.AUDIO, tracks)?.id
        )
        assertNull(selectedLibVlcTrackEvidence(7, LibVlcTrackKind.VIDEO, tracks))
        assertNull(selectedLibVlcTrackEvidence(-1, LibVlcTrackKind.AUDIO, tracks))
    }

    @Test
    fun `diagnostic evidence distinguishes hardware preference from observed decoder`() {
        val evidence = LibVlcCompatibilityEvidence(
            hardwareDecodingPreferred = true,
            selectedVideoTrack = LibVlcTrackEvidence(
                kind = LibVlcTrackKind.VIDEO,
                id = 2,
                codec = "h264",
                originalCodec = "avc1",
                profile = 100,
                level = 41,
                bitrate = 5_000_000,
                language = null,
                width = 1920,
                height = 1080,
                frameRateNumerator = 25,
                frameRateDenominator = 1
            ),
            selectedAudioTrack = LibVlcTrackEvidence(
                kind = LibVlcTrackKind.AUDIO,
                id = 3,
                codec = "eac3",
                originalCodec = "ec-3",
                profile = null,
                level = null,
                bitrate = 384_000,
                language = "rus",
                channels = 6,
                sampleRate = 48_000
            )
        )

        val message = evidence.toDiagnosticMessage()

        assertTrue(message.contains("backend=LIBVLC"))
        assertTrue(message.contains("actual_decoder_observed=false"))
        assertTrue(message.contains("hw_decode_preferred=true"))
        assertTrue(message.contains("video_codec=h264"))
        assertTrue(message.contains("video_profile=100"))
        assertTrue(message.contains("video_level=41"))
        assertTrue(message.contains("video_size=1920x1080"))
        assertTrue(message.contains("audio_codec=eac3"))
        assertTrue(message.contains("audio_channels=6"))
        assertTrue(message.contains("audio_sample_rate=48000"))
        assertFalse(message.contains("actual_decoder_observed=true"))
    }

    @Test
    fun `diagnostic tokens cannot inject multiline or structured log fields`() {
        val evidence = LibVlcCompatibilityEvidence(
            hardwareDecodingPreferred = false,
            selectedVideoTrack = LibVlcTrackEvidence(
                kind = LibVlcTrackKind.VIDEO,
                id = 1,
                codec = "h264\nstream_url=https://secret.invalid",
                originalCodec = "avc1,Referer: credential",
                profile = null,
                level = null,
                bitrate = null,
                language = "ru RU"
            ),
            selectedAudioTrack = null
        )

        val message = evidence.toDiagnosticMessage()

        assertFalse(message.contains('\n'))
        assertFalse(message.contains("https://"))
        assertFalse(message.contains("Referer:"))
        assertTrue(message.contains("video_codec=h264_stream_url_https___secret.invalid"))
        assertTrue(message.contains("audio_selected=false"))
    }
}
