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
            visitPlaylistChannelsInOrderWindowsInTransaction(
                playlistId = playlistId,
                visitor = visitor
            )
        }
    }

    /**
     * Captures playlist membership/order as lightweight IDs while retaining at most one bounded
     * ChannelEntity window at a time. Callers can process those IDs outside the read transaction
     * without pinning a full playlist of heavy channel rows in memory.
     */
    suspend fun snapshotPlaylistChannelIdsInOrder(playlistId: Long): List<Long> {
        return database.withTransaction {
            val channelIds = mutableListOf<Long>()
            visitPlaylistChannelsInOrderWindowsInTransaction(playlistId) { page ->
                page.forEach { channel -> channelIds += channel.id }
            }
            channelIds
        }
    }

    private suspend fun visitPlaylistChannelsInOrderWindowsInTransaction(
        playlistId: Long,
        visitor: (List<ChannelEntity>) -> Unit
    ) {
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
