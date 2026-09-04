package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesRepositoryFacadeObserveFavoritesTest {
    @Test
    fun favoriteBrowseUsesScalarMigrationBarrierWithoutDelegateRepresentativeScan() = runTest {
        val delegate = mockk<UnifiedFavoritesRepositoryImpl>()
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val favorite = FavoriteChannelEntity(
            logicalKey = "tvg:news.one",
            tvgId = "news.one",
            name = "News One",
            groupName = "News",
            logo = null,
            preferredStreamUrl = "https://example.com/news.m3u8",
            preferredPlaylistId = 7,
            preferredChannelId = 77,
            addedAt = 1,
            updatedAt = 2
        )

        every { delegate.observeFavoriteCount() } returns flowOf(1)
        every { favoriteSnapshotDao.observeFavoriteChannels() } returns flowOf(listOf(favorite))
        every { favoriteSnapshotDao.observeFavoriteVariants() } returns flowOf(emptyList())
        every { favoriteChannelLookupDao.observeChannelTableInvalidation() } returns flowOf(1)
        coEvery { favoriteLiveChannelResolver.findMatchingChannels(any()) } returns emptyList()

        val repository = FavoritesRepositoryFacade(
            delegate = delegate,
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            portableBackupService = mockk(),
            shareableExportService = mockk(),
            sourceVariantService = mockk()
        )

        val result = repository.observeFavorites().first()

        assertEquals(listOf(77L), result.map { it.id })
        assertEquals("News One", result.single().name)
        verify(exactly = 1) { delegate.observeFavoriteCount() }
        verify(exactly = 0) { delegate.observeFavorites() }
        verify(exactly = 1) { favoriteSnapshotDao.observeFavoriteChannels() }
        verify(exactly = 1) { favoriteSnapshotDao.observeFavoriteVariants() }
        verify(exactly = 1) { favoriteChannelLookupDao.observeChannelTableInvalidation() }
        coVerify(exactly = 1) { favoriteLiveChannelResolver.findMatchingChannels(setOf(favorite.logicalKey)) }
    }
}
