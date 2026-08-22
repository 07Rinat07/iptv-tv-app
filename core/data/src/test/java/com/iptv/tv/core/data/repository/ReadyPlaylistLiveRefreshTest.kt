package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.ReadyPlaylistRefreshDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import io.mockk.Runs
import io.mockk.coAnswers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
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

class ReadyPlaylistLiveRefreshTest {

    @Test
    fun refreshReadyCatalogDownloadsLatestM3uPreservesIdAndRefreshesEpgHeader() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    #EXTM3U url-tvg="https://epg.example/new.xml.gz"
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
            val playlist = readyPlaylist(
                playlistId,
                server.url("/ready.m3u").toString(),
                epgSourceUrl = "https://epg.example/old.xml.gz"
            )
            val fixture = fixture(listOf(playlist), mapOf(playlistId to listOf(existing)))

            val result = fixture.repository.refreshPlaylist(playlistId)

            assertTrue(result is AppResult.Success)
            assertEquals(1, server.requestCount)
            assertEquals("/ready.m3u", server.takeRequest().path)
            coVerify(exactly = 1) {
                fixture.readyPlaylistRefreshDao.applyRefresh(
                    playlistId = playlistId,
                    channels = match { channels ->
                        channels.size == 2 &&
                            channels.first { it.tvgId == "news" }.let { news ->
                                news.id == 41L &&
                                    news.health == ChannelHealth.UNKNOWN.name &&
                                    news.isHidden &&
                                    news.streamUrl.endsWith("/news-new.m3u8")
                            } &&
                            channels.first { it.tvgId == "movies" }.id == 0L
                    },
                    staleChannelIds = emptyList(),
                    epgSourceUrl = "https://epg.example/new.xml.gz",
                    syncedAt = any(),
                    syncLog = match { log ->
                        log.playlistId == playlistId && log.status == "refresh"
                    }
                )
            }
            coVerify(exactly = 0) { fixture.delegate.refreshPlaylist(any()) }
        }
    }

    @Test
    fun refreshReadyCatalogDoesNotMutateSnapshotWhenDownloadFails() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val playlistId = 21L
            val playlist = readyPlaylist(playlistId, server.url("/ready.m3u").toString())
            val fixture = fixture(listOf(playlist))

            val result = fixture.repository.refreshPlaylist(playlistId)

            assertTrue(result is AppResult.Error)
            assertEquals(1, server.requestCount)
            coVerify(exactly = 0) {
                fixture.readyPlaylistRefreshDao.applyRefresh(any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Test
    fun refreshFailureStillReturnsErrorWhenFailureLogCannotBePersisted() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val playlistId = 22L
            val playlist = readyPlaylist(playlistId, server.url("/ready.m3u").toString())
            val fixture = fixture(listOf(playlist))
            coEvery { fixture.syncLogDao.insert(any()) } throws IllegalStateException("database full")

            val result = fixture.repository.refreshPlaylist(playlistId)

            assertTrue(result is AppResult.Error)
            assertTrue((result as AppResult.Error).message.contains("HTTP 503"))
            coVerify(exactly = 0) {
                fixture.readyPlaylistRefreshDao.applyRefresh(any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Test
    fun concurrentRefreshesOfSameReadyPlaylistAreSerializedBeforeSecondDownload() = runTest {
        MockWebServer().use { server ->
            val body = """
                #EXTM3U
                #EXTINF:-1 tvg-id="news",News
                ${server.url("/news.m3u8")}
            """.trimIndent()
            server.enqueue(MockResponse().setBody(body))
            server.enqueue(MockResponse().setBody(body))
            val playlistId = 24L
            val playlist = readyPlaylist(playlistId, server.url("/ready.m3u").toString())
            val fixture = fixture(listOf(playlist))
            val firstWriteStarted = CompletableDeferred<Unit>()
            val releaseFirstWrite = CompletableDeferred<Unit>()
            var writeCount = 0
            coEvery {
                fixture.readyPlaylistRefreshDao.applyRefresh(any(), any(), any(), any(), any(), any())
            } coAnswers {
                writeCount += 1
                if (writeCount == 1) {
                    firstWriteStarted.complete(Unit)
                    releaseFirstWrite.await()
                }
            }

            val first = async { fixture.repository.refreshPlaylist(playlistId) }
            firstWriteStarted.await()
            val second = async { fixture.repository.refreshPlaylist(playlistId) }
            yield()

            assertEquals(1, server.requestCount)

            releaseFirstWrite.complete(Unit)
            assertTrue(first.await() is AppResult.Success)
            assertTrue(second.await() is AppResult.Success)
            assertEquals(2, server.requestCount)
            assertEquals(2, writeCount)
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
            val fixture = fixture(listOf(playlist))
            coEvery { fixture.delegate.refreshPlaylist(playlistId) } returns AppResult.Success(Unit)

            val result = fixture.repository.refreshPlaylist(playlistId)

            assertTrue(result is AppResult.Success)
            assertEquals(0, server.requestCount)
            coVerify(exactly = 1) { fixture.delegate.refreshPlaylist(playlistId) }
            coVerify(exactly = 0) {
                fixture.readyPlaylistRefreshDao.applyRefresh(any(), any(), any(), any(), any(), any())
            }
        }
    }

    @Test
    fun refreshAllRoutesReadyThroughLiveDownloaderAndKeepsOtherSourcesDelegated() = runTest {
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
            val ready = readyPlaylist(31L, server.url("/ready.m3u").toString())
            val manual = readyPlaylist(
                id = 32L,
                source = server.url("/manual.m3u").toString(),
                origin = CatalogOriginKind.USER_IMPORT
            )
            val fixture = fixture(listOf(ready, manual))
            coEvery { fixture.delegate.refreshPlaylist(manual.id) } returns AppResult.Success(Unit)

            val result = fixture.repository.refreshAllPlaylists()

            assertTrue(result is AppResult.Success)
            assertEquals(2, (result as AppResult.Success).data)
            assertEquals(1, server.requestCount)
            assertEquals("/ready.m3u", server.takeRequest().path)
            coVerify(exactly = 1) { fixture.delegate.refreshPlaylist(manual.id) }
            coVerify(exactly = 1) {
                fixture.readyPlaylistRefreshDao.applyRefresh(
                    playlistId = ready.id,
                    channels = any(),
                    staleChannelIds = any(),
                    epgSourceUrl = any(),
                    syncedAt = any(),
                    syncLog = any()
                )
            }
            coVerify(exactly = 0) { fixture.delegate.refreshAllPlaylists() }
        }
    }

    private fun fixture(
        playlists: List<PlaylistEntity>,
        existingByPlaylist: Map<Long, List<ChannelEntity>> = emptyMap()
    ): Fixture {
        val delegate = mockk<PlaylistRepositoryImpl>()
        val playlistDao = mockk<PlaylistDao>()
        val channelDao = mockk<ChannelDao>()
        val readyPlaylistRefreshDao = mockk<ReadyPlaylistRefreshDao>()
        val syncLogDao = mockk<SyncLogDao>(relaxed = true)

        playlists.forEach { playlist ->
            coEvery { playlistDao.findById(playlist.id) } returns playlist
            coEvery { channelDao.getChannels(playlist.id) } returns existingByPlaylist[playlist.id].orEmpty()
        }
        coEvery { playlistDao.getAllIds() } returns playlists.map(PlaylistEntity::id)
        coEvery {
            readyPlaylistRefreshDao.applyRefresh(any(), any(), any(), any(), any(), any())
        } just Runs

        return Fixture(
            repository = ReadyCatalogPlaylistRepository(
                delegate = delegate,
                playlistDao = playlistDao,
                channelDao = channelDao,
                readyPlaylistRefreshDao = readyPlaylistRefreshDao,
                syncLogDao = syncLogDao,
                parser = M3uParser(),
                okHttpClient = OkHttpClient(),
                logoCatalogResolver = LogoCatalogResolver()
            ),
            delegate = delegate,
            readyPlaylistRefreshDao = readyPlaylistRefreshDao,
            syncLogDao = syncLogDao
        )
    }

    private fun readyPlaylist(
        id: Long,
        source: String,
        origin: CatalogOriginKind = CatalogOriginKind.READY_CATALOG,
        epgSourceUrl: String? = null
    ) = PlaylistEntity(
        id = id,
        name = "Ready",
        sourceType = PlaylistSourceType.URL.name,
        source = source,
        epgSourceUrl = epgSourceUrl,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = 1L,
        catalogOrigin = origin.name
    )

    private data class Fixture(
        val repository: ReadyCatalogPlaylistRepository,
        val delegate: PlaylistRepositoryImpl,
        val readyPlaylistRefreshDao: ReadyPlaylistRefreshDao,
        val syncLogDao: SyncLogDao
    )
}
