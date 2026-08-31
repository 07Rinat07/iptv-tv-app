package com.iptv.tv.feature.player

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelCatchUpMetadata
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class StableCatchUpActionPolicyTest {
    @Test
    fun `finished programme with supported metadata is available`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null
            )
        )

        assertEquals(
            StableCatchUpActionState.AVAILABLE,
            StableCatchUpActionPolicy.state(
                channel = channel,
                program = program(start = 100_000L, end = 200_000L),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `finished programme without catch-up metadata is unavailable`() {
        assertEquals(
            StableCatchUpActionState.UNAVAILABLE,
            StableCatchUpActionPolicy.state(
                channel = channel(catchUp = null),
                program = program(start = 100_000L, end = 200_000L),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `current programme keeps archive state hidden`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null
            )
        )

        assertEquals(
            StableCatchUpActionState.HIDDEN,
            StableCatchUpActionPolicy.state(
                channel = channel,
                program = program(start = 100_000L, end = 400_000L),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `future programme keeps archive state hidden`() {
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null
            )
        )

        assertEquals(
            StableCatchUpActionState.HIDDEN,
            StableCatchUpActionPolicy.state(
                channel = channel,
                program = program(start = 400_000L, end = 500_000L),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `missing channel keeps archive state hidden`() {
        assertEquals(
            StableCatchUpActionState.HIDDEN,
            StableCatchUpActionPolicy.state(
                channel = null,
                program = program(start = 100_000L, end = 200_000L),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `finished programme on unsupported live transport is unavailable`() {
        val channel = channel(
            streamUrl = "acestream://0123456789abcdef0123456789abcdef01234567",
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 7,
                sourceTemplate = null
            )
        )

        assertEquals(
            StableCatchUpActionState.UNAVAILABLE,
            StableCatchUpActionPolicy.state(
                channel = channel,
                program = program(start = 100_000L, end = 200_000L),
                nowMs = 300_000L
            )
        )
    }

    @Test
    fun `programme outside declared catch-up window is unavailable`() {
        val dayMs = 24L * 60L * 60L * 1_000L
        val now = 10L * dayMs
        val channel = channel(
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 2,
                sourceTemplate = null
            )
        )

        assertEquals(
            StableCatchUpActionState.UNAVAILABLE,
            StableCatchUpActionPolicy.state(
                channel = channel,
                program = program(start = now - 3L * dayMs, end = now - 3L * dayMs + 60_000L),
                nowMs = now
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
