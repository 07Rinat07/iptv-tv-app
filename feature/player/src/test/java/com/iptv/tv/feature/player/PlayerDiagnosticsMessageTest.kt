package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlayerDiagnosticsMessageTest {

    @Test
    fun `oversized playlist EPG error is shared across channels`() {
        val message =
            "Unable to load EPG: IOException: EPG input exceeds the 67108864 byte safety limit " +
                "(reported=83760807)"

        assertEquals(
            epgErrorSignature(playlistId = 1L, channelId = 10L, message = message),
            epgErrorSignature(playlistId = 1L, channelId = 99L, message = message)
        )
        assertEquals(15L * 60_000L, epgSourceRetryBackoffMillis(message))
    }

    @Test
    fun `ordinary EPG errors remain immediately retryable`() {
        assertEquals(
            0L,
            epgSourceRetryBackoffMillis("Unable to load EPG: temporary HTTP 503")
        )
    }

    @Test
    fun `same EPG failure remains isolated between playlists`() {
        val message = "Unable to load EPG: parser rejected malformed XMLTV"

        assertFalse(
            epgErrorSignature(playlistId = 1L, channelId = 10L, message = message) ==
                epgErrorSignature(playlistId = 2L, channelId = 10L, message = message)
        )
    }

    @Test
    fun `absolute Ace Live timeout has concise Russian UI message`() {
        val raw =
            "The direct Ace Live swarm failed and transport metadata was unavailable: " +
                "direct=Timed out waiting for 60000 ms; metadata=Ace transport metadata was unavailable"

        assertEquals(
            "Torrent TV: источник не ответил за 60 секунд. " +
                "Возможно, content ID устарел или канал сейчас не раздаётся.",
            conciseP2pResolveError(raw)
        )
    }

    @Test
    fun `no connected peer timeout does not expose implementation details`() {
        val message = conciseP2pResolveError(
            "direct=Ace Live did not connect to any peer within 30000 ms"
        )

        assertEquals(
            "Torrent TV: доступный пир не найден за отведённое время. " +
                "Возможно, канал сейчас не раздаётся.",
            message
        )
        assertFalse(message.contains("30000"))
    }

    @Test
    fun `qualified peer without media has an honest concise message`() {
        val message = conciseP2pResolveError(
            "direct_retry=failure=qualified_peer_no_media; " +
                "Direct Ace Live fallback made peer connection progress but did not " +
                "produce media before the bounded qualification grace expired; " +
                "metadata_startup=Ace Live did not connect to any peer within 30000 ms"
        )

        assertEquals(
            "Torrent TV: пир был найден, но данные потока не поступили " +
                "за отведённое время. Попробуйте повторить запуск позже.",
            message
        )
        assertFalse(message.contains("30000"))
    }
}
