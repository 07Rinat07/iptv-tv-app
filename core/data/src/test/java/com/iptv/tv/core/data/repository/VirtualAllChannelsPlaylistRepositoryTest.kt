package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_SOURCE
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_PLAYLIST_ID
import com.iptv.tv.core.model.isSystemVirtualPlaylistId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualAllChannelsPlaylistRepositoryTest {
    @Test
    fun virtualPlaylistUsesStableSystemIdentityAndCoercesNegativeCount() {
        val playlist = virtualAllChannelsPlaylist(channelCount = -3)

        assertEquals(VIRTUAL_ALL_CHANNELS_PLAYLIST_ID, playlist.id)
        assertTrue(playlist.id < 0)
        assertEquals("Все каналы", playlist.name)
        assertEquals(VIRTUAL_ALL_CHANNELS_SOURCE, playlist.source)
        assertEquals(PlaylistSourceType.CUSTOM, playlist.sourceType)
        assertEquals(CatalogOriginKind.SYSTEM, playlist.catalogOrigin)
        assertEquals(0, playlist.channelCount)
        assertFalse(playlist.isCustom)
    }

    @Test
    fun parentalFilteringMatchesNameGroupAndTvgIdWhilePreservingSafeRows() {
        val safe = channelEntity(id = 10, name = "Kids", groupName = "Family", tvgId = "kids")
        val blockedByName = channelEntity(id = 11, name = "Adult Cinema", groupName = "Movies")
        val blockedByGroup = channelEntity(id = 12, name = "Night", groupName = "18+")
        val blockedByTvgId = channelEntity(id = 13, name = "Cinema", groupName = "Movies", tvgId = "adult-hd")
        val hiddenButSafe = channelEntity(id = 14, name = "Hidden Kids", groupName = "Family", isHidden = true)
        val rows = listOf(safe, blockedByName, blockedByGroup, blockedByTvgId, hiddenButSafe)
        val enabledGate = ParentalChannelGate(
            enabled = true,
            hideAdultChannels = true,
            blockedKeywords = listOf("adult", "18+")
        )

        val filtered = allChannelsForVirtualView(rows, enabledGate)

        assertEquals(listOf(10L, 14L), filtered.map(Channel::id))
        assertEquals(listOf(10L, 14L), filtered.map(Channel::playlistId))
        assertTrue(filtered.last().isHidden)

        val disabledGate = enabledGate.copy(enabled = false)
        assertEquals(rows.map(ChannelEntity::id), allChannelsForVirtualView(rows, disabledGate).map(Channel::id))
    }

    @Test
    fun virtualSummaryCountsVisibleChannelsAndOrdersPreviewsDeterministically() {
        val channels = listOf(
            channel(
                id = 10,
                playlistId = 2,
                name = "Zeta News",
                group = "News",
                health = ChannelHealth.AVAILABLE,
                orderIndex = 4,
                tvgId = "zeta.news",
                logo = "https://example.com/zeta.png"
            ),
            channel(
                id = 11,
                playlistId = 1,
                name = "Alpha News",
                group = "News",
                health = ChannelHealth.UNSTABLE,
                orderIndex = 7
            ),
            channel(
                id = 12,
                playlistId = 1,
                name = "Hidden Sport",
                group = "Sports",
                health = ChannelHealth.UNAVAILABLE,
                orderIndex = 1,
                tvgId = "hidden.sport",
                logo = "https://example.com/hidden.png",
                isHidden = true
            ),
            channel(
                id = 13,
                playlistId = 1,
                name = "Alpha Sport",
                group = "Sports",
                health = ChannelHealth.UNKNOWN,
                orderIndex = 1
            ),
            channel(
                id = 14,
                playlistId = 3,
                name = "Gamma",
                group = " ",
                health = ChannelHealth.UNAVAILABLE,
                orderIndex = 0,
                tvgId = "gamma",
                logo = "https://example.com/gamma.png"
            )
        )

        val summary = virtualAllChannelsSummary(channels)

        assertEquals(VIRTUAL_ALL_CHANNELS_PLAYLIST_ID, summary.playlistId)
        assertEquals(5, summary.totalChannels)
        assertEquals(4, summary.visibleChannels)
        assertEquals(1, summary.hiddenChannels)
        assertEquals(2, summary.channelsWithLogo)
        assertEquals(2, summary.channelsWithTvgId)
        assertEquals(1, summary.availableChannels)
        assertEquals(1, summary.unstableChannels)
        assertEquals(1, summary.unavailableChannels)
        assertEquals(1, summary.unknownHealthChannels)
        assertEquals(2, summary.groupCount)
        assertEquals(listOf("News" to 2, "Sports" to 1), summary.topGroups)
        assertEquals(listOf(13L, 11L, 10L, 14L), summary.channelPreviews.map { it.id })
    }

    @Test
    fun systemVirtualPlaylistPredicateRecognizesOnlyReservedAggregateIds() {
        assertTrue(isSystemVirtualPlaylistId(VIRTUAL_ALL_CHANNELS_PLAYLIST_ID))
        assertTrue(isSystemVirtualPlaylistId(VIRTUAL_FAVORITES_PLAYLIST_ID))
        assertFalse(isSystemVirtualPlaylistId(1L))
        assertFalse(isSystemVirtualPlaylistId(-1L))
    }

    private fun channelEntity(
        id: Long,
        name: String,
        groupName: String?,
        tvgId: String? = null,
        isHidden: Boolean = false
    ): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = id,
        tvgId = tvgId,
        name = name,
        groupName = groupName,
        logo = null,
        streamUrl = "https://example.com/$id.m3u8",
        health = ChannelHealth.UNKNOWN.name,
        orderIndex = id.toInt(),
        isHidden = isHidden
    )

    private fun channel(
        id: Long,
        playlistId: Long,
        name: String,
        group: String?,
        health: ChannelHealth,
        orderIndex: Int,
        tvgId: String? = null,
        logo: String? = null,
        isHidden: Boolean = false
    ): Channel = Channel(
        id = id,
        playlistId = playlistId,
        tvgId = tvgId,
        name = name,
        group = group,
        logo = logo,
        streamUrl = "https://example.com/$id.m3u8",
        health = health,
        orderIndex = orderIndex,
        isHidden = isHidden
    )
}
