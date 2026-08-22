package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelStableIdentity
import java.util.ArrayDeque

internal data class ReadyPlaylistRefreshPlan(
    val upsertChannels: List<ChannelEntity>,
    val staleChannelIds: List<Long>
)

/**
 * Reconciles a freshly downloaded Ready-catalog M3U with its existing Room rows.
 *
 * Stable logical matches retain their row ID so history, Favorites compatibility rows and player
 * routes keep pointing at the same channel. An exact stream fallback covers metadata upgrades such
 * as a publisher adding a tvg-id to a channel that previously had none.
 */
internal object ReadyPlaylistRefreshPlanner {
    fun plan(
        playlistId: Long,
        existing: List<ChannelEntity>,
        incoming: List<Channel>
    ): ReadyPlaylistRefreshPlan {
        val existingByStableKey = linkedMapOf<String, ArrayDeque<ChannelEntity>>()
        existing.forEach { channel ->
            existingByStableKey
                .getOrPut(stableKey(channel)) { ArrayDeque() }
                .addLast(channel)
        }
        val existingByStream = existing
            .groupBy { channel -> channel.streamUrl.trim() }
            .mapNotNull { (streamUrl, candidates) ->
                candidates.singleOrNull()?.let { streamUrl to it }
            }
            .toMap()

        val reusedIds = mutableSetOf<Long>()
        val upserts = incoming.mapIndexed { index, channel ->
            val stableMatch = existingByStableKey[stableKey(channel)]
                ?.firstOrNull { candidate -> candidate.id !in reusedIds }
            val streamMatch = existingByStream[channel.streamUrl.trim()]
                ?.takeIf { candidate -> candidate.id !in reusedIds }
            val matched = stableMatch ?: streamMatch
            if (matched != null) reusedIds += matched.id

            ChannelEntity(
                id = matched?.id ?: 0L,
                playlistId = playlistId,
                tvgId = channel.tvgId,
                name = channel.name,
                groupName = channel.group,
                logo = channel.logo ?: matched?.logo,
                streamUrl = channel.streamUrl,
                health = matched?.health ?: ChannelHealth.UNKNOWN.name,
                orderIndex = index,
                isHidden = matched?.isHidden ?: false
            )
        }
        val staleIds = existing
            .asSequence()
            .map(ChannelEntity::id)
            .filterNot(reusedIds::contains)
            .toList()

        return ReadyPlaylistRefreshPlan(
            upsertChannels = upserts,
            staleChannelIds = staleIds
        )
    }

    private fun stableKey(channel: ChannelEntity): String = ChannelStableIdentity.key(
        tvgId = channel.tvgId,
        name = channel.name,
        streamUrl = channel.streamUrl
    )

    private fun stableKey(channel: Channel): String = ChannelStableIdentity.key(
        tvgId = channel.tvgId,
        name = channel.name,
        streamUrl = channel.streamUrl
    )
}
