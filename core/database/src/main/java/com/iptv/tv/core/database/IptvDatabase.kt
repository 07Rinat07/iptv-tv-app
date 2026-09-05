package com.iptv.tv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelMetadataDao
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.EpgSnapshotDao
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.ParentalProfileDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.PlaylistDeleteDao
import com.iptv.tv.core.database.dao.PlaylistProviderDao
import com.iptv.tv.core.database.dao.ProviderSyncHistoryDao
import com.iptv.tv.core.database.dao.ReadyPlaylistRefreshDao
import com.iptv.tv.core.database.dao.RecordingDao
import com.iptv.tv.core.database.dao.RecordingScheduleDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.dao.TvHomeChannelDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.ChannelMetadataEntity
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.EpgSnapshotDisplayNameEntity
import com.iptv.tv.core.database.entity.EpgSnapshotProgramEntity
import com.iptv.tv.core.database.entity.EpgSnapshotSourceEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.FavoriteLegacySeedEntity
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
        FavoriteChannelEntity::class,
        FavoriteChannelVariantEntity::class,
        FavoriteLegacySeedEntity::class,
        HistoryEntity::class,
        SyncLogEntity::class,
        DownloadEntity::class,
        RecordingEntity::class,
        RecordingScheduleEntity::class,
        PlaylistProviderEntity::class,
        ProviderSyncHistoryEntity::class,
        ParentalProfileEntity::class,
        ChannelMetadataEntity::class,
        TvHomeChannelEntity::class,
        EpgSnapshotSourceEntity::class,
        EpgSnapshotDisplayNameEntity::class,
        EpgSnapshotProgramEntity::class
    ],
    version = 12,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistDeleteDao(): PlaylistDeleteDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun favoriteSnapshotDao(): FavoriteSnapshotDao
    abstract fun favoriteChannelLookupDao(): FavoriteChannelLookupDao
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
    abstract fun readyPlaylistRefreshDao(): ReadyPlaylistRefreshDao
    abstract fun epgSnapshotDao(): EpgSnapshotDao
}
