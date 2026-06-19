package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPlaylistParserTest {

    @Test
    fun parse_mediaPlaylist_resolvesSegmentsAndFlagsEndList() {
        val manifest = HlsPlaylistParser.parse(
            url = "https://cdn.example/live/index.m3u8",
            content = """
                #EXTM3U
                #EXT-X-TARGETDURATION:6
                #EXTINF:6.0,
                seg-01.ts
                #EXTINF:6.0,
                /segments/seg-02.ts
                #EXT-X-ENDLIST
            """.trimIndent()
        ) as HlsPlaylistParser.Manifest.Media

        assertEquals(2, manifest.segments.size)
        assertEquals("https://cdn.example/live/seg-01.ts", manifest.segments[0])
        assertEquals("https://cdn.example/segments/seg-02.ts", manifest.segments[1])
        assertEquals(6L, manifest.targetDurationSeconds)
        assertTrue(manifest.endList)
        assertFalse(manifest.encrypted)
        assertEquals(0, manifest.discontinuityCount)
    }

    @Test
    fun parse_masterPlaylist_selectsHighestBandwidthVariant() {
        val manifest = HlsPlaylistParser.parse(
            url = "https://cdn.example/master.m3u8",
            content = """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
                hi/index.m3u8
            """.trimIndent()
        ) as HlsPlaylistParser.Manifest.Master

        assertEquals(
            "https://cdn.example/hi/index.m3u8",
            HlsPlaylistParser.selectPreferredVariant(manifest)
        )
    }

    @Test
    fun parse_mediaPlaylist_marksEncryptedStreams() {
        val manifest = HlsPlaylistParser.parse(
            url = "https://cdn.example/live/index.m3u8",
            content = """
                #EXTM3U
                #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                #EXTINF:5.0,
                seg.ts
            """.trimIndent()
        ) as HlsPlaylistParser.Manifest.Media

        assertTrue(manifest.encrypted)
    }

    @Test
    fun parse_mediaPlaylist_countsDiscontinuityMarkers() {
        val manifest = HlsPlaylistParser.parse(
            url = "https://cdn.example/live/index.m3u8",
            content = """
                #EXTM3U
                #EXTINF:5.0,
                seg-01.ts
                #EXT-X-DISCONTINUITY
                #EXTINF:5.0,
                seg-02.ts
                #EXT-X-DISCONTINUITY
                #EXTINF:5.0,
                seg-03.ts
            """.trimIndent()
        ) as HlsPlaylistParser.Manifest.Media

        assertEquals(3, manifest.segments.size)
        assertEquals(2, manifest.discontinuityCount)
    }
}
