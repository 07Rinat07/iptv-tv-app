package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pRuntimeMetricsTest {
    @Test
    fun elapsedMillis_usesMonotonicNanosecondDelta() {
        assertEquals(1234L, elapsedMillis(1_000_000L, 1_235_000_000L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun elapsedMillis_rejectsBackwardsClockValues() {
        elapsedMillis(2L, 1L)
    }

    @Test
    fun metadataMetric_hasStableDiagnosticFields() {
        val line = P2pRuntimeMetric.MetadataReady(
            sourceType = "magnet",
            elapsedMillis = 4219L,
            fileCount = 3,
            pieceLengthBytes = 524_288
        ).toLogLine()

        assertEquals(
            "event=metadata_ready source=magnet elapsed_ms=4219 files=3 piece_length_bytes=524288",
            line
        )
    }

    @Test
    fun streamMetric_sanitizesFileNameAndIncludesNetworkSnapshot() {
        val line = P2pRuntimeMetric.StreamReady(
            sourceType = "torrent_url",
            elapsedMillis = 5100L,
            metadataMillis = 3200L,
            fileName = "TV channel.ts",
            fileSizeBytes = 20_000_000L,
            downloadRateBytesPerSecond = 1_500_000L,
            dhtNodes = 87L
        ).toLogLine()

        assertTrue(line.contains("event=stream_ready"))
        assertTrue(line.contains("metadata_ms=3200"))
        assertTrue(line.contains("download_bps=1500000"))
        assertTrue(line.contains("dht_nodes=87"))
        assertTrue(line.contains("file=TV_channel.ts"))
    }

    @Test
    fun firstByteMetric_reportsPlaybackStartupPosition() {
        assertEquals(
            "event=first_byte_ready source=infohash elapsed_ms=6800 position_bytes=0 bytes=65536",
            P2pRuntimeMetric.FirstByteReady(
                sourceType = "infohash",
                elapsedMillis = 6800L,
                positionBytes = 0L,
                byteCount = 65_536
            ).toLogLine()
        )
    }
}
