package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyPlaylistRefreshPlannerTest {

    @Test
    fun preservesRowStateWhenStableIdentitySurvivesStreamChange() {
        val existing = channelEntity(
            id = 41L,
            tvgId = "news-hd",
            name = "News HD",
            streamUrl = "https://old.example/news.m3u8",
            health = ChannelHealth.AVAILABLE,
            hidden = true
        )
        val incoming = channel(
            tvgId = "news-hd",
            name = "News",
            streamUrl = "https://new.example/news.m3u8"
        )

        val plan = ReadyPlaylistRefreshPlanner.plan(7L, listOf(existing), listOf(incoming))
        val updated = plan.upsertChannels.single()

        assertEquals(41L, updated.id)
        assertEquals(7L, updated.playlistId)
        assertEquals("https://new.example/news.m3u8", updated.streamUrl)
        assertEquals(ChannelHealth.AVAILABLE.name, updated.health)
        assertTrue(updated.isHidden)
        assertTrue(plan.staleChannelIds.isEmpty())
    }

    @Test
    fun exactStreamFallbackPreservesIdWhenPublisherAddsTvgId() {
        val existing = channelEntity(
            id = 9L,
            tvgId = null,
            name = "Movie",
            streamUrl = "https://example.org/movie.m3u8"
        )
        val incoming = channel(
            tvgId = "movie-channel",
            name = "Movie HD",
            streamUrl = "https://example.org/movie.m3u8"
        )

        val updated = ReadyPlaylistRefreshPlanner
            .plan(3L, listOf(existing), listOf(incoming))
            .upsertChannels
            .single()

        assertEquals(9L, updated.id)
        assertEquals("movie-channel", updated.tvgId)
    }

    @Test
    fun insertsNewRowsAndMarksMissingRowsStaleWithoutReusingTheirIds() {
        val kept = channelEntity(
            id = 1L,
            tvgId = "kept",
            name = "Kept",
            streamUrl = "https://example.org/kept.m3u8"
        )
        val removed = channelEntity(
            id = 2L,
            tvgId = "removed",
            name = "Removed",
            streamUrl = "https://example.org/removed.m3u8"
        )
        val incoming = listOf(
            channel("kept", "Kept", "https://example.org/kept-new.m3u8"),
            channel("new", "New", "https://example.org/new.m3u8")
        )

        val plan = ReadyPlaylistRefreshPlanner.plan(5L, listOf(kept, removed), incoming)

        assertEquals(listOf(1L, 0L), plan.upsertChannels.map { it.id })
        assertEquals(listOf(0, 1), plan.upsertChannels.map { it.orderIndex })
        assertEquals(listOf(2L), plan.staleChannelIds)
        assertFalse(plan.upsertChannels.last().isHidden)
        assertEquals(ChannelHealth.UNKNOWN.name, plan.upsertChannels.last().health)
    }

    private fun channelEntity(
        id: Long,
        tvgId: String?,
        name: String,
        streamUrl: String,
        health: ChannelHealth = ChannelHealth.UNKNOWN,
        hidden: Boolean = false
    ) = ChannelEntity(
        id = id,
        playlistId = 1L,
        tvgId = tvgId,
        name = name,
        groupName = "Group",
        logo = null,
        streamUrl = streamUrl,
        health = health.name,
        orderIndex = 0,
        isHidden = hidden
    )

    private fun channel(
        tvgId: String?,
        name: String,
        streamUrl: String
    ) = Channel(
        id = 0L,
        playlistId = 0L,
        tvgId = tvgId,
        name = name,
        group = "Group",
        logo = null,
        streamUrl = streamUrl,
        health = ChannelHealth.UNKNOWN,
        orderIndex = 0,
        isHidden = false
    )
}
