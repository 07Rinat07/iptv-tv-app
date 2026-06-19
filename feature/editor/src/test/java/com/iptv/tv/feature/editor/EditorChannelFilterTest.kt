package com.iptv.tv.feature.editor

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorChannelFilterTest {
    @Test
    fun filterEditorChannels_withBlankQuery_returnsAllChannels() {
        val channels = listOf(
            channel(id = 1, name = "News One"),
            channel(id = 2, name = "Movie Hub")
        )

        val result = filterEditorChannels(channels, "   ")

        assertEquals(channels, result)
    }

    @Test
    fun filterEditorChannels_matchesNameGroupTvgIdLogoAndStreamUrl() {
        val channels = listOf(
            channel(id = 1, name = "News One", group = "World", tvgId = "news.one"),
            channel(id = 2, name = "Movie Hub", group = "Cinema"),
            channel(id = 3, name = "Kids", logo = "https://cdn.example.com/kids-logo.png"),
            channel(id = 4, name = "Sports", streamUrl = "https://stream.example.com/live/sport.m3u8")
        )

        assertEquals(listOf(1L), filterEditorChannels(channels, "NEWS.ONE").map { it.id })
        assertEquals(listOf(2L), filterEditorChannels(channels, "cinema").map { it.id })
        assertEquals(listOf(3L), filterEditorChannels(channels, "kids-logo").map { it.id })
        assertEquals(listOf(4L), filterEditorChannels(channels, "sport.m3u8").map { it.id })
    }

    @Test
    fun filterEditorChannelsWithoutLogo_keepsCurrentQueryAndOnlyMissingLogos() {
        val channels = listOf(
            channel(id = 1, name = "News One", group = "News", logo = null),
            channel(id = 2, name = "News Two", group = "News", logo = "https://cdn.example.com/news2.png"),
            channel(id = 3, name = "Movie Hub", group = "Cinema", logo = "")
        )

        val result = filterEditorChannelsWithoutLogo(channels, "news")

        assertEquals(listOf(1L), result.map { it.id })
    }

    private fun channel(
        id: Long,
        name: String,
        group: String? = null,
        tvgId: String? = null,
        logo: String? = null,
        streamUrl: String = "https://example.com/channel.m3u8"
    ): Channel {
        return Channel(
            id = id,
            playlistId = 10,
            tvgId = tvgId,
            name = name,
            group = group,
            logo = logo,
            streamUrl = streamUrl,
            health = ChannelHealth.UNKNOWN,
            orderIndex = id.toInt(),
            isHidden = false
        )
    }
}
