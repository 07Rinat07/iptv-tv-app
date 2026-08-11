package com.iptv.tv.core.playervlc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibVlcFallbackPolicyTest {
    @Test
    fun decoderFailureUsesFallback() {
        val decision = LibVlcFallbackPolicy.evaluate(
            "Звук воспроизводится, но видеодекодер не вывел изображение после перезапуска"
        )

        assertTrue(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.VIDEO_DECODER, decision.reason)
    }

    @Test
    fun parsingFailureUsesFallback() {
        val decision = LibVlcFallbackPolicy.evaluate("ERROR_CODE_PARSING_CONTAINER_MALFORMED")

        assertTrue(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.DEMUX_OR_CONTAINER, decision.reason)
    }

    @Test
    fun authorizationFailureDoesNotUseFallback() {
        val decision = LibVlcFallbackPolicy.evaluate("HTTP 403 Forbidden")

        assertFalse(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.NOT_ELIGIBLE, decision.reason)
    }

    @Test
    fun unknownHostDoesNotUseFallback() {
        val decision = LibVlcFallbackPolicy.evaluate("Unable to resolve host provider.invalid")

        assertFalse(decision.shouldFallback)
    }

    @Test
    fun networkSourceFailureDoesNotRestartTheSameStreamWithLibVlc() {
        val decision = LibVlcFallbackPolicy.evaluate(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Source error"
        )

        assertFalse(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.NOT_ELIGIBLE, decision.reason)
    }

    @Test
    fun unknownMedia3FailureGetsSingleFallbackChance() {
        val decision = LibVlcFallbackPolicy.evaluate("Unexpected Media3 playback failure")

        assertTrue(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.MEDIA3_PLAYBACK, decision.reason)
    }
}
