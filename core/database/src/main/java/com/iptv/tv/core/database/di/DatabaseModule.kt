package com.iptv.tv.core.database.di

import android.content.Context
import androidx.room.Room
import com.iptv.tv.core.database.IptvDatabase
import com.iptv.tv.core.database.MIGRATION_6_7
import com.iptv.tv.core.database.MIGRATION_7_8
import com.iptv.tv.core.database.MIGRATION_8_9
import com.iptv.tv.core.database.MIGRATION_9_10
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelMetadataDao
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.ParentalProfileDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.PlaylistProviderDao
import com.iptv.tv.core.database.dao.ProviderSyncHistoryDao
import com.iptv.tv.core.database.dao.RecordingDao
import com.iptv.tv.core.database.dao.RecordingScheduleDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.dao.TvHomeChannelDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): IptvDatabase {
        return Room.databaseBuilder(context, IptvDatabase::class.java, "iptv.db")
            .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun providePlaylistDao(database: IptvDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideChannelDao(database: IptvDatabase): ChannelDao = database.channelDao()

    @Provides
    fun provideFavoriteDao(database: IptvDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun provideFavoriteSnapshotDao(database: IptvDatabase): FavoriteSnapshotDao = database.favoriteSnapshotDao()

    @Provides
    fun provideFavoriteChannelLookupDao(database: IptvDatabase): FavoriteChannelLookupDao =
        database.favoriteChannelLookupDao()

    @Provides
    fun provideHistoryDao(database: IptvDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideSyncLogDao(database: IptvDatabase): SyncLogDao = database.syncLogDao()

    @Provides
    fun provideDownloadDao(database: IptvDatabase): DownloadDao = database.downloadDao()

    @Provides
    fun provideRecordingDao(database: IptvDatabase): RecordingDao = database.recordingDao()

    @Provides
    fun provideRecordingScheduleDao(database: IptvDatabase): RecordingScheduleDao = database.recordingScheduleDao()

    @Provides
    fun providePlaylistProviderDao(database: IptvDatabase): PlaylistProviderDao = database.playlistProviderDao()

    @Provides
    fun provideProviderSyncHistoryDao(database: IptvDatabase): ProviderSyncHistoryDao = database.providerSyncHistoryDao()

    @Provides
    fun provideParentalProfileDao(database: IptvDatabase): ParentalProfileDao = database.parentalProfileDao()

    @Provides
    fun provideChannelMetadataDao(database: IptvDatabase): ChannelMetadataDao = database.channelMetadataDao()

    @Provides
    fun provideTvHomeChannelDao(database: IptvDatabase): TvHomeChannelDao = database.tvHomeChannelDao()
}
