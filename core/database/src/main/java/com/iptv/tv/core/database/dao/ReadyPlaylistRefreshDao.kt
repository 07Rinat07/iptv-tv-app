package com.iptv.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.SyncLogEntity

/**
 * Atomic persistence boundary for a downloaded Ready-catalog snapshot.
 *
 * A refresh may contain thousands of channels, so writes stay chunked while Room keeps every
 * chunk, stale-row deletion, playlist metadata update and success log inside one transaction.
 * Throwing from any step rolls the whole snapshot back to the previously usable state.
 */
@Dao
abstract class ReadyPlaylistRefreshDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun upsertChannels(items: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE id IN (:channelIds)")
    protected abstract suspend fun deleteChannelsByIds(channelIds: List<Long>): Int

    @Query(
        "UPDATE playlists SET epgSourceUrl = :epgSourceUrl, lastSyncedAt = :syncedAt " +
            "WHERE id = :playlistId"
    )
    protected abstract suspend fun updatePlaylistRefreshMetadata(
        playlistId: Long,
        epgSourceUrl: String?,
        syncedAt: Long
    ): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSyncLog(item: SyncLogEntity)

    @Transaction
    open suspend fun applyRefresh(
        playlistId: Long,
        channels: List<ChannelEntity>,
        staleChannelIds: List<Long>,
        epgSourceUrl: String?,
        syncedAt: Long,
        syncLog: SyncLogEntity
    ) {
        channels.chunked(READY_REFRESH_DB_CHUNK).forEach { chunk ->
            upsertChannels(chunk)
        }
        staleChannelIds.chunked(READY_REFRESH_DB_CHUNK).forEach { chunk ->
            deleteChannelsByIds(chunk)
        }
        check(updatePlaylistRefreshMetadata(playlistId, epgSourceUrl, syncedAt) == 1) {
            "Ready playlist disappeared during refresh: id=$playlistId"
        }
        insertSyncLog(syncLog)
    }

    private companion object {
        const val READY_REFRESH_DB_CHUNK = 500
    }
}
