package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlaybackHistoryItem
import com.iptv.tv.core.model.VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualRecentChannelsBoundedCandidatesTest {
    @Test
    fun recentBrowseUsesBoundedCandidatesWithoutFullDelegateAggregates() = runTest {
        val delegate = mockk<VirtualAllChannelsPlaylistRepository>()
        val historyRepository = mockk<HistoryRepository>()
        val settingsRepository = mockk<SettingsRepository>()
        val favoritesRepository = mockk<FavoritesRepository>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val history = listOf(history(id = 1L, channelId = 10L, playedAt = 100L))
        val settings = parentalSettings()
        val liveChannel = channelEntity(id = 10L, playlistId = 7L, name = "News")

        every { historyRepository.observeHistory(any()) } returns flowOf(history)
        every { settingsRepository.observeParentalControlSettings() } returns flowOf(settings)
        every { favoritesRepository.observeFavoriteCount() } returns flowOf(0)
        every { favoriteChannelLookupDao.observeChannelTableInvalidation() } returns flowOf(0)
        every { favoriteSnapshotDao.observeFavoriteVariantCount() } returns flowOf(0)
        coEvery { favoriteChannelLookupDao.findChannelsByIds(listOf(10L)) } returns listOf(liveChannel)
        coEvery { favoriteSnapshotDao.findFavoritesByPreferredChannelIds(listOf(10L)) } returns emptyList()

        val aggregateTestScope = CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
        )
        val repository = VirtualRecentChannelsPlaylistRepository(
            delegate = delegate,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            epgSettingsRepository = mockk(relaxed = true),
            favoritesRepository = favoritesRepository,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            aggregateScope = VirtualPlaylistAggregateScope.forTest(aggregateTestScope)
        )

        val recent = repository.observeChannels(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID).first()

        assertEquals(listOf(10L), recent.map(Channel::id))
        verify(exactly = 0) { delegate.observeChannels(any()) }
        coVerify(exactly = 1) { favoriteChannelLookupDao.findChannelsByIds(listOf(10L)) }
        coVerify(exactly = 1) { favoriteSnapshotDao.findFavoritesByPreferredChannelIds(listOf(10L)) }
        coVerify(exactly = 0) { favoriteSnapshotDao.findVariantsByLogicalKeys(any()) }
        coVerify(exactly = 0) { favoriteLiveChannelResolver.findMatchingChannels(any()) }
    }

    @Test
    fun recentBrowseReloadsBoundedCandidatesWhenFavoriteVariantInvalidates() = runTest {
        val favoriteVariantInvalidation = MutableStateFlow(0)
        val history = listOf(history(id = 1L, channelId = 30L, playedAt = 100L))
        val emissions = mutableListOf<List<Channel>>()
        var loads = 0

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeVirtualRecentChannels(
                history = flowOf(history),
                parentalSettings = flowOf(parentalSettings()),
                channelInvalidation = flowOf(0),
                favoriteInvalidation = flowOf(0),
                favoriteVariantInvalidation = favoriteVariantInvalidation,
                loadCandidates = {
                    loads += 1
                    RecentMetadataCandidates(
                        allChannels = emptyList(),
                        favoriteChannels = listOf(
                            channel(
                                id = 30L,
                                playlistId = if (loads == 1) 7L else 8L,
                                name = "Favorite",
                                streamUrl = if (loads == 1) {
                                    "https://first.example/live.m3u8"
                                } else {
                                    "https://second.example/live.m3u8"
                                }
                            )
                        )
                    )
                }
            ).take(2).toList(emissions)
        }

        advanceUntilIdle()
        assertEquals(1, loads)
        assertEquals(listOf(7L), emissions.single().map(Channel::playlistId))

        favoriteVariantInvalidation.value = 1
        advanceUntilIdle()

        assertEquals(2, loads)
        assertEquals(2, emissions.size)
        assertEquals(8L, emissions.last().single().playlistId)
        assertEquals("https://second.example/live.m3u8", emissions.last().single().streamUrl)
    }

    private fun parentalSettings(): ParentalControlSettings = ParentalControlSettings(
        enabled = false,
        pinConfigured = false,
        hideAdultChannels = true,
        blockedKeywords = emptyList()
    )

    private fun history(id: Long, channelId: Long, playedAt: Long): PlaybackHistoryItem =
        PlaybackHistoryItem(
            id = id,
            channelId = channelId,
            channelName = "History $channelId",
            playedAt = playedAt
        )

    private fun channelEntity(id: Long, playlistId: Long, name: String): ChannelEntity =
        ChannelEntity(
            id = id,
            playlistId = playlistId,
            tvgId = "tvg-$id",
            name = name,
            groupName = "General",
            logo = null,
            streamUrl = "https://example.com/$id.m3u8",
            health = ChannelHealth.AVAILABLE.name,
            orderIndex = 0,
            isHidden = false
        )

    private fun channel(
        id: Long,
        playlistId: Long,
        name: String,
        streamUrl: String
    ): Channel = Channel(
        id = id,
        playlistId = playlistId,
        tvgId = "tvg-$id",
        name = name,
        group = "General",
        logo = null,
        streamUrl = streamUrl,
        health = ChannelHealth.AVAILABLE,
        orderIndex = 0,
        isHidden = false
    )
}
