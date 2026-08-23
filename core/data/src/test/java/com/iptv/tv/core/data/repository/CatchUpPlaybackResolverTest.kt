package com.iptv.tv.core.data.repository

import com.iptv.tv.core.parser.ChannelCatchUpMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchUpPlaybackResolverTest {
    private val now = 1_800_000_000_000L
    private val start = now - 3_600_000L
    private val end = now - 1_800_000L

    @Test
    fun appendRendersExplicitTemplateAndPreservesTransportSuffix() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8?token=abc|User-Agent=RinatTV",
            metadata = ChannelCatchUpMetadata(
                mode = "append",
                days = 7,
                sourceTemplate = "&utc=${'$'}{start}&duration=${'$'}{duration}"
            ),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertTrue(result.supported)
        assertEquals(
            "https://tv.example/live.m3u8?token=abc&utc=1799996400&duration=1800|User-Agent=RinatTV",
            result.playbackUrl
        )
        assertNull(result.reason)
    }

    @Test
    fun appendUsesCurrentTimestampAndPlacesTemplateBeforeFragment() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8#variant",
            metadata = ChannelCatchUpMetadata(
                mode = "append",
                days = 7,
                sourceTemplate = "?utc=${'$'}{start}&lutc=${'$'}{timestamp}"
            ),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertTrue(result.supported)
        assertEquals(
            "https://tv.example/live.m3u8?utc=1799996400&lutc=1800000000#variant",
            result.playbackUrl
        )
    }

    @Test
    fun shiftBuildsUtcLutcQueryWithoutProviderTemplate() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8?token=abc",
            metadata = ChannelCatchUpMetadata(mode = "shift", days = 2, sourceTemplate = null),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertTrue(result.supported)
        assertEquals(
            "https://tv.example/live.m3u8?token=abc&utc=1799996400&lutc=1800000000",
            result.playbackUrl
        )
    }

    @Test
    fun shiftPlacesArchiveQueryBeforeFragment() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8#variant",
            metadata = ChannelCatchUpMetadata(mode = "shift", days = 2, sourceTemplate = null),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertTrue(result.supported)
        assertEquals(
            "https://tv.example/live.m3u8?utc=1799996400&lutc=1800000000#variant",
            result.playbackUrl
        )
    }

    @Test
    fun defaultRequiresAbsoluteRenderedSource() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "default",
                days = 7,
                sourceTemplate = "https://archive.example/replay?start={utc}&end={end}"
            ),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertTrue(result.supported)
        assertEquals(
            "https://archive.example/replay?start=1799996400&end=1799998200",
            result.playbackUrl
        )
    }

    @Test
    fun liveOnlyChannelDoesNotGainArchiveCapability() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = null,
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertFalse(result.supported)
        assertNull(result.playbackUrl)
    }

    @Test
    fun invalidDeclaredDaysFailsClosedInsteadOfBecomingUnlimited() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "append",
                days = null,
                sourceTemplate = "?utc=${'$'}{start}",
                daysDeclared = true
            ),
            programStartEpochMs = now - 30L * 24L * 60L * 60L * 1_000L,
            programEndEpochMs = now - 29L * 24L * 60L * 60L * 1_000L,
            nowEpochMs = now
        )

        assertFalse(result.supported)
        assertNull(result.playbackUrl)
    }

    @Test
    fun programmeOutsideDeclaredDaysFailsClosed() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "append",
                days = 1,
                sourceTemplate = "?utc=${'$'}{start}"
            ),
            programStartEpochMs = now - 2L * 24L * 60L * 60L * 1_000L,
            programEndEpochMs = now - 47L * 60L * 60L * 1_000L,
            nowEpochMs = now
        )

        assertFalse(result.supported)
        assertNull(result.playbackUrl)
    }

    @Test
    fun unfinishedProgrammeFailsClosed() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null
            ),
            programStartEpochMs = now - 1_000L,
            programEndEpochMs = now + 60_000L,
            nowEpochMs = now
        )

        assertFalse(result.supported)
        assertNull(result.playbackUrl)
    }

    @Test
    fun unsupportedModeAndPlaceholderFailClosed() {
        val unsupportedMode = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(mode = "flussonic", days = 7, sourceTemplate = null),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )
        val unsupportedPlaceholder = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "append",
                days = 7,
                sourceTemplate = "?id=${'$'}{catchup-id}"
            ),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )
        val unterminatedPlaceholder = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "append",
                days = 7,
                sourceTemplate = "?start=${'$'}{start"
            ),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertFalse(unsupportedMode.supported)
        assertFalse(unsupportedPlaceholder.supported)
        assertFalse(unterminatedPlaceholder.supported)
        assertNull(unsupportedMode.playbackUrl)
        assertNull(unsupportedPlaceholder.playbackUrl)
        assertNull(unterminatedPlaceholder.playbackUrl)
    }

    @Test
    fun relativeDefaultTemplateFailsClosedInsteadOfGuessing() {
        val result = CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = "https://tv.example/live.m3u8",
            metadata = ChannelCatchUpMetadata(
                mode = "default",
                days = 7,
                sourceTemplate = "?utc=${'$'}{start}"
            ),
            programStartEpochMs = start,
            programEndEpochMs = end,
            nowEpochMs = now
        )

        assertFalse(result.supported)
        assertNull(result.playbackUrl)
    }
}
