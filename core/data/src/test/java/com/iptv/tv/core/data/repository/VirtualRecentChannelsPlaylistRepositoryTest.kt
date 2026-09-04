package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toEntity
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelStableIdentity
import com.iptv.tv.core.model.LegacyPlaylistCatalogAdapter
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlaybackHistoryItem
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_RECENT_CHANNELS_SOURCE
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualRecentChannelsPlaylistRepositoryTest {
    @Test
    fun outerDecoratorPublishesRecentCountAndReactsToParentalUpdates() = runTest {
        val delegate = mockk<VirtualAllChannelsPlaylistRepository>()
        val historyRepository = mockk<HistoryRepository>()
        val settingsRepository = mockk<SettingsRepository>()
        val favoritesRepository = mockk<FavoritesRepository>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val adult = channel(id = 10, playlistId = 1, name = "Adult Cinema")
        val safe = channel(id = 20, playlistId = 1, name = "Kids")
        val orphanFavorite = channel(id = 30, playlistId = 77, name = "Orphan Favorite")
        val allChannels = MutableStateFlow(listOf(adult, safe))
        val favoriteChannels = MutableStateFlow(listOf(orphanFavorite))
        val history = MutableStateFlow(
            listOf(
                history(id = 1, channelId = 10, playedAt = 300),
                history(id = 2, channelId = 30, playedAt = 250),
                history(id = 3, channelId = 20, playedAt = 200)
            )
        )
        val parentalSettings = MutableStateFlow(
            ParentalControlSettings(
                enabled = false,
                pinConfigured = false,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            )
        )
        val physicalPlaylist = playlist(id = 1, name = "Physical", channelCount = 2)
        val allPlaylist = virtualAllChannelsPlaylist(channelCount = 2)
        val favoritesPlaylist = virtualFavoritesPlaylist(channelCount = 1)
        val delegatePlaylists = listOf(physicalPlaylist, allPlaylist, favoritesPlaylist)
        val orphanSnapshot = favoriteSnapshot(orphanFavorite)

        every { delegate.observePlaylists() } returns MutableStateFlow(delegatePlaylists)
        every { delegate.observeChannels(VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) } returns allChannels
        every { delegate.observeChannels(VIRTUAL_FAVORITES_PLAYLIST_ID) } returns favoriteChannels
        every { historyRepository.observeHistory(any()) } returns history
        every { settingsRepository.observeParentalControlSettings() } returns parentalSettings
        every { favoritesRepository.observeFavoriteCount() } returns flowOf(1)
        every { favoriteChannelLookupDao.observeChannelTableInvalidation() } returns flowOf(2)
        every { favoriteSnapshotDao.observeFavoriteVariantCount() } returns flowOf(0)
        coEvery { favoriteChannelLookupDao.findChannelsByIds(any()) } returns
            listOf(adult.toEntity(), safe.toEntity())
        coEvery { favoriteSnapshotDao.findFavoritesByPreferredChannelIds(any()) } returns
            listOf(orphanSnapshot)
        coEvery { favoriteSnapshotDao.findVariantsByLogicalKeys(any()) } returns emptyList()
        coEvery { favoriteLiveChannelResolver.findMatchingChannels(any()) } returns emptyList()

        // The production aggregate runs on an application scope. In coroutine tests, keep the
        // background-scope Job lifecycle but replace its dispatcher so MutableStateFlow updates
        // resume the shared upstream deterministically instead of being deferred behind the
        // background scheduler that advanceUntilIdle intentionally does not drain.
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

        val playlists = repository.observePlaylists().first()
        assertEquals(delegatePlaylists, playlists.filterNot { it.id == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID })
        assertEquals(1, playlists.count { it.id == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID })
        assertEquals(
            3,
            playlists.single { it.id == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID }.channelCount
        )

        val channelEmissions = mutableListOf<List<Channel>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.observeChannels(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID)
                .take(3)
                .toList(channelEmissions)
        }
        advanceUntilIdle()
        assertEquals(listOf(10L, 30L, 20L), channelEmissions.single().map { it.id })

        parentalSettings.value = parentalSettings.value.copy(enabled = true)
        advanceUntilIdle()

        assertEquals(2, channelEmissions.size)
        assertEquals(listOf(30L, 20L), channelEmissions.last().map { it.id })
        assertEquals(
            2,
            repository.observePlaylists().first()
                .single { it.id == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID }
                .channelCount
        )

        history.value = emptyList()
        advanceUntilIdle()

        assertEquals(3, channelEmissions.size)
        assertTrue(channelEmissions.last().isEmpty())
        assertEquals(
            0,
            repository.observePlaylists().first()
                .single { it.id == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID }
                .channelCount
        )
    }

    @Test
    fun playlistMetadataDoesNotSubscribeFullRecentChannelAggregates() = runTest {
        val delegate = mockk<VirtualAllChannelsPlaylistRepository>()
        val historyRepository = mockk<HistoryRepository>()
        val settingsRepository = mockk<SettingsRepository>()
        val favoritesRepository = mockk<FavoritesRepository>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val settings = ParentalControlSettings(
            enabled = false,
            pinConfigured = false,
            hideAdultChannels = true,
            blockedKeywords = emptyList()
        )

        every { delegate.observePlaylists() } returns flowOf(emptyList())
        every { historyRepository.observeHistory(any()) } returns flowOf(emptyList())
        every { settingsRepository.observeParentalControlSettings() } returns flowOf(settings)
        every { favoritesRepository.observeFavoriteCount() } returns flowOf(0)
        every { favoriteChannelLookupDao.observeChannelTableInvalidation() } returns flowOf(0)
        every { favoriteSnapshotDao.observeFavoriteVariantCount() } returns flowOf(0)

        val repository = VirtualRecentChannelsPlaylistRepository(
            delegate = delegate,
            historyRepository = historyRepository,
            settingsRepository = settingsRepository,
            epgSettingsRepository = mockk(relaxed = true),
            favoritesRepository = favoritesRepository,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            aggregateScope = VirtualPlaylistAggregateScope.forTest(backgroundScope)
        )

        val recent = repository.observePlaylists().first()
            .single { it.id == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID }

        assertEquals(0, recent.channelCount)
        verify(exactly = 0) { delegate.observeChannels(any()) }
        verify(exactly = 0) { favoriteSnapshotDao.observeFavoriteVariants() }
        coVerify(exactly = 0) { favoriteChannelLookupDao.findChannelsByIds(any()) }
        coVerify(exactly = 0) { favoriteSnapshotDao.findFavoritesByPreferredChannelIds(any()) }
        coVerify(exactly = 0) { favoriteSnapshotDao.findVariantsByLogicalKeys(any()) }
        coVerify(exactly = 0) { favoriteLiveChannelResolver.findMatchingChannels(any()) }
    }

    @Test
    fun metadataCountReloadsWhenFavoriteVariantInvalidates() = runTest {
        val favoriteVariantInvalidation = MutableStateFlow(0)
        val historyItems = listOf(
            history(id = 2, channelId = 10, playedAt = 200),
            history(id = 1, channelId = 20, playedAt = 100)
        )
        val counts = mutableListOf<Int>()
        var loads = 0

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeVirtualRecentChannelCount(
                history = flowOf(historyItems),
                parentalSettings = flowOf(
                    ParentalControlSettings(
                        enabled = false,
                        pinConfigured = false,
                        hideAdultChannels = true,
                        blockedKeywords = emptyList()
                    )
                ),
                channelInvalidation = flowOf(0),
                favoriteInvalidation = flowOf(0),
                favoriteVariantInvalidation = favoriteVariantInvalidation,
                loadCandidates = {
                    loads += 1
                    RecentMetadataCandidates(
                        allChannels = if (loads == 1) {
                            listOf(channel(id = 10, playlistId = 1, name = "One"))
                        } else {
                            listOf(
                                channel(id = 10, playlistId = 1, name = "One"),
                                channel(id = 20, playlistId = 1, name = "Two")
                            )
                        },
                        favoriteChannels = emptyList()
                    )
                }
            ).take(2).toList(counts)
        }

        advanceUntilIdle()
        assertEquals(listOf(1), counts)
        assertEquals(1, loads)

        favoriteVariantInvalidation.value = 1
        advanceUntilIdle()

        assertEquals(listOf(1, 2), counts)
        assertEquals(2, loads)
    }

    @Test
    fun metadataCandidateLoaderPreservesStableFavoriteIdAndPreferredVariant() = runTest {
        val live = channel(id = 10, playlistId = 1, tvgId = "live", name = "Live")
        val staleFavoriteChannel = channel(
            id = 30,
            playlistId = 3,
            tvgId = "sport",
            name = "Sport"
        )
        val preferredStreamUrl = "https://preferred.example/sport.m3u8"
        val favorite = favoriteSnapshot(staleFavoriteChannel).copy(
            preferredStreamUrl = preferredStreamUrl,
            preferredPlaylistId = 99
        )
        val preferredVariant = FavoriteChannelVariantEntity(
            logicalKey = favorite.logicalKey,
            variantKey = UnifiedFavoritePersistence.variantKey(preferredStreamUrl),
            legacyChannelId = 77,
            playlistId = 99,
            playlistName = "Saved source",
            sourceType = PlaylistSourceType.URL.name,
            catalogOrigin = CatalogOriginKind.USER_IMPORT.name,
            tvgId = "sport",
            name = "Sport Preferred",
            groupName = "Sports",
            logo = null,
            streamUrl = preferredStreamUrl,
            addedAt = 1,
            updatedAt = 1
        )
        val otherLiveEquivalent = channel(
            id = 88,
            playlistId = 8,
            tvgId = "sport",
            name = "Sport Live"
        )
        val history = listOf(
            history(id = 2, channelId = live.id, playedAt = 300),
            history(id = 1, channelId = favorite.preferredChannelId, playedAt = 200)
        )
        val requestedLiveIds = mutableListOf<List<Long>>()
        val requestedFavoriteIds = mutableListOf<List<Long>>()
        val requestedLogicalKeys = mutableListOf<List<String>>()
        val requestedLiveLogicalKeys = mutableListOf<Set<String>>()

        val candidates = loadRecentMetadataCandidates(
            history = history,
            findChannelsByIds = { ids ->
                requestedLiveIds += ids
                listOf(live.toEntity())
            },
            findFavoritesByPreferredChannelIds = { ids ->
                requestedFavoriteIds += ids
                listOf(favorite)
            },
            findVariantsByLogicalKeys = { logicalKeys ->
                requestedLogicalKeys += logicalKeys
                listOf(preferredVariant)
            },
            findMatchingFavoriteLiveChannels = { logicalKeys ->
                requestedLiveLogicalKeys += logicalKeys
                listOf(otherLiveEquivalent.toEntity())
            }
        )

        assertEquals(listOf(listOf(10L, 30L)), requestedLiveIds)
        assertEquals(listOf(listOf(10L, 30L)), requestedFavoriteIds)
        assertEquals(listOf(listOf(favorite.logicalKey)), requestedLogicalKeys)
        assertEquals(listOf(setOf(favorite.logicalKey)), requestedLiveLogicalKeys)
        assertEquals(listOf(10L), candidates.allChannels.map { it.id })
        val favoriteRepresentative = candidates.favoriteChannels.single()
        assertEquals(30L, favoriteRepresentative.id)
        assertEquals(99L, favoriteRepresentative.playlistId)
        assertEquals("Sport Preferred", favoriteRepresentative.name)
        assertEquals(preferredStreamUrl, favoriteRepresentative.streamUrl)

        val recent = recentChannelsForVirtualView(
            history = history,
            allChannels = candidates.allChannels,
            favoriteChannels = candidates.favoriteChannels,
            parentalGate = ParentalChannelGate(false, true, emptyList())
        )
        assertEquals(listOf(10L, 30L), recent.map { it.id })
    }

    @Test
    fun plannerKeepsNewestLogicalChannelsAndResolvesFavoriteSources() {
        val sameLogicalOlder = channel(id = 10, playlistId = 1, tvgId = "news", name = "News")
        val sameLogicalNewer = channel(id = 11, playlistId = 2, tvgId = "news", name = "News HD")
        val hidden = channel(id = 20, playlistId = 1, name = "Hidden", isHidden = true)
        val adult = channel(id = 30, playlistId = 1, name = "Adult Cinema")
        val liveSource = channel(id = 50, playlistId = 5, tvgId = "sport", name = "Sport")
        val preferredFavoriteSource = liveSource.copy(
            playlistId = 99,
            streamUrl = "https://preferred.example/50.m3u8"
        )
        val orphanFavorite = channel(id = 40, playlistId = 77, tvgId = "orphan", name = "Orphan")
        val history = listOf(
            history(id = 1, channelId = 10, playedAt = 300),
            history(id = 2, channelId = 11, playedAt = 500),
            history(id = 3, channelId = 20, playedAt = 700),
            history(id = 4, channelId = 30, playedAt = 650),
            history(id = 5, channelId = 40, playedAt = 450),
            history(id = 6, channelId = 50, playedAt = 400),
            history(id = 7, channelId = 60, playedAt = 490),
            history(id = 8, channelId = 11, playedAt = 200),
            history(id = 9, channelId = -1, playedAt = 900)
        )

        val result = recentChannelsForVirtualView(
            history = history,
            allChannels = listOf(
                sameLogicalOlder,
                sameLogicalNewer,
                hidden,
                adult,
                liveSource
            ),
            favoriteChannels = listOf(orphanFavorite, preferredFavoriteSource),
            parentalGate = ParentalChannelGate(
                enabled = true,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            )
        )

        assertEquals(listOf(11L, 40L, 50L), result.map { it.id })
        assertEquals(listOf(0, 1, 2), result.map { it.orderIndex })
        assertEquals(99L, result.last().playlistId)
        assertEquals("https://preferred.example/50.m3u8", result.last().streamUrl)
    }

    @Test
    fun plannerUsesHistoryIdAsStableTieBreakAndHonorsBoundedLimit() {
        val channels = (1L..4L).map { id -> channel(id = id, playlistId = id, name = "Channel $id") }
        val history = channels.map { channel ->
            history(id = channel.id, channelId = channel.id, playedAt = 100)
        }

        val result = recentChannelsForVirtualView(
            history = history,
            allChannels = channels,
            favoriteChannels = emptyList(),
            parentalGate = ParentalChannelGate(false, true, listOf("adult")),
            limit = 2
        )

        assertEquals(listOf(4L, 3L), result.map { it.id })
        assertTrue(
            recentChannelsForVirtualView(
                history = history,
                allChannels = channels,
                favoriteChannels = emptyList(),
                parentalGate = ParentalChannelGate(false, true, emptyList()),
                limit = 0
            ).isEmpty()
        )
    }

    @Test
    fun plannerOrderSurvivesLegacyCatalogAdapterSorting() {
        val older = channel(id = 1, playlistId = 1, name = "Older").copy(orderIndex = 0)
        val latest = channel(id = 2, playlistId = 1, name = "Latest").copy(orderIndex = 500)
        val planned = recentChannelsForVirtualView(
            history = listOf(
                history(id = 1, channelId = older.id, playedAt = 100),
                history(id = 2, channelId = latest.id, playedAt = 200)
            ),
            allChannels = listOf(older, latest),
            favoriteChannels = emptyList(),
            parentalGate = ParentalChannelGate(false, true, emptyList())
        )

        val tree = LegacyPlaylistCatalogAdapter.build(
            playlist = virtualRecentChannelsPlaylist(planned.size),
            channels = planned
        )
        val nodeOrderByChannelId = tree.channelNodeIdByChannelId.mapValues { (_, nodeId) ->
            tree.nodes.single { node -> node.id == nodeId }.order
        }

        assertEquals(listOf(2L, 1L), planned.map { it.id })
        assertEquals(0, nodeOrderByChannelId[latest.id])
        assertEquals(1, nodeOrderByChannelId[older.id])
    }

    @Test
    fun virtualPlaylistAndSummaryKeepStableIdentityAndRecentOrder() {
        val playlist = virtualRecentChannelsPlaylist(channelCount = -5)
        val channels = listOf(
            channel(
                id = 30,
                playlistId = 3,
                name = "Latest",
                group = "News",
                health = ChannelHealth.UNKNOWN
            ),
            channel(
                id = 10,
                playlistId = 1,
                name = "Earlier",
                group = "News",
                health = ChannelHealth.AVAILABLE,
                tvgId = "earlier",
                logo = "https://example.com/earlier.png"
            )
        )

        val summary = virtualRecentChannelsSummary(channels)

        assertEquals(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID, playlist.id)
        assertTrue(playlist.id < 0)
        assertEquals(VIRTUAL_RECENT_CHANNELS_SOURCE, playlist.source)
        assertEquals(PlaylistSourceType.CUSTOM, playlist.sourceType)
        assertEquals(CatalogOriginKind.SYSTEM, playlist.catalogOrigin)
        assertEquals(0, playlist.channelCount)
        assertFalse(playlist.isCustom)
        assertEquals(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID, summary.playlistId)
        assertEquals(2, summary.totalChannels)
        assertEquals(2, summary.visibleChannels)
        assertEquals(1, summary.channelsWithLogo)
        assertEquals(1, summary.channelsWithTvgId)
        assertEquals(1, summary.availableChannels)
        assertEquals(1, summary.unknownHealthChannels)
        assertEquals(listOf("News" to 2), summary.topGroups)
        assertEquals(listOf(30L, 10L), summary.channelPreviews.map { it.id })
    }

    @Test
    fun recentVirtualOperationsAreGuardedWithoutDelegating() = runTest {
        val delegate = mockk<VirtualAllChannelsPlaylistRepository>(relaxed = true)
        val repository = VirtualRecentChannelsPlaylistRepository(
            delegate = delegate,
            historyRepository = mockk<HistoryRepository>(),
            settingsRepository = mockk<SettingsRepository>(),
            epgSettingsRepository = mockk(relaxed = true),
            favoritesRepository = mockk(relaxed = true),
            favoriteChannelLookupDao = mockk(relaxed = true),
            favoriteSnapshotDao = mockk(relaxed = true),
            favoriteLiveChannelResolver = mockk(relaxed = true),
            aggregateScope = VirtualPlaylistAggregateScope.forTest(backgroundScope)
        )

        assertTrue(repository.refreshPlaylist(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) is AppResult.Success)
        assertTrue(repository.deletePlaylist(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) is AppResult.Error)
        assertTrue(
            repository.setPlaylistEpgSource(
                VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID,
                "https://example.com/epg.xml"
            ) is AppResult.Error
        )
        assertTrue(repository.validatePlaylist(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) is AppResult.Error)
        val epgWindow = repository.getPlaylistEpgWindow(
            playlistId = VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID,
            startEpochMs = 0,
            endEpochMs = 1,
            query = null
        )
        assertEquals(emptyMap<Long, List<Nothing>>(), (epgWindow as AppResult.Success).data)

        coVerify(exactly = 0) { delegate.refreshPlaylist(any()) }
        coVerify(exactly = 0) { delegate.deletePlaylist(any()) }
        coVerify(exactly = 0) { delegate.setPlaylistEpgSource(any(), any()) }
        coVerify(exactly = 0) { delegate.validatePlaylist(any()) }
        coVerify(exactly = 0) { delegate.getPlaylistEpgWindow(any(), any(), any(), any()) }
    }

    private fun history(id: Long, channelId: Long, playedAt: Long): PlaybackHistoryItem {
        return PlaybackHistoryItem(
            id = id,
            channelId = channelId,
            channelName = "History $channelId",
            playedAt = playedAt
        )
    }

    private fun playlist(id: Long, name: String, channelCount: Int): Playlist {
        return Playlist(
            id = id,
            name = name,
            sourceType = PlaylistSourceType.URL,
            source = "https://example.com/$id.m3u8",
            scheduleHours = 0,
            lastSyncedAt = null,
            channelCount = channelCount,
            isCustom = false
        )
    }

    private fun favoriteSnapshot(channel: Channel): FavoriteChannelEntity {
        return FavoriteChannelEntity(
            logicalKey = ChannelStableIdentity.key(
                tvgId = channel.tvgId,
                name = channel.name,
                streamUrl = channel.streamUrl
            ),
            tvgId = channel.tvgId,
            name = channel.name,
            groupName = channel.group,
            logo = channel.logo,
            preferredStreamUrl = channel.streamUrl,
            preferredPlaylistId = channel.playlistId,
            preferredChannelId = channel.id,
            addedAt = 1,
            updatedAt = 1
        )
    }

    private fun channel(
        id: Long,
        playlistId: Long,
        name: String,
        tvgId: String? = null,
        group: String? = "General",
        health: ChannelHealth = ChannelHealth.AVAILABLE,
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
        orderIndex = id.toInt(),
        isHidden = isHidden
    )
}
