package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.AllChannelsGroupCountRow
import com.iptv.tv.core.database.dao.AllChannelsParentalSummaryRow
import com.iptv.tv.core.database.dao.AllChannelsSummaryAggregateRow
import com.iptv.tv.core.database.dao.AllChannelsSummaryPreviewRow
import com.iptv.tv.core.database.dao.AllChannelsSummarySnapshot
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_SOURCE
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_PLAYLIST_ID
import com.iptv.tv.core.model.isSystemVirtualPlaylistId
import kotlinx.coroutines.test.runTest
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
    fun summaryWithoutParentalBlockingUsesSingleBoundedSnapshotRead() = runTest {
        var snapshotReads = 0
        var requestedGroupLimit = -1
        var requestedPreviewLimit = -1
        var parentalRowReads = 0

        val summary = loadVirtualAllChannelsSummary(
            parentalGate = ParentalChannelGate(
                enabled = false,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            ),
            snapshot = { groupLimit, previewLimit ->
                snapshotReads += 1
                requestedGroupLimit = groupLimit
                requestedPreviewLimit = previewLimit
                AllChannelsSummarySnapshot(
                    aggregate = AllChannelsSummaryAggregateRow(
                        totalChannels = 12_500,
                        visibleChannels = 12_000,
                        hiddenChannels = 500,
                        channelsWithLogo = 10_000,
                        channelsWithTvgId = 11_000,
                        availableChannels = 9_000,
                        unstableChannels = 1_000,
                        unavailableChannels = 500,
                        unknownHealthChannels = 1_500,
                        groupCount = 250
                    ),
                    topGroups = listOf(
                        AllChannelsGroupCountRow(groupName = "News", channelCount = 800)
                    ),
                    previews = listOf(
                        AllChannelsSummaryPreviewRow(
                            id = 10,
                            name = "Alpha",
                            groupName = "News",
                            logo = null,
                            health = ChannelHealth.AVAILABLE.name,
                            isHidden = false
                        )
                    )
                )
            },
            parentalRows = {
                parentalRowReads += 1
                emptyList()
            }
        )

        assertEquals(1, snapshotReads)
        assertEquals(VIRTUAL_PLAYLIST_TOP_GROUP_LIMIT, requestedGroupLimit)
        assertEquals(VIRTUAL_PLAYLIST_PREVIEW_LIMIT, requestedPreviewLimit)
        assertEquals(0, parentalRowReads)
        assertEquals(12_500, summary.totalChannels)
        assertEquals(12_000, summary.visibleChannels)
        assertEquals(250, summary.groupCount)
        assertEquals(listOf("News" to 800), summary.topGroups)
        assertEquals(listOf(10L), summary.channelPreviews.map { it.id })
    }

    @Test
    fun summaryWithParentalBlockingUsesOnlyNarrowSummaryRows() = runTest {
        var snapshotReads = 0
        var parentalRowReads = 0
        val gate = ParentalChannelGate(
            enabled = true,
            hideAdultChannels = true,
            blockedKeywords = listOf("adult", "18+")
        )

        val summary = loadVirtualAllChannelsSummary(
            parentalGate = gate,
            snapshot = { _, _ ->
                snapshotReads += 1
                error("bounded SQL snapshot must not run while parental filtering is active")
            },
            parentalRows = {
                parentalRowReads += 1
                listOf(
                    parentalSummaryRow(
                        id = 10,
                        playlistId = 2,
                        name = "Safe News",
                        groupName = "News",
                        health = ChannelHealth.AVAILABLE,
                        orderIndex = 5,
                        logo = "https://example.com/safe.png",
                        tvgId = "safe.news"
                    ),
                    parentalSummaryRow(
                        id = 11,
                        playlistId = 1,
                        name = "Adult Cinema",
                        groupName = "Movies",
                        health = ChannelHealth.UNSTABLE,
                        orderIndex = 2
                    ),
                    parentalSummaryRow(
                        id = 12,
                        playlistId = 1,
                        name = "Hidden Kids",
                        groupName = "Family",
                        health = ChannelHealth.UNKNOWN,
                        orderIndex = 1,
                        isHidden = true
                    )
                )
            }
        )

        assertEquals(0, snapshotReads)
        assertEquals(1, parentalRowReads)
        assertEquals(2, summary.totalChannels)
        assertEquals(1, summary.visibleChannels)
        assertEquals(1, summary.hiddenChannels)
        assertEquals(1, summary.channelsWithLogo)
        assertEquals(1, summary.channelsWithTvgId)
        assertEquals(listOf("News" to 1), summary.topGroups)
        assertEquals(listOf(10L), summary.channelPreviews.map { it.id })
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

    private fun parentalSummaryRow(
        id: Long,
        playlistId: Long,
        name: String,
        groupName: String?,
        health: ChannelHealth,
        orderIndex: Int,
        tvgId: String? = null,
        logo: String? = null,
        isHidden: Boolean = false
    ): AllChannelsParentalSummaryRow = AllChannelsParentalSummaryRow(
        id = id,
        playlistId = playlistId,
        tvgId = tvgId,
        name = name,
        groupName = groupName,
        logo = logo,
        health = health.name,
        orderIndex = orderIndex,
        isHidden = isHidden
    )
}
