package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.PlaylistDeleteDao
import com.iptv.tv.core.database.dao.PlaylistDeleteResult
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.PlaylistEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistDeleteRepositoryTest {
    @Test
    fun physicalDeleteUsesPlaylistScopedTransaction() = runTest {
        val delegate = mockk<ReadyCatalogPlaylistRepository>()
        val playlistDao = mockk<PlaylistDao>()
        val playlistDeleteDao = mockk<PlaylistDeleteDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val repository = PlaylistDeleteRepository(
            delegate = delegate,
            playlistDao = playlistDao,
            playlistDeleteDao = playlistDeleteDao,
            syncLogDao = syncLogDao
        )

        coEvery { playlistDao.findById(7L) } returns playlist(
            id = 7L,
            sourceType = "URL",
            catalogOrigin = "USER_IMPORT"
        )
        coEvery { playlistDeleteDao.deletePlaylist(7L) } returns PlaylistDeleteResult(
            removedChannels = 1_500,
            removedPlaylists = 1
        )
        coEvery { syncLogDao.insert(any()) } returns Unit

        val result = repository.deletePlaylist(7L)

        assertTrue(result is AppResult.Success)
        assertEquals(1_500, (result as AppResult.Success).data)
        coVerify(exactly = 1) { playlistDeleteDao.deletePlaylist(7L) }
        coVerify(exactly = 0) { delegate.deletePlaylist(any()) }
        coVerify(exactly = 1) {
            syncLogDao.insert(match { it.playlistId == 7L && it.status == "playlist_deleted" })
        }
    }

    @Test
    fun readyCatalogDeleteKeepsSerializedDelegatePath() = runTest {
        val delegate = mockk<ReadyCatalogPlaylistRepository>()
        val playlistDao = mockk<PlaylistDao>()
        val playlistDeleteDao = mockk<PlaylistDeleteDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val repository = PlaylistDeleteRepository(
            delegate = delegate,
            playlistDao = playlistDao,
            playlistDeleteDao = playlistDeleteDao,
            syncLogDao = syncLogDao
        )

        coEvery { playlistDao.findById(9L) } returns playlist(
            id = 9L,
            sourceType = "URL",
            catalogOrigin = "READY_CATALOG"
        )
        coEvery { delegate.deletePlaylist(9L) } returns AppResult.Success(42)

        val result = repository.deletePlaylist(9L)

        assertTrue(result is AppResult.Success)
        assertEquals(42, (result as AppResult.Success).data)
        coVerify(exactly = 1) { delegate.deletePlaylist(9L) }
        coVerify(exactly = 0) { playlistDeleteDao.deletePlaylist(any()) }
        coVerify(exactly = 0) { syncLogDao.insert(any()) }
    }

    private fun playlist(
        id: Long,
        sourceType: String,
        catalogOrigin: String
    ) = PlaylistEntity(
        id = id,
        name = "Playlist $id",
        sourceType = sourceType,
        source = "https://example.com/$id.m3u",
        epgSourceUrl = null,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = 1L,
        catalogOrigin = catalogOrigin
    )
}
