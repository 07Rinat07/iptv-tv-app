package com.iptv.tv.core.database.dao

import androidx.room.withTransaction
import com.iptv.tv.core.database.IptvDatabase
import com.iptv.tv.core.database.entity.ChannelEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelExportSnapshotReader @Inject constructor(
    private val database: IptvDatabase,
    private val channelDao: ChannelDao
) {
    suspend fun visitPlaylistChannelsInOrderWindows(
        playlistId: Long,
        visitor: (List<ChannelEntity>) -> Unit
    ) {
        database.withTransaction {
            val maxOrderIndex = channelDao.maxOrderIndex(playlistId)
            for (window in orderIndexWindows(maxOrderIndex)) {
                visitor(
                    channelDao.findByPlaylistIdAndOrderIndexes(
                        playlistId = playlistId,
                        orderIndexes = window.toList()
                    )
                )
            }
        }
    }

    internal companion object {
        fun orderIndexWindows(maxOrderIndex: Int): Sequence<IntRange> = sequence {
            if (maxOrderIndex < 0) return@sequence

            var batchStart = 0
            while (batchStart <= maxOrderIndex) {
                val batchEnd = minOf(
                    batchStart + ChannelWriteBatching.MAX_IDS_PER_QUERY - 1,
                    maxOrderIndex
                )
                yield(batchStart..batchEnd)
                batchStart = batchEnd + 1
            }
        }
    }
}
