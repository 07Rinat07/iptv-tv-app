package com.iptv.tv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.HistoryEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.SyncLogEntity

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        SyncLogEntity::class,
        DownloadEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao
    abstract fun channelDao(): ChannelDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun downloadDao(): DownloadDao
}
