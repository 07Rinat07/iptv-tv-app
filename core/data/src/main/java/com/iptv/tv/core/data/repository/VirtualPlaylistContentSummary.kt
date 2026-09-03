package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistSourceType
import java.util.PriorityQueue

/** Shared summary contract for non-Room system playlists. */
internal fun virtualPlaylistContentSummary(
    playlistId: Long,
    playlistName: String,
    source: String,
    channels: List<Channel>,
    previewComparator: Comparator<Channel>
): PlaylistContentSummary {
    val groupCounts = mutableMapOf<String, Int>()
    var visibleChannels = 0
    var hiddenChannels = 0
    var channelsWithLogo = 0
    var channelsWithTvgId = 0
    var availableChannels = 0
    var unstableChannels = 0
    var unavailableChannels = 0
    var unknownHealthChannels = 0

    // Existing summaries used a stable full-list sort and then take(50). Keep exactly the same
    // ordering semantics, including source-order ties, while retaining only the best 50 entries.
    val indexedPreviewComparator = Comparator<IndexedValue<Channel>> { left, right ->
        val channelOrder = previewComparator.compare(left.value, right.value)
        if (channelOrder != 0) channelOrder else left.index.compareTo(right.index)
    }
    val previewQueue = PriorityQueue(
        VIRTUAL_PLAYLIST_PREVIEW_LIMIT,
        indexedPreviewComparator.reversed()
    )

    channels.forEachIndexed { index, channel ->
        if (channel.isHidden) {
            hiddenChannels++
            return@forEachIndexed
        }

        visibleChannels++
        if (!channel.logo.isNullOrBlank()) channelsWithLogo++
        if (!channel.tvgId.isNullOrBlank()) channelsWithTvgId++
        when (channel.health) {
            ChannelHealth.AVAILABLE -> availableChannels++
            ChannelHealth.UNSTABLE -> unstableChannels++
            ChannelHealth.UNAVAILABLE -> unavailableChannels++
            ChannelHealth.UNKNOWN -> unknownHealthChannels++
        }

        channel.group
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { group -> groupCounts[group] = (groupCounts[group] ?: 0) + 1 }

        val indexedChannel = IndexedValue(index = index, value = channel)
        if (previewQueue.size < VIRTUAL_PLAYLIST_PREVIEW_LIMIT) {
            previewQueue.add(indexedChannel)
        } else if (indexedPreviewComparator.compare(indexedChannel, previewQueue.peek()) < 0) {
            previewQueue.poll()
            previewQueue.add(indexedChannel)
        }
    }

    return PlaylistContentSummary(
        playlistId = playlistId,
        playlistName = playlistName,
        sourceType = PlaylistSourceType.CUSTOM,
        source = source,
        epgSourceUrl = null,
        totalChannels = channels.size,
        visibleChannels = visibleChannels,
        hiddenChannels = hiddenChannels,
        channelsWithLogo = channelsWithLogo,
        channelsWithTvgId = channelsWithTvgId,
        availableChannels = availableChannels,
        unstableChannels = unstableChannels,
        unavailableChannels = unavailableChannels,
        unknownHealthChannels = unknownHealthChannels,
        groupCount = groupCounts.size,
        topGroups = groupCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(VIRTUAL_PLAYLIST_TOP_GROUP_LIMIT)
            .map { it.key to it.value },
        channelPreviews = previewQueue
            .toList()
            .sortedWith(indexedPreviewComparator)
            .map { indexedChannel ->
                val channel = indexedChannel.value
                ChannelPreview(
                    id = channel.id,
                    name = channel.name,
                    group = channel.group,
                    logo = channel.logo,
                    health = channel.health,
                    isHidden = channel.isHidden
                )
            }
    )
}

internal const val VIRTUAL_PLAYLIST_TOP_GROUP_LIMIT = 10
internal const val VIRTUAL_PLAYLIST_PREVIEW_LIMIT = 50
