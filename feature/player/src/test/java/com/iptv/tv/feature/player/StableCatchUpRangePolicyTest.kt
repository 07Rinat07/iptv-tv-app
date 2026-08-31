package com.iptv.tv.feature.player

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelCatchUpMetadata
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableCatchUpRangePolicyTest {
    @Test
    fun `explicit days plus confirmed archive exposes range label`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null,
                daysDeclared = true
            )
        )

        assertEquals(
            "Архив: до 7 дней",
            StableCatchUpRangePolicy.label(
                channel = channel,
                programs = listOf(program(start = 100_000L, end = 200_000L)),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `single declared day uses singular genitive label`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 1,
                sourceTemplate = null,
                daysDeclared = true
            )
        )

        assertEquals(
            "Архив: до 1 дня",
            StableCatchUpRangePolicy.label(
                channel = channel,
                programs = listOf(program(start = 100_000L, end = 200_000L)),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `undeclared days do not invent archive range`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = null,
                sourceTemplate = null,
                daysDeclared = false
            )
        )

        assertNull(
            StableCatchUpRangePolicy.label(
                channel = channel,
                programs = listOf(program(start = 100_000L, end = 200_000L)),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `invalid declared days do not expose archive range`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = null,
                sourceTemplate = null,
                daysDeclared = true
            )
        )

        assertNull(
            StableCatchUpRangePolicy.label(
                channel = channel,
                programs = listOf(program(start = 100_000L, end = 200_000L)),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `unsupported mode does not expose provider declared range`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "provider-private",
                days = 7,
                sourceTemplate = null,
                daysDeclared = true
            )
        )

        assertNull(
            StableCatchUpRangePolicy.label(
                channel = channel,
                programs = listOf(program(start = 100_000L, end = 200_000L)),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `unsupported p2p transport does not expose provider declared range`() {
        val channel = channel(
            streamUrl = "acestream://0123456789abcdef0123456789abcdef01234567",
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null,
                daysDeclared = true
            )
        )

        assertNull(
            StableCatchUpRangePolicy.label(
                channel = channel,
                programs = listOf(program(start = 100_000L, end = 200_000L)),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `only current or future programmes do not prove archive range`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null,
                daysDeclared = true
            )
        )

        assertNull(
            StableCatchUpRangePolicy.label(
                channel = channel,
                programs = listOf(
                    program(start = 200_000L, end = 400_000L),
                    program(start = 400_000L, end = 500_000L)
                ),
                nowMs = 300_000L
            )
        )
    }

    private fun channel(
        streamUrl: String = "https://example.test/live.m3u8",
        catchUp: ChannelCatchUpMetadata?
    ): Channel = Channel(
        id = 1L,
        playlistId = 10L,
        tvgId = "test",
        name = "Test",
        group = "Test",
        logo = null,
        streamUrl = streamUrl,
        health = ChannelHealth.UNKNOWN,
        orderIndex = 0,
        isHidden = false,
        catchUp = catchUp
    )

    private fun program(start: Long, end: Long): EpgProgram = EpgProgram(
        title = "Programme",
        description = null,
        category = null,
        startEpochMs = start,
        endEpochMs = end
    )
}
