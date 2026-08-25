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
    fun media3IoErrorsFailClosedBeforeCodecMarkers() {
        val decision = LibVlcFallbackPolicy.evaluate(
            "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: source codec metadata unavailable"
        )

        assertFalse(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.NOT_ELIGIBLE, decision.reason)
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
    fun explicitMedia3BackendFailureUsesFallback() {
        val decision = LibVlcFallbackPolicy.evaluate("ERROR_CODE_AUDIO_TRACK_INIT_FAILED")

        assertTrue(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.MEDIA3_PLAYBACK, decision.reason)
    }

    @Test
    fun media3InitializationFailureUsesFallback() {
        val decision = LibVlcFallbackPolicy.evaluate("Не удалось создать Media3: decoder service unavailable")

        assertTrue(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.MEDIA3_PLAYBACK, decision.reason)
    }

    @Test
    fun unknownMedia3FailureDoesNotSpeculativelyRestartWithLibVlc() {
        val decision = LibVlcFallbackPolicy.evaluate("Unexpected Media3 playback failure")

        assertFalse(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.NOT_ELIGIBLE, decision.reason)
    }

    @Test
    fun blankMedia3FailureDoesNotSpeculativelyRestartWithLibVlc() {
        val decision = LibVlcFallbackPolicy.evaluate("   ")

        assertFalse(decision.shouldFallback)
        assertEquals(LibVlcFallbackReason.NOT_ELIGIBLE, decision.reason)
    }
}
