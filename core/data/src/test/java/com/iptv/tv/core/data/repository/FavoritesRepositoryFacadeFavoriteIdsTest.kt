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
class FavoritesRepositoryFacadeFavoriteIdsTest {
    @Test
    fun favoriteIdFlowUsesSnapshotReadWithoutRepresentativeMaterialization() = runTest {
        val delegate = mockk<UnifiedFavoritesRepositoryImpl>()
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()

        every { delegate.observeFavoriteChannelIds() } returns flowOf(linkedSetOf(101L, 202L))
        coEvery { favoriteSnapshotDao.getFavoriteChannels() } returns
            listOf(favorite(202L), favorite(303L))

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
        coVerify(exactly = 1) { favoriteSnapshotDao.getFavoriteChannels() }
        verify(exactly = 0) { delegate.observeFavorites() }
        verify(exactly = 0) { favoriteSnapshotDao.observeFavoriteChannels() }
        verify(exactly = 0) { favoriteSnapshotDao.observeFavoriteVariants() }
        verify(exactly = 0) { favoriteChannelLookupDao.observeChannelTableInvalidation() }
        coVerify(exactly = 0) { favoriteLiveChannelResolver.findMatchingChannels(any()) }
    }

    private fun favorite(preferredChannelId: Long): FavoriteChannelEntity {
        return FavoriteChannelEntity(
            logicalKey = "favorite:$preferredChannelId",
            tvgId = null,
            name = "Favorite $preferredChannelId",
            groupName = null,
            logo = null,
            preferredStreamUrl = "https://example.com/$preferredChannelId.m3u8",
            preferredPlaylistId = preferredChannelId + 1_000,
            preferredChannelId = preferredChannelId,
            addedAt = 1,
            updatedAt = 1
        )
    }
}
