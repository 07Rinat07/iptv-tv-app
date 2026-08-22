package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.ReadyPlaylistRefreshDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyPlaylistDeleteSerializationTest {

    @Test
    fun readyDeleteWaitsUntilInFlightRefreshFinishesItsAtomicWrite() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="news",News
                    ${server.url("/news.m3u8")}
                    """.trimIndent()
                )
            )
            val playlistId = 91L
            val playlist = readyPlaylist(playlistId, server.url("/ready.m3u").toString())
            val delegate = mockk<PlaylistRepositoryImpl>()
            val playlistDao = mockk<PlaylistDao>()
            val channelDao = mockk<ChannelDao>()
            val refreshDao = mockk<ReadyPlaylistRefreshDao>()
            val syncLogDao = mockk<SyncLogDao>(relaxed = true)
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()

            coEvery { playlistDao.findById(playlistId) } returns playlist
            coEvery { channelDao.getChannels(playlistId) } returns emptyList()
            coEvery {
                refreshDao.applyRefresh(any(), any(), any(), any(), any(), any())
            } coAnswers {
                writeStarted.complete(Unit)
                releaseWrite.await()
            }
            coEvery { delegate.deletePlaylist(playlistId) } returns AppResult.Success(1)

            val repository = ReadyCatalogPlaylistRepository(
                delegate = delegate,
                playlistDao = playlistDao,
                channelDao = channelDao,
                readyPlaylistRefreshDao = refreshDao,
                syncLogDao = syncLogDao,
                parser = M3uParser(),
                okHttpClient = OkHttpClient(),
                logoCatalogResolver = LogoCatalogResolver()
            )

            val refresh = async { repository.refreshPlaylist(playlistId) }
            writeStarted.await()
            val delete = async { repository.deletePlaylist(playlistId) }
            yield()

            coVerify(exactly = 0) { delegate.deletePlaylist(playlistId) }

            releaseWrite.complete(Unit)
            assertTrue(refresh.await() is AppResult.Success)
            val deleteResult = delete.await()
            assertTrue(deleteResult is AppResult.Success)
            assertEquals(1, (deleteResult as AppResult.Success).data)
            coVerify(exactly = 1) { delegate.deletePlaylist(playlistId) }
        }
    }

    private fun readyPlaylist(id: Long, source: String) = PlaylistEntity(
        id = id,
        name = "Ready",
        sourceType = PlaylistSourceType.URL.name,
        source = source,
        epgSourceUrl = null,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = 1L,
        catalogOrigin = CatalogOriginKind.READY_CATALOG.name
    )
}
