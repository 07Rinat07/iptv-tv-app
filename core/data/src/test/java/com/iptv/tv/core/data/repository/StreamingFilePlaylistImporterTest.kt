package com.iptv.tv.core.data.repository

import android.content.Context
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
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingFilePlaylistImporterTest {
    @Test
    fun filesystemImportStreamsLargeM3uAndPreservesLocalMetadata() = runTest {
        val channelCount = 8_192
        val path = Files.createTempFile("iptv-large-", ".m3u")
        try {
            Files.newBufferedWriter(path).use { writer ->
                writer.append("#EXTM3U url-tvg=\"https://epg.example/local.xml.gz\"\n")
                repeat(channelCount) { index ->
                    writer.append("#EXTINF:-1 tvg-id=\"file-")
                    writer.append(index.toString())
                    writer.append("\",File Channel ")
                    writer.append(index.toString())
                    writer.append('\n')
                    writer.append("udp://239.2.")
                    writer.append(((index / 255) % 255).toString())
                    writer.append('.')
                    writer.append((index % 255).toString())
                    writer.append(':')
                    writer.append((30_000 + index).toString())
                    writer.append('\n')
                }
            }

            val playlistDao = mockk<PlaylistDao>()
            val channelDao = mockk<ChannelDao>()
            val favoriteDao = mockk<FavoriteDao>()
            val syncLogDao = mockk<SyncLogDao>()
            val logoCatalogResolver = mockk<LogoCatalogResolver>()
            val insertedPlaylist = slot<PlaylistEntity>()
            val insertedChunks = mutableListOf<List<ChannelEntity>>()
            coEvery { playlistDao.insertPlaylist(capture(insertedPlaylist)) } returns 101L
            coEvery { channelDao.insertAll(capture(insertedChunks)) } just Runs
            coEvery { favoriteDao.getFavorites() } returns emptyList()
            coEvery { channelDao.getChannelsLimited(101L, 200) } returns emptyList()
            coEvery { syncLogDao.insert(any()) } just Runs
            every { logoCatalogResolver.resolve(any(), any(), any()) } returns null

            val persistence = StreamingUrlPlaylistImporter(
                playlistDao = playlistDao,
                channelDao = channelDao,
                favoriteDao = favoriteDao,
                syncLogDao = syncLogDao,
                parser = M3uParser(),
                okHttpClient = OkHttpClient(),
                logoCatalogResolver = logoCatalogResolver
            )
            val importer = StreamingFilePlaylistImporter(
                context = mockk<Context>(relaxed = true),
                parser = M3uParser(),
                persistence = persistence
            )

            val result = importer.importFromFile(
                pathOrUri = path.toString(),
                name = "Large local file"
            )

            assertTrue(result is AppResult.Success)
            val report = (result as AppResult.Success).data
            assertEquals(channelCount, report.totalParsed)
            assertEquals(channelCount, report.totalImported)
            assertEquals(channelCount, insertedChunks.sumOf { it.size })
            assertEquals(channelCount - 1, insertedChunks.last().last().orderIndex)
            assertEquals("File Channel ${channelCount - 1}", insertedChunks.last().last().name)
            assertEquals(PlaylistSourceType.FILE.name, insertedPlaylist.captured.sourceType)
            assertEquals(CatalogOriginKind.LOCAL.name, insertedPlaylist.captured.catalogOrigin)
            assertEquals(path.toString(), insertedPlaylist.captured.source)
            assertEquals("https://epg.example/local.xml.gz", insertedPlaylist.captured.epgSourceUrl)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun contentUriRouteUsesContentStreamOpenerInsteadOfFilesystem() {
        var contentSource: String? = null
        val stream = openPlaylistFileInputStream(
            pathOrUri = "content://media/playlists/42",
            contentUriOpener = { source ->
                contentSource = source
                ByteArrayInputStream("#EXTM3U\n".toByteArray(StandardCharsets.UTF_8))
            },
            fileOpener = { error("filesystem opener must not be used for content uri") }
        )

        stream.use { input ->
            assertEquals("#EXTM3U\n", input.bufferedReader().readText())
        }
        assertEquals("content://media/playlists/42", contentSource)
    }

    @Test
    fun missingContentUriStreamPreservesOpenFailure() {
        val failure = runCatching {
            openPlaylistFileInputStream(
                pathOrUri = "content://media/playlists/missing",
                contentUriOpener = { null },
                fileOpener = { error("filesystem opener must not be used for content uri") }
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Cannot open content uri", failure?.message)
    }
}
