package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpPlaybackResolverTest {
    @Test
    fun defaultTemplateResolvesFromExplicitMetadata() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://live.example/channel.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "default",
                days = 3,
                sourceTemplate = "https://archive.example/channel?start={start}&duration={duration}"
            ),
            programStartEpochMs = 1_700_000_000_000L,
            programEndEpochMs = 1_700_003_600_000L,
            nowEpochMs = 1_700_010_000_000L
        )

        assertTrue(result.supported)
        assertEquals(
            "https://archive.example/channel?start=1700000000&duration=3600",
            result.playbackUrl
        )
        assertNull(result.reason)
    }

    @Test
    fun missingMetadataNeverInfersArchiveFromLiveUrl() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://live.example/channel.m3u8?archive=1",
            metadata = null,
            programStartEpochMs = 1_700_000_000_000L,
            programEndEpochMs = 1_700_003_600_000L,
            nowEpochMs = 1_700_010_000_000L
        )

        assertFalse(result.supported)
        assertNull(result.playbackUrl)
        assertEquals("Archive metadata is not declared", result.reason)
    }

    @Test
    fun unfinishedProgrammeRemainsFailClosed() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://live.example/channel.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "shift",
                days = 2,
                sourceTemplate = null
            ),
            programStartEpochMs = 1_700_000_000_000L,
            programEndEpochMs = 1_700_003_600_000L,
            nowEpochMs = 1_700_003_000_000L
        )

        assertFalse(result.supported)
        assertNull(result.playbackUrl)
        assertEquals("Archive programme has not finished yet", result.reason)
    }

    @Test
    fun transportSuffixIsPreservedForResolvedArchiveUrl() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://live.example/channel.m3u8|User-Agent=TV",
            metadata = ChannelCatchUpMetadata(
                mode = "append",
                days = null,
                sourceTemplate = "?utc={utc}&duration={duration}"
            ),
            programStartEpochMs = 1_700_000_000_000L,
            programEndEpochMs = 1_700_000_600_000L,
            nowEpochMs = 1_700_010_000_000L
        )

        assertTrue(result.supported)
        assertEquals(
            "https://live.example/channel.m3u8?utc=1700000000&duration=600|User-Agent=TV",
            result.playbackUrl
        )
    }
}
