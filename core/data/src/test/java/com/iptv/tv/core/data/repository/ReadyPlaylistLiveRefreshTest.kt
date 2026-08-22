package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyPlaylistLiveRefreshTest {

    @Test
    fun refreshReadyCatalogDownloadsLatestM3uAndPreservesMatchingChannelId() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="news",News Updated
                    ${server.url("/news-new.m3u8")}
                    #EXTINF:-1 tvg-id="movies",Movies
                    ${server.url("/movies.m3u8")}
                    """.trimIndent()
                )
            )
            val playlistId = 17L
            val existing = ChannelEntity(
                id = 41L,
                playlistId = playlistId,
                tvgId = "news",
                name = "News",
                groupName = null,
                logo = null,
                streamUrl = server.url("/news-old.m3u8").toString(),
                health = ChannelHealth.AVAILABLE.name,
                orderIndex = 0,
                isHidden = true
            )
            val playlist = readyPlaylist(playlistId, server.url("/ready.m3u").toString())
            val fixture = fixture(playlist, listOf(existing))
            val insertedBatches = mutableListOf<List<ChannelEntity>>()
            coEvery { fixture.channelDao.insertAll(capture(insertedBatches)) } just Runs

            val result = fixture.repository.refreshPlaylist(playlistId)

            assertTrue(result is AppResult.Success)
            assertEquals(1, server.requestCount)
            assertEquals("/ready.m3u", server.takeRequest().path)
            val inserted = insertedBatches.flatten()
            assertEquals(2, inserted.size)
            val news = inserted.first { it.tvgId == "news" }
            val movies = inserted.first { it.tvgId == "movies" }
            assertEquals(41L, news.id)
            assertEquals(ChannelHealth.AVAILABLE.name, news.health)
            assertTrue(news.isHidden)
            assertEquals(0L, movies.id)
            coVerify(exactly = 1) { fixture.playlistDao.updateLastSynced(playlistId, any()) }
            coVerify(exactly = 0) { fixture.delegate.refreshPlaylist(any()) }
        }
    }

    @Test
    fun refreshReadyCatalogDoesNotMutateChannelsWhenDownloadFails() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val playlistId = 21L
            val playlist = readyPlaylist(playlistId, server.url("/ready.m3u").toString())
            val fixture = fixture(playlist, emptyList())

            val result = fixture.repository.refreshPlaylist(playlistId)

            assertTrue(result is AppResult.Error)
            assertEquals(1, server.requestCount)
            coVerify(exactly = 0) { fixture.channelDao.insertAll(any()) }
            coVerify(exactly = 0) { fixture.channelDao.deleteByIds(any()) }
            coVerify(exactly = 0) { fixture.playlistDao.updateLastSynced(any(), any()) }
        }
    }

    @Test
    fun refreshNonReadyPlaylistDelegatesWithoutDownloading() = runTest {
        MockWebServer().use { server ->
            val playlistId = 23L
            val playlist = readyPlaylist(
                id = playlistId,
                source = server.url("/manual.m3u").toString(),
                origin = CatalogOriginKind.USER_IMPORT
            )
            val fixture = fixture(playlist, emptyList())
            coEvery { fixture.delegate.refreshPlaylist(playlistId) } returns AppResult.Success(Unit)

            val result = fixture.repository.refreshPlaylist(playlistId)

            assertTrue(result is AppResult.Success)
            assertEquals(0, server.requestCount)
            coVerify(exactly = 1) { fixture.delegate.refreshPlaylist(playlistId) }
        }
    }

    private fun fixture(
        playlist: PlaylistEntity,
        existing: List<ChannelEntity>
    ): Fixture {
        val delegate = mockk<PlaylistRepositoryImpl>()
        val playlistDao = mockk<PlaylistDao>()
        val channelDao = mockk<ChannelDao>()
        val syncLogDao = mockk<SyncLogDao>(relaxed = true)

        coEvery { playlistDao.findById(playlist.id) } returns playlist
        coEvery { playlistDao.updateLastSynced(any(), any()) } just Runs
        coEvery { channelDao.getChannels(playlist.id) } returns existing
        coEvery { channelDao.insertAll(any()) } just Runs
        coEvery { channelDao.deleteByIds(any()) } returns 0

        return Fixture(
            repository = ReadyCatalogPlaylistRepository(
                delegate = delegate,
                playlistDao = playlistDao,
                channelDao = channelDao,
                syncLogDao = syncLogDao,
                parser = M3uParser(),
                okHttpClient = OkHttpClient(),
                logoCatalogResolver = LogoCatalogResolver()
            ),
            delegate = delegate,
            playlistDao = playlistDao,
            channelDao = channelDao
        )
    }

    private fun readyPlaylist(
        id: Long,
        source: String,
        origin: CatalogOriginKind = CatalogOriginKind.READY_CATALOG
    ) = PlaylistEntity(
        id = id,
        name = "Ready",
        sourceType = PlaylistSourceType.URL.name,
        source = source,
        epgSourceUrl = null,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = 1L,
        catalogOrigin = origin.name
    )

    private data class Fixture(
        val repository: ReadyCatalogPlaylistRepository,
        val delegate: PlaylistRepositoryImpl,
        val playlistDao: PlaylistDao,
        val channelDao: ChannelDao
    )
}
