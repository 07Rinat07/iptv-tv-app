package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelIdentityRow
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.model.ChannelStableIdentity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded lookup of current channel rows that correspond to durable logical Favorites.
 *
 * This deliberately scans only the narrow identity projection in keyset pages. Full channel rows
 * are materialized only after their IDs match one of the requested logical keys. This keeps large
 * Scanner/Torrent-TV catalogs out of Favorites hot flows and one-shot export/reconciliation paths.
 */
@Singleton
class FavoriteLiveChannelResolver @Inject constructor(
    private val lookupDao: FavoriteChannelLookupDao
) {
    suspend fun findMatchingChannels(logicalKeys: Set<String>): List<ChannelEntity> {
        if (logicalKeys.isEmpty()) return emptyList()
        val channelIds = findMatchingIds(logicalKeys)
        if (channelIds.isEmpty()) return emptyList()

        return channelIds
            .chunked(FULL_CHANNEL_BATCH_SIZE)
            .flatMap { ids -> lookupDao.findChannelsByIds(ids) }
            .sortedWith(
                compareBy<ChannelEntity> { it.playlistId }
                    .thenBy { it.orderIndex }
                    .thenBy { it.id }
            )
    }

    suspend fun findMatchingIds(logicalKeys: Set<String>): List<Long> {
        if (logicalKeys.isEmpty()) return emptyList()

        val matched = linkedSetOf<Long>()
        var afterId = 0L
        while (true) {
            val page = lookupDao.getChannelIdentityPage(
                afterId = afterId,
                limit = IDENTITY_PAGE_SIZE
            )
            if (page.isEmpty()) break

            FavoriteChannelIdentityReconciliation.collectMatchingIds(
                rows = page,
                logicalKeys = logicalKeys,
                destination = matched
            )

            afterId = page.last().id
            if (page.size < IDENTITY_PAGE_SIZE) break
        }
        return matched.toList()
    }

    private companion object {
        const val IDENTITY_PAGE_SIZE = 512
        const val FULL_CHANNEL_BATCH_SIZE = 256
    }
}

internal object FavoriteChannelIdentityReconciliation {
    fun collectMatchingIds(
        rows: List<FavoriteChannelIdentityRow>,
        logicalKeys: Set<String>,
        destination: MutableSet<Long>
    ) {
        if (logicalKeys.isEmpty()) return
        rows.forEach { row ->
            val logicalKey = ChannelStableIdentity.key(
                tvgId = row.tvgId,
                name = row.name,
                streamUrl = row.streamUrl
            )
            if (logicalKey in logicalKeys) {
                destination += row.id
            }
        }
    }
}
