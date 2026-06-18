package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.DownloadSourceType
import com.iptv.tv.core.model.RecordingStorageInfo
import com.iptv.tv.core.model.RecordingStorageLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeshiftBufferPlannerTest {

    @Test
    fun plan_supportsHlsWhenStorageIsWritable() {
        val plan = TimeshiftBufferPlanner.plan(
            channelId = 10,
            channelName = "News",
            rawStreamUrl = "https://example.com/live/news.m3u8|User-Agent=tv",
            storageInfo = storageInfo(freeBytes = 8L * 1024L * 1024L * 1024L),
            requestedMinutes = 60
        )

        assertTrue(plan.supported)
        assertNull(plan.reason)
        assertEquals(DownloadSourceType.HLS_PLAYLIST, plan.sourceType)
        assertEquals(60, plan.requestedDurationMinutes)
        assertEquals(60, plan.maxDurationMinutes)
    }

    @Test
    fun plan_blocksTorrentLikeSources() {
        val plan = TimeshiftBufferPlanner.plan(
            channelId = 11,
            channelName = "Sports",
            rawStreamUrl = "acestream://abc123",
            storageInfo = storageInfo(freeBytes = 8L * 1024L * 1024L * 1024L),
            requestedMinutes = 60
        )

        assertFalse(plan.supported)
        assertEquals(DownloadSourceType.ACESTREAM, plan.sourceType)
        assertEquals(0L, plan.estimatedBytes)
        assertEquals(0, plan.maxDurationMinutes)
    }

    @Test
    fun plan_blocksWhenStorageCannotFitRequestedBuffer() {
        val plan = TimeshiftBufferPlanner.plan(
            channelId = 12,
            channelName = "Movie",
            rawStreamUrl = "https://example.com/live/movie.ts",
            storageInfo = storageInfo(freeBytes = 300L * 1024L * 1024L),
            requestedMinutes = 60
        )

        assertFalse(plan.supported)
        assertEquals(DownloadSourceType.HTTP_STREAM, plan.sourceType)
        assertTrue(plan.maxDurationMinutes < plan.requestedDurationMinutes)
        assertTrue(plan.reason.orEmpty().contains("Недостаточно места"))
    }

    @Test
    fun plan_acceptsUnknownFreeSpaceForConfiguredSafFolder() {
        val plan = TimeshiftBufferPlanner.plan(
            channelId = 13,
            channelName = "Documentary",
            rawStreamUrl = "https://example.com/live/doc.m3u8",
            storageInfo = storageInfo(
                location = RecordingStorageLocation.CUSTOM_EXTERNAL,
                freeBytes = -1L,
                path = "SAF folder"
            ),
            requestedMinutes = 120
        )

        assertTrue(plan.supported)
        assertEquals(120, plan.maxDurationMinutes)
        assertEquals(-1L, plan.availableBytes)
    }

    private fun storageInfo(
        location: RecordingStorageLocation = RecordingStorageLocation.INTERNAL,
        freeBytes: Long,
        path: String = "/recordings",
        exists: Boolean = true,
        writable: Boolean = true,
        configured: Boolean = true
    ): RecordingStorageInfo {
        return RecordingStorageInfo(
            location = location,
            path = path,
            exists = exists,
            writable = writable,
            freeBytes = freeBytes,
            usingFallback = false,
            configured = configured
        )
    }
}
