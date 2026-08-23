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
 * Exact stream matches are reserved for the whole incoming snapshot before any stable-identity
 * fallback is assigned. This prevents a new same-identity variant that appears earlier in the M3U
 * from stealing the persisted row of an unchanged stream that appears later. Stable identity is
 * then used only from rows that are not reserved for any exact stream match.
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

        // Reserve every unambiguous exact endpoint before stable fallback matching begins. Incoming
        // Ready channels are already exact-URL deduplicated, so one persisted row can have at most
        // one exact incoming owner here.
        val exactMatches = incoming.map { channel ->
            existingByStream[channel.streamUrl.trim()]
        }
        val reservedExactIds = exactMatches
            .mapNotNullTo(hashSetOf()) { candidate -> candidate?.id }

        val reusedIds = mutableSetOf<Long>()
        val upserts = incoming.mapIndexed { index, channel ->
            val incomingStream = channel.streamUrl.trim()
            val streamMatch = exactMatches[index]
                ?.takeIf { candidate -> candidate.id !in reusedIds }
            val stableMatch = if (streamMatch == null) {
                existingByStableKey[stableKey(channel)]
                    ?.firstOrNull { candidate ->
                        candidate.id !in reusedIds && candidate.id !in reservedExactIds
                    }
            } else {
                null
            }
            val matched = streamMatch ?: stableMatch
            if (matched != null) reusedIds += matched.id
            val unchangedStream = matched?.streamUrl?.trim() == incomingStream

            ChannelEntity(
                id = matched?.id ?: 0L,
                playlistId = playlistId,
                tvgId = channel.tvgId,
                name = channel.name,
                groupName = channel.group,
                logo = channel.logo ?: matched?.logo,
                streamUrl = channel.streamUrl,
                // Health belongs to a concrete endpoint. Keep it only for an exact-stream match;
                // a replacement URL must be revalidated instead of inheriting stale availability.
                health = if (matched != null && unchangedStream) {
                    matched.health
                } else {
                    ChannelHealth.UNKNOWN.name
                },
                orderIndex = index,
                isHidden = matched?.isHidden ?: false,
                catchUpMode = channel.catchUp?.mode,
                catchUpDays = channel.catchUp?.days,
                catchUpSourceTemplate = channel.catchUp?.sourceTemplate,
                catchUpDaysDeclared = channel.catchUp?.daysDeclared == true
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
