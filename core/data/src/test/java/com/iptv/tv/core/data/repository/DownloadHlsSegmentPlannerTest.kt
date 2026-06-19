package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadHlsSegmentPlannerTest {

    @Test
    fun plan_mediaPlaylist_returnsResolvedSegmentUrls() {
        val plan = DownloadHlsSegmentPlanner.plan("https://cdn.example/live/index.m3u8") {
            """
                #EXTM3U
                #EXTINF:6,
                seg-01.ts
                #EXT-X-DISCONTINUITY
                #EXTINF:6,
                /global/seg-02.ts
                #EXT-X-ENDLIST
            """.trimIndent()
        }

        assertEquals("https://cdn.example/live/index.m3u8", plan.mediaPlaylistUrl)
        assertEquals(
            listOf(
                "https://cdn.example/live/seg-01.ts",
                "https://cdn.example/global/seg-02.ts"
            ),
            plan.segmentUrls
        )
        assertEquals(1, plan.discontinuityCount)
    }

    @Test
    fun plan_masterPlaylist_usesHighestBandwidthVariant() {
        val responses = mapOf(
            "https://cdn.example/master.m3u8" to """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=800000
                low/index.m3u8
                #EXT-X-STREAM-INF:BANDWIDTH=2500000
                hi/index.m3u8
            """.trimIndent(),
            "https://cdn.example/hi/index.m3u8" to """
                #EXTM3U
                #EXTINF:5,
                hi-01.ts
            """.trimIndent()
        )

        val plan = DownloadHlsSegmentPlanner.plan("https://cdn.example/master.m3u8") { url ->
            responses.getValue(url)
        }

        assertEquals("https://cdn.example/hi/index.m3u8", plan.mediaPlaylistUrl)
        assertEquals(listOf("https://cdn.example/hi/hi-01.ts"), plan.segmentUrls)
        assertEquals(0, plan.discontinuityCount)
    }

    @Test
    fun plan_encryptedMediaPlaylist_failsWithClearReason() {
        val result = runCatching {
            DownloadHlsSegmentPlanner.plan("https://cdn.example/live/index.m3u8") {
                """
                    #EXTM3U
                    #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
                    #EXTINF:5,
                    seg.ts
                """.trimIndent()
            }
        }

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()!!
        assertTrue(error.message.orEmpty().contains("Encrypted HLS"))
    }
}
