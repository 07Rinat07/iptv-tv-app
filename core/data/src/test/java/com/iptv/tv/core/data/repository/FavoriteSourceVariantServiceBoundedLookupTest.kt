package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteSourceVariantServiceBoundedLookupTest {
    @Test
    fun resolvePlaybackContextUsesBoundedLiveResolverWithoutFullCatalogRead() = runTest {
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val favoriteDao = mockk<FavoriteDao>()
        val playlistDao = mockk<PlaylistDao>()
        val favorite = FavoriteChannelEntity(
            logicalKey = "tvg:news.one",
            tvgId = "news.one",
            name = "News One",
            groupName = "News",
            logo = null,
            preferredStreamUrl = "https://snapshot.example/news.m3u8",
            preferredPlaylistId = 7,
            preferredChannelId = 77,
            addedAt = 1,
            updatedAt = 2
        )

        coEvery { favoriteChannelLookupDao.findChannelById(77L) } returns null
        coEvery { favoriteSnapshotDao.findFavoriteByPreferredChannelId(77L) } returns favorite
        coEvery {
            favoriteLiveChannelResolver.findMatchingChannels(setOf(favorite.logicalKey))
        } returns emptyList()
        coEvery { favoriteSnapshotDao.getVariants(favorite.logicalKey) } returns emptyList()

        val service = FavoriteSourceVariantService(
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            favoriteDao = favoriteDao,
            playlistDao = playlistDao
        )

        val result = service.resolvePlaybackContext(77L)

        assertNotNull(result)
        assertEquals(77L, result?.channel?.id)
        assertEquals(favorite.preferredStreamUrl, result?.channel?.streamUrl)
        coVerify(exactly = 1) {
            favoriteLiveChannelResolver.findMatchingChannels(setOf(favorite.logicalKey))
        }
        coVerify(exactly = 0) { favoriteChannelLookupDao.getAllChannels() }
    }
}
