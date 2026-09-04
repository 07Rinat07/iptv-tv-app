package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.PlaylistEntity

private const val PLAYLIST_LOOKUP_BATCH_SIZE = 900

internal suspend fun PlaylistDao.findPlaylistMapByIds(
    playlistIds: Collection<Long>
): Map<Long, PlaylistEntity> {
    val distinctIds = playlistIds.distinct()
    if (distinctIds.isEmpty()) return emptyMap()

    return distinctIds
        .chunked(PLAYLIST_LOOKUP_BATCH_SIZE)
        .flatMap { batch -> findByIds(batch) }
        .associateBy(PlaylistEntity::id)
}
