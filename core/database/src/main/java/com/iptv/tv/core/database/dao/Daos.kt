package com.iptv.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.HistoryEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCount(
    @Embedded val playlist: PlaylistEntity,
    val channelCount: Int
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.*, COUNT(c.id) AS channelCount " +
            "FROM playlists p " +
            "LEFT JOIN channels c ON c.playlistId = p.id AND c.isHidden = 0 " +
            "GROUP BY p.id " +
            "ORDER BY p.createdAt DESC"
    )
    fun observePlaylistsWithCount(): Flow<List<PlaylistWithCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(item: PlaylistEntity): Long

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun findById(playlistId: Long): PlaylistEntity?

    @Query(
        "SELECT * FROM playlists " +
            "WHERE source = :source AND isCustom = 1 " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    suspend fun findLatestCustomBySource(source: String): PlaylistEntity?

    @Query("SELECT id FROM playlists")
    suspend fun getAllIds(): List<Long>

    @Query("UPDATE playlists SET lastSyncedAt = :syncedAt WHERE id = :playlistId")
    suspend fun updateLastSynced(playlistId: Long, syncedAt: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deleteById(playlistId: Long): Int
}

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    fun observeChannels(playlistId: Long): Flow<List<ChannelEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ChannelEntity>)

    @Query("SELECT * FROM channels WHERE playlistId = :playlistId ORDER BY orderIndex ASC")
    suspend fun getChannels(playlistId: Long): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id IN (:channelIds)")
    suspend fun findByIds(channelIds: List<Long>): List<ChannelEntity>

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun clearPlaylist(playlistId: Long): Int

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    suspend fun findById(channelId: Long): ChannelEntity?

    @Query("UPDATE channels SET health = :health WHERE id = :channelId")
    suspend fun updateHealth(channelId: Long, health: String)

    @Query("UPDATE channels SET isHidden = :hidden WHERE id IN (:channelIds)")
    suspend fun setHidden(channelIds: List<Long>, hidden: Boolean): Int

    @Query("DELETE FROM channels WHERE id IN (:channelIds)")
    suspend fun deleteByIds(channelIds: List<Long>): Int

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND health = :health")
    suspend fun deleteByHealth(playlistId: Long, health: String): Int

    @Query("UPDATE channels SET orderIndex = :orderIndex WHERE id = :channelId")
    suspend fun updateOrderIndex(channelId: Long, orderIndex: Int)

    @Query(
        "UPDATE channels SET " +
            "name = :name, groupName = :groupName, logo = :logo, streamUrl = :streamUrl " +
            "WHERE id = :channelId"
    )
    suspend fun updateChannelFields(
        channelId: Long,
        name: String,
        groupName: String?,
        logo: String?,
        streamUrl: String
    ): Int

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM channels WHERE playlistId = :playlistId")
    suspend fun maxOrderIndex(playlistId: Long): Int

    @Query(
        "SELECT c.* FROM channels c " +
            "INNER JOIN favorites f ON f.channelId = c.id " +
            "ORDER BY f.addedAt DESC"
    )
    fun observeFavoriteChannels(): Flow<List<ChannelEntity>>
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelId = :channelId")
    suspend fun delete(channelId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelId = :channelId)")
    suspend fun exists(channelId: Long): Boolean

    @Query("DELETE FROM favorites WHERE channelId IN (:channelIds)")
    suspend fun deleteByChannelIds(channelIds: List<Long>): Int
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT :limit")
    fun observeHistory(limit: Int): Flow<List<HistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE channelId IN (:channelIds)")
    suspend fun deleteByChannelIds(channelIds: List<Long>): Int
}

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_logs ORDER BY createdAt DESC LIMIT :limit")
    fun observeLogs(limit: Int): Flow<List<SyncLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncLogEntity)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC LIMIT :limit")
    fun observeDownloads(limit: Int): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: DownloadEntity): Long

    @Query("SELECT * FROM downloads WHERE id = :downloadId LIMIT 1")
    suspend fun findById(downloadId: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC")
    suspend fun findByStatus(status: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY createdAt ASC LIMIT 1")
    suspend fun findFirstByStatus(status: String): DownloadEntity?

    @Query("UPDATE downloads SET status = :status WHERE id = :downloadId")
    suspend fun updateStatus(downloadId: Long, status: String): Int

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE id = :downloadId")
    suspend fun updateState(downloadId: Long, status: String, progress: Int): Int

    @Query("DELETE FROM downloads WHERE id = :downloadId")
    suspend fun deleteById(downloadId: Long): Int
}
