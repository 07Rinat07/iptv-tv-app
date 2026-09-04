package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.PlaylistEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistMetadataLookupTest {
    @Test
    fun emptyIdsAvoidDatabaseLookup() = runTest {
        val playlistDao = mockk<PlaylistDao>()

        val result = playlistDao.findPlaylistMapByIds(emptyList())

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { playlistDao.findByIds(any()) }
    }

    @Test
    fun duplicateIdsAreResolvedInOneBatch() = runTest {
        val playlistDao = mockk<PlaylistDao>()
        val first = playlist(1L)
        val second = playlist(2L)
        coEvery { playlistDao.findByIds(listOf(1L, 2L)) } returns listOf(first, second)

        val result = playlistDao.findPlaylistMapByIds(listOf(1L, 2L, 1L))

        assertEquals(mapOf(1L to first, 2L to second), result)
        coVerify(exactly = 1) { playlistDao.findByIds(listOf(1L, 2L)) }
    }

    @Test
    fun idsBeyondSingleSqliteBatchAreChunked() = runTest {
        val playlistDao = mockk<PlaylistDao>()
        val ids = (1L..901L).toList()
        val firstBatch = ids.take(900)
        val secondBatch = ids.drop(900)
        coEvery { playlistDao.findByIds(firstBatch) } returns firstBatch.map(::playlist)
        coEvery { playlistDao.findByIds(secondBatch) } returns secondBatch.map(::playlist)

        val result = playlistDao.findPlaylistMapByIds(ids)

        assertEquals(901, result.size)
        coVerify(exactly = 1) { playlistDao.findByIds(firstBatch) }
        coVerify(exactly = 1) { playlistDao.findByIds(secondBatch) }
    }

    private fun playlist(id: Long): PlaylistEntity = PlaylistEntity(
        id = id,
        name = "Playlist $id",
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
