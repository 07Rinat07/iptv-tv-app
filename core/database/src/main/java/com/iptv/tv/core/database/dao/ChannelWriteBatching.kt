package com.iptv.tv.core.database.dao

internal object ChannelWriteBatching {
    const val MAX_IDS_PER_QUERY = 900

    fun batches(channelIds: List<Long>): List<List<Long>> =
        channelIds.distinct().chunked(MAX_IDS_PER_QUERY)
}
