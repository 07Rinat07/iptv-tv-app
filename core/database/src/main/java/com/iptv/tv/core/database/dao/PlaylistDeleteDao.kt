package com.iptv.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

data class PlaylistDeleteResult(
    val removedChannels: Int,
    val removedPlaylists: Int
)

/**
 * Atomic delete boundary for one physical playlist.
 *
 * Legacy favorites and history reference channel ids, but loading every channel id into Kotlin
 * creates O(n) allocations and can exceed SQLite bind limits for large catalogs. Delete those
 * dependent rows with playlist-scoped subqueries while the channel rows still exist, then remove
 * channels and the playlist in the same transaction.
 *
 * Durable Favorites snapshots/variants are intentionally untouched: they are designed to survive
 * removal of the original physical playlist.
 */
@Dao
abstract class PlaylistDeleteDao {
    @Query(
        "DELETE FROM favorites WHERE channelId IN (" +
            "SELECT id FROM channels WHERE playlistId = :playlistId)"
    )
    protected abstract suspend fun deleteLegacyFavoritesForPlaylist(playlistId: Long): Int

    @Query(
        "DELETE FROM history WHERE channelId IN (" +
            "SELECT id FROM channels WHERE playlistId = :playlistId)"
    )
    protected abstract suspend fun deleteHistoryForPlaylist(playlistId: Long): Int

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    protected abstract suspend fun deleteChannelsForPlaylist(playlistId: Long): Int

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    protected abstract suspend fun deletePlaylistRow(playlistId: Long): Int

    @Transaction
    open suspend fun deletePlaylist(playlistId: Long): PlaylistDeleteResult {
        deleteLegacyFavoritesForPlaylist(playlistId)
        deleteHistoryForPlaylist(playlistId)
        val removedChannels = deleteChannelsForPlaylist(playlistId)
        val removedPlaylists = deletePlaylistRow(playlistId)
        return PlaylistDeleteResult(
            removedChannels = removedChannels,
            removedPlaylists = removedPlaylists
        )
    }
}
