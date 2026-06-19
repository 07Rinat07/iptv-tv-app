package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.DownloadSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStoragePreflightTest {

    @Test
    fun evaluate_allowsWhenAvailableStorageIsUnknown() {
        val result = DownloadStoragePreflight { null }.evaluate(
            source = "https://example.com/movie.ts?size=100gb",
            sourceType = DownloadSourceType.HTTP_STREAM
        )

        assertTrue(result.allowed)
        assertNull(result.availableBytes)
    }

    @Test
    fun evaluate_usesExplicitSizeHintBeforeFallbackEstimate() {
        val result = DownloadStoragePreflight { 2L * GIB }.evaluate(
            source = "https://example.com/movie.ts?size=700mb",
            sourceType = DownloadSourceType.HTTP_STREAM
        )

        assertTrue(result.allowed)
        assertEquals(700L * MIB, result.estimatedBytes)
    }

    @Test
    fun evaluate_blocksWhenAvailableStorageCannotFitEstimateAndReserve() {
        val result = DownloadStoragePreflight { 1L * GIB }.evaluate(
            source = "magnet:?xt=urn:btih:abcdef",
            sourceType = DownloadSourceType.MAGNET
        )

        assertFalse(result.allowed)
        assertEquals(2L * GIB, result.estimatedBytes)
        assertEquals(256L * MIB, result.reserveBytes)
    }

    @Test
    fun evaluate_doesNotRequireSpaceForLocalFileSource() {
        val result = DownloadStoragePreflight { 0L }.evaluate(
            source = "/sdcard/Download/movie.mp4",
            sourceType = DownloadSourceType.LOCAL_FILE
        )

        assertTrue(result.allowed)
        assertEquals(0L, result.estimatedBytes)
    }

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * KIB
        const val GIB = 1024L * MIB
    }
}
