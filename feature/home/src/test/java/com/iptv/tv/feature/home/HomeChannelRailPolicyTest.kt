package com.iptv.tv.feature.home

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeChannelRailPolicyTest {
    @Test
    fun `rail preserves repository order hides hidden channels and applies limit`() {
        val channels = listOf(
            channel(id = 30, playlistId = 2, orderIndex = 0),
            channel(id = 10, playlistId = 1, orderIndex = 50),
            channel(id = 20, playlistId = 1, orderIndex = 1, isHidden = true),
            channel(id = 40, playlistId = 2, orderIndex = 2)
        )

        assertEquals(
            listOf(30L, 10L),
            homeChannelRailItems(channels, limit = 2).map { it.id }
        )
    }

    @Test
    fun `rail focus restores selected channel when rendered`() {
        val channels = listOf(
            channel(id = 10, orderIndex = 0),
            channel(id = 20, orderIndex = 1),
            channel(id = 30, orderIndex = 2)
        )

        assertEquals(1, homeChannelRailFocusIndex(channels, selectedChannelId = 20))
    }

    @Test
    fun `rail focus falls back to first channel and rejects empty rail`() {
        val channels = listOf(channel(id = 10, orderIndex = 0))

        assertEquals(0, homeChannelRailFocusIndex(channels, selectedChannelId = 999))
        assertNull(homeChannelRailFocusIndex(emptyList(), selectedChannelId = 999))
    }

    private fun channel(
        id: Long,
        playlistId: Long = 7,
        orderIndex: Int,
        isHidden: Boolean = false
    ): Channel = Channel(
        id = id,
        playlistId = playlistId,
        tvgId = "tvg-$id",
        name = "Channel $id",
        group = "Live",
        logo = null,
        streamUrl = "https://example.invalid/$id",
        health = ChannelHealth.UNKNOWN,
        orderIndex = orderIndex,
        isHidden = isHidden
    )
}
