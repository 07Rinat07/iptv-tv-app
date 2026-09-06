package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.parser.M3uParser
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingUrlFavoriteInheritanceBatchTest {
    @Test
    fun favoriteInheritanceBatchesLegacyLookupsAndScansImportedCatalogByOrderWindow() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="seed",Seed
                    udp://239.1.1.1:1234
                    """.trimIndent()
                )
            )

            val playlistDao = mockk<PlaylistDao>()
            val channelDao = mockk<ChannelDao>()
            val favoriteDao = mockk<FavoriteDao>()
            val syncLogDao = mockk<SyncLogDao>()
            val logoCatalogResolver = mockk<LogoCatalogResolver>()
            val inheritedWrites = mutableListOf<List<FavoriteEntity>>()

            coEvery { playlistDao.insertPlaylist(any()) } returns PLAYLIST_ID
            coEvery { channelDao.insertAll(any()) } just Runs
            coEvery { favoriteDao.getFavorites() } returns (1L..901L).map { channelId ->
                FavoriteEntity(channelId = channelId, addedAt = channelId)
            }
            coEvery { channelDao.findByIds(FIRST_FAVORITE_BATCH) } returns listOf(
                channel(
                    id = 1L,
                    orderIndex = 10,
                    tvgId = "favorite-a",
                    name = "Favorite A",
                    streamUrl = "https://source.example/a"
                )
            )
            coEvery { channelDao.findByIds(SECOND_FAVORITE_BATCH) } returns listOf(
                channel(
                    id = 901L,
                    orderIndex = 20,
                    tvgId = "favorite-b",
                    name = "Favorite B",
                    streamUrl = "https://source.example/b"
                )
            )
            coEvery { channelDao.maxOrderIndex(PLAYLIST_ID) } returns 1800
            coEvery {
                channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, FIRST_ORDER_WINDOW)
            } returns listOf(
                channel(
                    id = INHERITED_A_ID,
                    orderIndex = 0,
                    tvgId = "favorite-a",
                    name = "Favorite A",
                    streamUrl = "https://source.example/a"
                ),
                channel(
                    id = 1002L,
                    orderIndex = 1,
                    tvgId = "other-a",
                    name = "Other A",
                    streamUrl = "https://source.example/other-a"
                )
            )
            coEvery {
                channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, SECOND_ORDER_WINDOW)
            } returns listOf(
                channel(
                    id = 1003L,
                    orderIndex = 900,
                    tvgId = "other-b",
                    name = "Other B",
                    streamUrl = "https://source.example/other-b"
                )
            )
            coEvery {
                channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, THIRD_ORDER_WINDOW)
            } returns listOf(
                channel(
                    id = INHERITED_B_ID,
                    orderIndex = 1800,
                    tvgId = "favorite-b",
                    name = "Favorite B",
                    streamUrl = "https://source.example/b"
                )
            )
            coEvery { favoriteDao.upsertAll(capture(inheritedWrites)) } just Runs
            coEvery { channelDao.getChannelsLimited(PLAYLIST_ID, 200) } returns emptyList()
            coEvery { syncLogDao.insert(any()) } just Runs
            every { logoCatalogResolver.resolve(any(), any(), any()) } returns null

            val importer = StreamingUrlPlaylistImporter(
                playlistDao = playlistDao,
                channelDao = channelDao,
                favoriteDao = favoriteDao,
                syncLogDao = syncLogDao,
                parser = M3uParser(),
                okHttpClient = OkHttpClient(),
                logoCatalogResolver = logoCatalogResolver
            )

            val result = importer.importFromUrl(
                url = server.url("/favorites.m3u").toString(),
                name = "Favorite inheritance",
                catalogOrigin = CatalogOriginKind.USER_IMPORT
            )

            assertTrue(result is AppResult.Success)
            assertEquals(
                listOf(INHERITED_A_ID, INHERITED_B_ID),
                inheritedWrites.flatten().map { favorite -> favorite.channelId }
            )
            assertEquals(listOf(1, 1), inheritedWrites.map { batch -> batch.size })

            coVerify(exactly = 1) { channelDao.findByIds(FIRST_FAVORITE_BATCH) }
            coVerify(exactly = 1) { channelDao.findByIds(SECOND_FAVORITE_BATCH) }
            coVerify(exactly = 1) { channelDao.maxOrderIndex(PLAYLIST_ID) }
            coVerify(exactly = 1) {
                channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, FIRST_ORDER_WINDOW)
            }
            coVerify(exactly = 1) {
                channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, SECOND_ORDER_WINDOW)
            }
            coVerify(exactly = 1) {
                channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, THIRD_ORDER_WINDOW)
            }
            coVerify(exactly = 0) { channelDao.getChannels(PLAYLIST_ID) }
            coVerify(exactly = 2) { favoriteDao.upsertAll(any()) }
        }
    }

    private fun channel(
        id: Long,
        orderIndex: Int,
        tvgId: String?,
        name: String,
        streamUrl: String
    ) = ChannelEntity(
        id = id,
        playlistId = PLAYLIST_ID,
        tvgId = tvgId,
        name = name,
        groupName = "Group",
        logo = null,
        streamUrl = streamUrl,
        health = ChannelHealth.UNKNOWN.name,
        orderIndex = orderIndex,
        isHidden = false
    )

    private companion object {
        const val PLAYLIST_ID = 77L
        const val INHERITED_A_ID = 1001L
        const val INHERITED_B_ID = 1004L

        val FIRST_FAVORITE_BATCH = (1L..900L).toList()
        val SECOND_FAVORITE_BATCH = listOf(901L)
        val FIRST_ORDER_WINDOW = (0..899).toList()
        val SECOND_ORDER_WINDOW = (900..1799).toList()
        val THIRD_ORDER_WINDOW = listOf(1800)
    }
}
