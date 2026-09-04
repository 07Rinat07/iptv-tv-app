package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelIdentityRow
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UnifiedFavoritesRepositoryTogglePlaylistMetadataBatchTest {
    @Test
    fun toggleFavoriteBatchesPlaylistMetadataAndPreservesWrites() = runTest {
        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>(relaxed = true)
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val firstChannel = channel(
            id = 101L,
            playlistId = 7L,
            streamUrl = "https://one.example/live.m3u8"
        )
        val secondChannel = channel(
            id = 102L,
            playlistId = 8L,
            streamUrl = "https://two.example/live.m3u8"
        )
        val logicalKey = UnifiedFavoritePersistence.logicalKey(firstChannel)

        coEvery { favoriteSnapshotDao.getLegacySeeds() } returns emptyList()
        coEvery { favoriteChannelLookupDao.findChannelById(101L) } returns firstChannel
        coEvery { favoriteSnapshotDao.findFavorite(logicalKey) } returns null
        coEvery {
            favoriteChannelLookupDao.getChannelIdentityPage(afterId = 0L, limit = 512)
        } returns listOf(
            identityRow(firstChannel),
            identityRow(secondChannel)
        )
        coEvery {
            favoriteChannelLookupDao.findChannelsByIds(listOf(101L, 102L))
        } returns listOf(firstChannel, secondChannel)
        coEvery { playlistDao.findByIds(listOf(7L, 8L)) } returns listOf(
            playlist(id = 7L, name = "Playlist 7"),
            playlist(id = 8L, name = "Playlist 8")
        )

        val repository = UnifiedFavoritesRepositoryImpl(
            favoriteDao = favoriteDao,
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            playlistDao = playlistDao
        )

        repository.toggleFavorite(101L)

        coVerify(exactly = 1) { playlistDao.findByIds(listOf(7L, 8L)) }
        coVerify(exactly = 0) { playlistDao.findById(any()) }
        coVerify(exactly = 1) {
            favoriteSnapshotDao.upsertFavorite(
                match { favorite ->
                    favorite.logicalKey == logicalKey &&
                        favorite.preferredChannelId == 101L &&
                        favorite.preferredPlaylistId == 7L &&
                        favorite.preferredStreamUrl == firstChannel.streamUrl
                }
            )
        }
        coVerify(exactly = 1) {
            favoriteSnapshotDao.upsertVariants(
                match { variants ->
                    variants.size == 2 &&
                        variants.associate { variant -> variant.playlistId to variant.playlistName } ==
                        mapOf(7L to "Playlist 7", 8L to "Playlist 8") &&
                        variants.map { variant -> variant.legacyChannelId }.toSet() == setOf(101L, 102L)
                }
            )
        }
        coVerify(exactly = 1) {
            favoriteDao.upsertAll(
                match { favorites ->
                    favorites.map { favorite -> favorite.channelId }.toSet() == setOf(101L, 102L) &&
                        favorites.map { favorite -> favorite.addedAt }.distinct().size == 1
                }
            )
        }
    }

    private fun channel(id: Long, playlistId: Long, streamUrl: String): ChannelEntity =
        ChannelEntity(
            id = id,
            playlistId = playlistId,
            tvgId = "news.one",
            name = "News One",
            groupName = "News",
            logo = null,
            streamUrl = streamUrl,
            health = "AVAILABLE",
            orderIndex = 0,
            isHidden = false
        )

    private fun identityRow(channel: ChannelEntity): FavoriteChannelIdentityRow =
        FavoriteChannelIdentityRow(
            id = channel.id,
            tvgId = channel.tvgId,
            name = channel.name,
            streamUrl = channel.streamUrl
        )

    private fun playlist(id: Long, name: String): PlaylistEntity = PlaylistEntity(
        id = id,
        name = name,
        sourceType = "URL",
        source = "https://playlist.example/$id.m3u8",
        epgSourceUrl = null,
        scheduleHours = 0,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = id,
        catalogOrigin = "USER_IMPORT"
    )
}
