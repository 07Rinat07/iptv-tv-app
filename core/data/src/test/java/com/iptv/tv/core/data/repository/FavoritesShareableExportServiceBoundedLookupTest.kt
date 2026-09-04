package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.model.FavoritesPortableExport
import com.iptv.tv.core.model.FavoritesShareableExportFormat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesShareableExportServiceBoundedLookupTest {
    @Test
    fun exportResolvesLiveRowsForFavoriteKeysWithoutFullCatalogRead() = runTest {
        val portableBackupService = mockk<FavoritesPortableBackupService>()
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>()
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>()
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val playlistDao = mockk<PlaylistDao>()
        val first = favorite(
            logicalKey = "tvg:news.one",
            preferredUrl = "https://public.example/news.m3u8",
            preferredChannelId = 77L
        )
        val second = favorite(
            logicalKey = "tvg:sport.one",
            preferredUrl = "https://public.example/sport.m3u8",
            preferredChannelId = 88L
        )
        val logicalKeys = setOf(first.logicalKey, second.logicalKey)

        coEvery { portableBackupService.exportPortableBackup() } returns FavoritesPortableExport(
            content = "",
            favoriteCount = 2,
            variantCount = 2,
            redactedVariantCount = 0
        )
        coEvery { favoriteSnapshotDao.getFavoriteChannels() } returns listOf(first, second)
        coEvery { favoriteSnapshotDao.getVariants(first.logicalKey) } returns emptyList()
        coEvery { favoriteSnapshotDao.getVariants(second.logicalKey) } returns emptyList()
        coEvery { favoriteLiveChannelResolver.findMatchingChannels(logicalKeys) } returns emptyList()

        val service = FavoritesShareableExportService(
            portableBackupService = portableBackupService,
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            playlistDao = playlistDao
        )

        val result = service.export(FavoritesShareableExportFormat.M3U8)

        assertEquals(2, result.favoriteCount)
        assertEquals(2, result.safeUrlCount)
        assertTrue(result.content.contains(first.preferredStreamUrl))
        assertTrue(result.content.contains(second.preferredStreamUrl))
        coVerify(exactly = 1) { favoriteLiveChannelResolver.findMatchingChannels(logicalKeys) }
        coVerify(exactly = 0) { favoriteChannelLookupDao.getAllChannels() }
    }

    private fun favorite(
        logicalKey: String,
        preferredUrl: String,
        preferredChannelId: Long
    ): FavoriteChannelEntity = FavoriteChannelEntity(
        logicalKey = logicalKey,
        tvgId = logicalKey.removePrefix("tvg:"),
        name = logicalKey.removePrefix("tvg:").replace('.', ' '),
        groupName = "Group",
        logo = null,
        preferredStreamUrl = preferredUrl,
        preferredPlaylistId = 7L,
        preferredChannelId = preferredChannelId,
        addedAt = 1L,
        updatedAt = 2L
    )
}
