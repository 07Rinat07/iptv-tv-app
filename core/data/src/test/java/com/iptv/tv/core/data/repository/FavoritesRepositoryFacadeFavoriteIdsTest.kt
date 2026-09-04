package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
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
class FavoritesRepositoryFacadeFavoriteIdsTest {
    @Test
    fun favoriteIdFlowUsesNarrowPreferredIdsWithoutRepresentativeMaterialization() = runTest {
        val delegate = mockk<UnifiedFavoritesRepositoryImpl>()
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()

        every { delegate.observeFavoriteChannelIds() } returns flowOf(linkedSetOf(101L, 202L))
        every { favoriteSnapshotDao.observeFavoritePreferredChannelIds() } returns
            flowOf(listOf(202L, 303L))

        val repository = FavoritesRepositoryFacade(
            delegate = delegate,
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            portableBackupService = mockk(),
            shareableExportService = mockk(),
            sourceVariantService = mockk()
        )

        assertEquals(
            linkedSetOf(101L, 202L, 303L),
            repository.observeFavoriteChannelIds().first()
        )

        verify(exactly = 1) { delegate.observeFavoriteChannelIds() }
        verify(exactly = 1) { favoriteSnapshotDao.observeFavoritePreferredChannelIds() }
        verify(exactly = 0) { delegate.observeFavorites() }
        verify(exactly = 0) { favoriteSnapshotDao.observeFavoriteChannels() }
        verify(exactly = 0) { favoriteSnapshotDao.observeFavoriteVariants() }
        verify(exactly = 0) { favoriteChannelLookupDao.observeChannelTableInvalidation() }
        coVerify(exactly = 0) { favoriteLiveChannelResolver.findMatchingChannels(any()) }
    }
}
