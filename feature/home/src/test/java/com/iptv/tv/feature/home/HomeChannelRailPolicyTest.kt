package com.iptv.tv.feature.home

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeChannelRailPolicyTest {
    @Test
    fun `rail keeps canonical order hides hidden channels and applies limit`() {
        val channels = listOf(
            channel(id = 3, orderIndex = 30),
            channel(id = 1, orderIndex = 10),
            channel(id = 2, orderIndex = 20, isHidden = true),
            channel(id = 4, orderIndex = 40)
        )

        assertEquals(
            listOf(1L, 3L),
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
        orderIndex: Int,
        isHidden: Boolean = false
    ): Channel = Channel(
        id = id,
        playlistId = 7,
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
