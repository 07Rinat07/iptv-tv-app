package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.model.FavoritesPortableImportStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesPortableBackupServiceBoundedLookupTest {
    @Test
    fun importResolvesLiveRowsOnlyForDocumentFavoriteKeys() = runTest {
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>(relaxed = true)
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        val first = source(
            logicalKey = "tvg:news.one",
            streamUrl = "https://public.example/news.m3u8"
        )
        val second = source(
            logicalKey = "tvg:sport.one",
            streamUrl = "https://public.example/sport.m3u8"
        )
        val logicalKeys = setOf(first.favorite.logicalKey, second.favorite.logicalKey)
        val content = FavoritesPortableBackupCodec.encode(
            sources = listOf(first, second),
            createdAt = 100L
        ).content

        coEvery { favoriteSnapshotDao.getLegacySeeds() } returns emptyList()
        coEvery { favoriteSnapshotDao.getFavoriteChannels() } returns emptyList()
        coEvery { favoriteLiveChannelResolver.findMatchingChannels(logicalKeys) } returns emptyList()

        val service = FavoritesPortableBackupService(
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            playlistDao = playlistDao,
            favoriteDao = favoriteDao
        )

        val result = service.importPortableBackup(content)

        assertEquals(FavoritesPortableImportStatus.SUCCESS, result.status)
        assertEquals(2, result.importedFavorites)
        coVerify(exactly = 1) { favoriteLiveChannelResolver.findMatchingChannels(logicalKeys) }
    }

    private fun source(
        logicalKey: String,
        streamUrl: String
    ): FavoriteBackupSource {
        val tvgId = logicalKey.removePrefix("tvg:")
        val favorite = FavoriteChannelEntity(
            logicalKey = logicalKey,
            tvgId = tvgId,
            name = tvgId.replace('.', ' '),
            groupName = "Group",
            logo = null,
            preferredStreamUrl = streamUrl,
            preferredPlaylistId = 7L,
            preferredChannelId = 77L,
            addedAt = 1L,
            updatedAt = 2L
        )
        val variant = FavoriteChannelVariantEntity(
            logicalKey = logicalKey,
            variantKey = UnifiedFavoritePersistence.variantKey(streamUrl),
            legacyChannelId = 77L,
            playlistId = 7L,
            playlistName = "Portable source",
            sourceType = "URL",
            catalogOrigin = "USER_IMPORT",
            tvgId = tvgId,
            name = favorite.name,
            groupName = favorite.groupName,
            logo = null,
            streamUrl = streamUrl,
            addedAt = 1L,
            updatedAt = 2L
        )
        return FavoriteBackupSource(favorite = favorite, variants = listOf(variant))
    }
}
