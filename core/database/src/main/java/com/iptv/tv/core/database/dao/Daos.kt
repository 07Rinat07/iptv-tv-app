package com.iptv.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.ChannelMetadataEntity
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.HistoryEntity
import com.iptv.tv.core.database.entity.ParentalProfileEntity
import com.iptv.tv.core.database.entity.PlaylistProviderEntity
import com.iptv.tv.core.database.entity.RecordingEntity
import com.iptv.tv.core.database.entity.RecordingScheduleEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.database.entity.TvHomeChannelEntity
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

    @Query("UPDATE playlists SET epgSourceUrl = :epgSourceUrl WHERE id = :playlistId")
    suspend fun updateEpgSourceUrl(playlistId: Long, epgSourceUrl: String?): Int

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

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecordings(limit: Int): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE status = :status ORDER BY scheduledStartAt ASC, createdAt ASC")
    suspend fun findByStatus(status: String): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE status IN (:statuses) AND COALESCE(endedAt, createdAt) < :beforeEpochMs")
    suspend fun findOlderThan(statuses: List<String>, beforeEpochMs: Long): List<RecordingEntity>

    @Query("SELECT * FROM recordings WHERE id = :recordingId LIMIT 1")
    suspend fun findById(recordingId: Long): RecordingEntity?

    @Query(
        "SELECT * FROM recordings WHERE channelId = :channelId AND scheduledStartAt = :startAt " +
            "AND scheduledEndAt = :endAt AND status IN (:statuses) LIMIT 1"
    )
    suspend fun findMatchingScheduled(
        channelId: Long,
        startAt: Long,
        endAt: Long,
        statuses: List<String>
    ): RecordingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecordingEntity): Long

    @Query("UPDATE recordings SET status = :status, filePath = :filePath, startedAt = :startedAt WHERE id = :recordingId")
    suspend fun markStarted(recordingId: Long, status: String, filePath: String?, startedAt: Long): Int

    @Query("UPDATE recordings SET status = :status, endedAt = :endedAt WHERE id = :recordingId")
    suspend fun markFinished(recordingId: Long, status: String, endedAt: Long): Int

    @Query(
        "UPDATE recordings SET status = :status, endedAt = :endedAt WHERE channelId = :channelId " +
            "AND scheduledStartAt = :startAt AND scheduledEndAt = :endAt AND status = :currentStatus"
    )
    suspend fun updateMatchingScheduledStatus(
        channelId: Long,
        startAt: Long,
        endAt: Long,
        currentStatus: String,
        status: String,
        endedAt: Long
    ): Int

    @Query("DELETE FROM recordings WHERE id = :recordingId")
    suspend fun deleteById(recordingId: Long): Int
}

@Dao
interface RecordingScheduleDao {
    @Query("SELECT * FROM recording_schedules ORDER BY startAt ASC")
    fun observeSchedules(): Flow<List<RecordingScheduleEntity>>

    @Query("SELECT * FROM recording_schedules WHERE id = :scheduleId LIMIT 1")
    suspend fun findById(scheduleId: Long): RecordingScheduleEntity?

    @Query("SELECT * FROM recording_schedules WHERE enabled = 1 AND startAt <= :beforeEpochMs ORDER BY startAt ASC")
    suspend fun findDueSchedules(beforeEpochMs: Long): List<RecordingScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecordingScheduleEntity): Long

    @Query("UPDATE recording_schedules SET enabled = :enabled WHERE id = :scheduleId")
    suspend fun setEnabled(scheduleId: Long, enabled: Boolean): Int

    @Query("DELETE FROM recording_schedules WHERE id = :scheduleId")
    suspend fun deleteById(scheduleId: Long): Int
}

@Dao
interface PlaylistProviderDao {
    @Query("SELECT * FROM playlist_providers ORDER BY createdAt DESC")
    fun observeProviders(): Flow<List<PlaylistProviderEntity>>

    @Query("SELECT * FROM playlist_providers WHERE id = :providerId LIMIT 1")
    suspend fun findById(providerId: Long): PlaylistProviderEntity?

    @Query("SELECT * FROM playlist_providers WHERE type = :type ORDER BY createdAt DESC")
    suspend fun findByType(type: String): List<PlaylistProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PlaylistProviderEntity): Long

    @Query("UPDATE playlist_providers SET linkedPlaylistId = :playlistId, lastSyncedAt = :syncedAt WHERE id = :providerId")
    suspend fun markSynced(providerId: Long, playlistId: Long?, syncedAt: Long): Int

    @Query("DELETE FROM playlist_providers WHERE id = :providerId")
    suspend fun deleteById(providerId: Long): Int
}

@Dao
interface ParentalProfileDao {
    @Query("SELECT * FROM parental_profiles ORDER BY createdAt DESC")
    fun observeProfiles(): Flow<List<ParentalProfileEntity>>

    @Query("SELECT * FROM parental_profiles WHERE enabled = 1 ORDER BY createdAt DESC LIMIT 1")
    suspend fun findActiveProfile(): ParentalProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ParentalProfileEntity): Long

    @Query("UPDATE parental_profiles SET enabled = :enabled WHERE id = :profileId")
    suspend fun setEnabled(profileId: Long, enabled: Boolean): Int

    @Query("DELETE FROM parental_profiles WHERE id = :profileId")
    suspend fun deleteById(profileId: Long): Int
}

@Dao
interface ChannelMetadataDao {
    @Query("SELECT * FROM channel_metadata WHERE channelId = :channelId LIMIT 1")
    suspend fun findByChannelId(channelId: Long): ChannelMetadataEntity?

    @Query("SELECT * FROM channel_metadata WHERE channelId IN (:channelIds)")
    suspend fun findByChannelIds(channelIds: List<Long>): List<ChannelMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ChannelMetadataEntity)

    @Query("UPDATE channel_metadata SET manualLogoUrl = :logoUrl, updatedAt = :updatedAt WHERE channelId = :channelId")
    suspend fun updateManualLogo(channelId: Long, logoUrl: String?, updatedAt: Long): Int

    @Query("DELETE FROM channel_metadata WHERE channelId IN (:channelIds)")
    suspend fun deleteByChannelIds(channelIds: List<Long>): Int
}

@Dao
interface TvHomeChannelDao {
    @Query("SELECT * FROM tv_home_channels")
    fun observeStates(): Flow<List<TvHomeChannelEntity>>

    @Query("SELECT * FROM tv_home_channels WHERE type = :type LIMIT 1")
    suspend fun findByType(type: String): TvHomeChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TvHomeChannelEntity)

    @Query("UPDATE tv_home_channels SET providerChannelId = :providerChannelId, lastPublishedAt = :publishedAt WHERE type = :type")
    suspend fun markPublished(type: String, providerChannelId: Long?, publishedAt: Long): Int

    @Query("UPDATE tv_home_channels SET enabled = :enabled WHERE type = :type")
    suspend fun setEnabled(type: String, enabled: Boolean): Int
}
