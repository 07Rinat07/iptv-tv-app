package com.iptv.tv.core.database.di

import android.content.Context
import androidx.room.Room
import com.iptv.tv.core.database.IptvDatabase
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
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
    fun provideHistoryDao(database: IptvDatabase): HistoryDao = database.historyDao()

    @Provides
    fun provideSyncLogDao(database: IptvDatabase): SyncLogDao = database.syncLogDao()

    @Provides
    fun provideDownloadDao(database: IptvDatabase): DownloadDao = database.downloadDao()
}
