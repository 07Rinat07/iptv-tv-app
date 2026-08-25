package com.iptv.tv.core.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class Media3CompatibilityEvidenceTest {
    @Test
    fun `tracker keeps decoder names and truthful selected adaptive track set`() {
        val video1080 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setContainerMimeType(MimeTypes.VIDEO_MP4)
            .setCodecs("avc1.640028")
            .setWidth(1920)
            .setHeight(1080)
            .setFrameRate(25f)
            .build()
        val video720 = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.4d401f")
            .setWidth(1280)
            .setHeight(720)
            .setFrameRate(25f)
            .build()
        val audio = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
            .setCodecs("ec-3")
            .setLanguage("rus")
            .setChannelCount(6)
            .setSampleRate(48_000)
            .build()

        val tracks = Tracks(
            listOf(
                selectedGroup("video", arrayOf(video1080, video720), booleanArrayOf(true, true)),
                selectedGroup("audio", arrayOf(audio), booleanArrayOf(true))
            )
        )
        val tracker = Media3CompatibilityEvidenceTracker()

        tracker.onVideoDecoderInitialized("c2.android.avc.decoder")
        tracker.onAudioDecoderInitialized("c2.android.eac3.decoder")
        tracker.onTracksChanged(tracks)
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
    fun `selection ignores unselected tracks and the wrong track type`() {
        val video = Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setWidth(3840)
            .setHeight(2160)
            .build()
        val audio = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setChannelCount(2)
            .setSampleRate(44_100)
            .build()
        val tracks = Tracks(
            listOf(
                selectedGroup("video", arrayOf(video), booleanArrayOf(false)),
                selectedGroup("audio", arrayOf(audio), booleanArrayOf(true))
            )
        )
        val tracker = Media3CompatibilityEvidenceTracker().apply { onTracksChanged(tracks) }

        assertTrue(tracker.snapshot().videoTrackPresent)
        assertTrue(selectedMedia3TrackEvidence(tracks, Media3TrackKind.VIDEO).isEmpty())
        assertEquals(
            MimeTypes.AUDIO_AAC,
            selectedMedia3TrackEvidence(tracks, Media3TrackKind.AUDIO).single().sampleMimeType
        )
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

    private fun selectedGroup(
        id: String,
        formats: Array<Format>,
        selected: BooleanArray
    ): Tracks.Group = Tracks.Group(
        TrackGroup(id, *formats),
        false,
        IntArray(formats.size) { C.FORMAT_HANDLED },
        selected
    )
}
