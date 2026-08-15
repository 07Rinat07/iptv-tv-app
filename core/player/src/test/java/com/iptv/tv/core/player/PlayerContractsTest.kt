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
    fun standardBufferProfile_startsWithinTvBoxTargetWindow() {
        val config = bufferConfigForProfile(profile = BufferProfile.STANDARD)

        assertEquals(6_000, config.minBufferMs)
        assertEquals(45_000, config.maxBufferMs)
        assertEquals(1_500, config.bufferForPlaybackMs)
        assertEquals(2_500, config.bufferForPlaybackAfterRebufferMs)
    }

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
    fun p2pMedia3BufferConfig_boundsDuplicateLocalhostReadAhead() {
        val config = p2pMedia3BufferConfig(
            BufferConfig(
                minBufferMs = 20_000,
                maxBufferMs = 60_000,
                bufferForPlaybackMs = 8_000,
                bufferForPlaybackAfterRebufferMs = 2_500,
                targetBufferBytes = 64 * 1024 * 1024
            )
        )

        assertEquals(10_000, config.minBufferMs)
        assertEquals(30_000, config.maxBufferMs)
        assertEquals(2_000, config.bufferForPlaybackMs)
        assertEquals(4_000, config.bufferForPlaybackAfterRebufferMs)
        assertEquals(32 * 1024 * 1024, config.targetBufferBytes)
    }

    @Test
    fun p2pMedia3BufferConfig_preservesAlreadyConservativeDeviceBounds() {
        val config = p2pMedia3BufferConfig(
            BufferConfig(
                minBufferMs = 6_000,
                maxBufferMs = 25_000,
                bufferForPlaybackMs = 1_500,
                bufferForPlaybackAfterRebufferMs = 2_500,
                targetBufferBytes = 24 * 1024 * 1024
            )
        )

        assertEquals(6_000, config.minBufferMs)
        assertEquals(25_000, config.maxBufferMs)
        assertEquals(1_500, config.bufferForPlaybackMs)
        assertEquals(4_000, config.bufferForPlaybackAfterRebufferMs)
        assertEquals(24 * 1024 * 1024, config.targetBufferBytes)
    }

    @Test
    fun p2pMedia3BufferConfig_neverRaisesRebufferBeyondMinBuffer() {
        val config = p2pMedia3BufferConfig(
            BufferConfig(
                minBufferMs = 3_000,
                maxBufferMs = 15_000,
                bufferForPlaybackMs = 700,
                bufferForPlaybackAfterRebufferMs = 1_200
            )
        )

        assertEquals(3_000, config.minBufferMs)
        assertEquals(15_000, config.maxBufferMs)
        assertEquals(700, config.bufferForPlaybackMs)
        assertEquals(3_000, config.bufferForPlaybackAfterRebufferMs)
        assertEquals(32 * 1024 * 1024, config.targetBufferBytes)
    }

    @Test
    fun externalPlayerCheck_worksForVlc() {
        assertTrue(isExternalPlayer(PlayerType.VLC))
        assertFalse(isExternalPlayer(PlayerType.INTERNAL))
    }
}
