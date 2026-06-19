package com.iptv.tv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelMetadataDao
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.ParentalProfileDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.PlaylistProviderDao
import com.iptv.tv.core.database.dao.ProviderSyncHistoryDao
import com.iptv.tv.core.database.dao.RecordingDao
import com.iptv.tv.core.database.dao.RecordingScheduleDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.dao.TvHomeChannelDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.ChannelMetadataEntity
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.HistoryEntity
import com.iptv.tv.core.database.entity.ParentalProfileEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.PlaylistProviderEntity
import com.iptv.tv.core.database.entity.ProviderSyncHistoryEntity
import com.iptv.tv.core.database.entity.RecordingEntity
import com.iptv.tv.core.database.entity.RecordingScheduleEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.database.entity.TvHomeChannelEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        SyncLogEntity::class,
        DownloadEntity::class,
        RecordingEntity::class,
        RecordingScheduleEntity::class,
        PlaylistProviderEntity::class,
        ProviderSyncHistoryEntity::class,
        ParentalProfileEntity::class,
        ChannelMetadataEntity::class,
        TvHomeChannelEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun downloadDao(): DownloadDao
    abstract fun recordingDao(): RecordingDao
    abstract fun recordingScheduleDao(): RecordingScheduleDao
    abstract fun playlistProviderDao(): PlaylistProviderDao
    abstract fun providerSyncHistoryDao(): ProviderSyncHistoryDao
    abstract fun parentalProfileDao(): ParentalProfileDao
    abstract fun channelMetadataDao(): ChannelMetadataDao
    abstract fun tvHomeChannelDao(): TvHomeChannelDao
}
