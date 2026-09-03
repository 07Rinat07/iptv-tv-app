package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.parser.M3uParser
import io.mockk.Runs
import io.mockk.coEvery
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

class StreamingUrlPlaylistImporterTest {
    @Test
    fun genericUrlImportStreamsTenThousandChannelsAndPersistsFinalRow() = runTest {
        MockWebServer().use { server ->
            val channelCount = 10_000
            val body = buildString {
                append("#EXTM3U url-tvg=\"https://epg.example/guide.xml.gz\"\n")
                repeat(channelCount) { index ->
                    append("#EXTINF:-1 tvg-id=\"id-")
                    append(index)
                    append("\" tvg-logo=\"https://logos.example/")
                    append(index)
                    append(".png\",Channel ")
                    append(index)
                    append('\n')
                    append("udp://239.0.0.")
                    append(index % 255)
                    append(':')
                    append(10_000 + index)
                    append('\n')
                }
            }
            server.enqueue(MockResponse().setBody(body))

            val playlistDao = mockk<PlaylistDao>()
            val channelDao = mockk<ChannelDao>()
            val favoriteDao = mockk<FavoriteDao>()
            val syncLogDao = mockk<SyncLogDao>()
            val logoCatalogResolver = mockk<LogoCatalogResolver>()
            val insertedChunks = mutableListOf<List<ChannelEntity>>()
            coEvery { playlistDao.insertPlaylist(any()) } returns 77L
            coEvery { channelDao.insertAll(capture(insertedChunks)) } just Runs
            coEvery { channelDao.getChannels(77L) } returns emptyList()
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
                url = server.url("/large.m3u").toString(),
                name = "Large URL",
                catalogOrigin = CatalogOriginKind.USER_IMPORT
            )

            assertTrue(result is AppResult.Success)
            val report = (result as AppResult.Success).data
            assertEquals(channelCount, report.totalParsed)
            assertEquals(channelCount, report.totalImported)
            assertEquals(0, report.removedDuplicates)
            assertEquals(channelCount, insertedChunks.sumOf { it.size })
            val last = insertedChunks.last().last()
            assertEquals(channelCount - 1, last.orderIndex)
            assertEquals("Channel ${channelCount - 1}", last.name)
            assertEquals(1, server.requestCount)
            assertEquals("/large.m3u", server.takeRequest().path)
        }
    }

    @Test
    fun emptyHttpBodyPreservesImportDiagnostic() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(""))
            val playlistDao = mockk<PlaylistDao>()
            val channelDao = mockk<ChannelDao>()
            val favoriteDao = mockk<FavoriteDao>()
            val syncLogDao = mockk<SyncLogDao>()
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

            val result = importer.importFromUrl(
                url = server.url("/empty.m3u").toString(),
                name = "Empty",
                catalogOrigin = CatalogOriginKind.USER_IMPORT
            )

            assertTrue(result is AppResult.Error)
            assertEquals("Playlist content is empty", (result as AppResult.Error).message)
        }
    }
}
