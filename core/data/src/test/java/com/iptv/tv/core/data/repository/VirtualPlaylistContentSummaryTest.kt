package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualPlaylistContentSummaryTest {
    @Test
    fun previewPreparationIsBoundedAndKeepsStableSortTies() {
        val visible = (1L..80L).map { id ->
            channel(id = id, hidden = false)
        }
        val hidden = channel(id = 999L, hidden = true)

        val summary = virtualPlaylistContentSummary(
            playlistId = -99L,
            playlistName = "Virtual",
            source = "virtual://test",
            channels = visible + hidden,
            // Every visible channel intentionally compares equal. The previous stable full sort
            // therefore kept input order; bounded selection must preserve that exact behavior.
            previewComparator = compareBy<Channel> { it.orderIndex }
        )

        assertEquals(81, summary.totalChannels)
        assertEquals(80, summary.visibleChannels)
        assertEquals(1, summary.hiddenChannels)
        assertEquals(VIRTUAL_PLAYLIST_PREVIEW_LIMIT, summary.channelPreviews.size)
        assertEquals((1L..50L).toList(), summary.channelPreviews.map { it.id })
    }

    private fun channel(id: Long, hidden: Boolean): Channel = Channel(
        id = id,
        playlistId = 1L,
        tvgId = null,
        name = "Tie",
        group = "General",
        logo = null,
        streamUrl = "https://example.com/$id.m3u8",
        health = ChannelHealth.UNKNOWN,
        orderIndex = 0,
        isHidden = hidden
    )
}
