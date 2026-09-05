package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.PlaylistDeleteDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.PlaylistSourceType
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes large physical playlists without materializing their complete channel list in Kotlin.
 *
 * Ready-catalog deletion remains on [ReadyCatalogPlaylistRepository] so its refresh/delete mutex
 * keeps the existing serialization contract. Other physical playlists use a single Room
 * transaction with playlist-scoped SQL subqueries for legacy favorites/history cleanup.
 */
@Singleton
class PlaylistDeleteRepository @Inject constructor(
    private val delegate: ReadyCatalogPlaylistRepository,
    private val playlistDao: PlaylistDao,
    private val playlistDeleteDao: PlaylistDeleteDao,
    private val syncLogDao: SyncLogDao
) : PlaylistRepository by delegate {
    override suspend fun deletePlaylist(playlistId: Long): AppResult<Int> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")

        if (
            playlist.catalogOrigin == CatalogOriginKind.READY_CATALOG.name &&
            playlist.sourceType == PlaylistSourceType.URL.name
        ) {
            return@withContext delegate.deletePlaylist(playlistId)
        }

        val deleted = playlistDeleteDao.deletePlaylist(playlistId)
        if (deleted.removedPlaylists <= 0) {
            return@withContext AppResult.Error("Unable to delete playlist: id=$playlistId")
        }

        syncLogDao.insert(
            SyncLogEntity(
                playlistId = playlistId,
                status = "playlist_deleted",
                message = "Deleted playlist ${playlist.name}, channels=${deleted.removedChannels}",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(deleted.removedChannels)
    }
}
