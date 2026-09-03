package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTvheadendPlaylistImporterTest {
    @Test
    fun tvheadendImportStreamsLargeM3uPreservesProviderMetadataAndBasicAuth() = runTest {
        MockWebServer().use { server ->
            val channelCount = 4_096
            val body = buildString {
                append("#EXTM3U url-tvg=\"https://epg.example/tvheadend.xml.gz\"\n")
                repeat(channelCount) { index ->
                    append("#EXTINF:-1 tvg-id=\"tvh-")
                    append(index)
                    append("\",TVH Channel ")
                    append(index)
                    append('\n')
                    append("udp://239.1.")
                    append((index / 255) % 255)
                    append('.')
                    append(index % 255)
                    append(':')
                    append(20_000 + index)
                    append('\n')
                }
            }
            server.enqueue(MockResponse().setBody(body))

            val playlistDao = mockk<PlaylistDao>()
            val channelDao = mockk<ChannelDao>()
            val favoriteDao = mockk<FavoriteDao>()
            val syncLogDao = mockk<SyncLogDao>()
            val logoCatalogResolver = mockk<LogoCatalogResolver>()
            val insertedPlaylist = slot<PlaylistEntity>()
            val insertedChunks = mutableListOf<List<ChannelEntity>>()
            coEvery { playlistDao.insertPlaylist(capture(insertedPlaylist)) } returns 91L
            coEvery { channelDao.insertAll(capture(insertedChunks)) } just Runs
            coEvery { favoriteDao.getFavorites() } returns emptyList()
            coEvery { channelDao.getChannelsLimited(91L, 200) } returns emptyList()
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

            val result = importer.importFromTvheadend(
                baseUrl = server.url("/api/").toString(),
                username = " alice ",
                password = " secret ",
                name = "TVH"
            )

            assertTrue(result is AppResult.Success)
            val report = (result as AppResult.Success).data
            assertEquals(channelCount, report.totalParsed)
            assertEquals(channelCount, report.totalImported)
            assertEquals(channelCount, insertedChunks.sumOf { it.size })
            assertEquals(channelCount - 1, insertedChunks.last().last().orderIndex)
            assertEquals("TVH Channel ${channelCount - 1}", insertedChunks.last().last().name)

            val expectedSource = server.url("/api/playlist/channels.m3u").toString()
            assertEquals(PlaylistSourceType.TVHEADEND.name, insertedPlaylist.captured.sourceType)
            assertEquals(CatalogOriginKind.PROVIDER.name, insertedPlaylist.captured.catalogOrigin)
            assertEquals(expectedSource, insertedPlaylist.captured.source)
            assertEquals("https://epg.example/tvheadend.xml.gz", insertedPlaylist.captured.epgSourceUrl)

            assertEquals(1, server.requestCount)
            val request = server.takeRequest()
            assertEquals("/api/playlist/channels.m3u", request.path)
            assertEquals(Credentials.basic("alice", "secret"), request.getHeader("Authorization"))
        }
    }

    @Test
    fun directTvheadendPlaylistUrlIsNotRewritten() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    #EXTM3U
                    #EXTINF:-1 tvg-id="one",One
                    udp://239.9.9.9:1234
                    """.trimIndent()
                )
            )
            val playlistDao = mockk<PlaylistDao>()
            val channelDao = mockk<ChannelDao>()
            val favoriteDao = mockk<FavoriteDao>()
            val syncLogDao = mockk<SyncLogDao>()
            val insertedPlaylist = slot<PlaylistEntity>()
            coEvery { playlistDao.insertPlaylist(capture(insertedPlaylist)) } returns 92L
            coEvery { channelDao.insertAll(any()) } just Runs
            coEvery { favoriteDao.getFavorites() } returns emptyList()
            coEvery { channelDao.getChannelsLimited(92L, 200) } returns emptyList()
            coEvery { syncLogDao.insert(any()) } just Runs

            val importer = StreamingUrlPlaylistImporter(
                playlistDao = playlistDao,
                channelDao = channelDao,
                favoriteDao = favoriteDao,
                syncLogDao = syncLogDao,
                parser = M3uParser(),
                okHttpClient = OkHttpClient(),
                logoCatalogResolver = mockk(relaxed = true)
            )
            val directUrl = server.url("/custom/live.m3u").toString()

            val result = importer.importFromTvheadend(
                baseUrl = directUrl,
                username = null,
                password = null,
                name = "Direct TVH"
            )

            assertTrue(result is AppResult.Success)
            assertEquals(directUrl, insertedPlaylist.captured.source)
            assertEquals("/custom/live.m3u", server.takeRequest().path)
        }
    }

    @Test
    fun blankTvheadendUrlPreservesDiagnostic() = runTest {
        val importer = StreamingUrlPlaylistImporter(
            playlistDao = mockk(),
            channelDao = mockk(),
            favoriteDao = mockk(),
            syncLogDao = mockk(),
            parser = M3uParser(),
            okHttpClient = OkHttpClient(),
            logoCatalogResolver = mockk()
        )

        val result = importer.importFromTvheadend(
            baseUrl = "   ",
            username = null,
            password = null,
            name = "TVH"
        )

        assertTrue(result is AppResult.Error)
        assertEquals("Tvheadend URL is empty", (result as AppResult.Error).message)
    }
}
