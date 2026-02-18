package com.iptv.tv.core.player

import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.PlayerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerContractsTest {

    @Test
    fun manualBufferProfile_usesProvidedValues() {
        val config = bufferConfigForProfile(
            profile = BufferProfile.MANUAL,
            manual = ManualBufferSettings(
                startMs = 15_000,
                rebufferMs = 3_000,
                maxMs = 60_000
            )
        )

        assertEquals(15_000, config.minBufferMs)
        assertEquals(60_000, config.maxBufferMs)
        assertEquals(15_000, config.bufferForPlaybackMs)
        assertEquals(3_000, config.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun manualBufferProfile_normalizesInvalidBounds() {
        val config = bufferConfigForProfile(
            profile = BufferProfile.MANUAL,
            manual = ManualBufferSettings(
                startMs = 90_000,
                rebufferMs = 100_000,
                maxMs = 10_000
            )
        )

        assertEquals(90_000, config.minBufferMs)
        assertEquals(90_000, config.maxBufferMs)
        assertEquals(90_000, config.bufferForPlaybackAfterRebufferMs)
    }

    @Test
    fun externalPlayerCheck_worksForVlc() {
        assertTrue(isExternalPlayer(PlayerType.VLC))
        assertFalse(isExternalPlayer(PlayerType.INTERNAL))
    }
}

