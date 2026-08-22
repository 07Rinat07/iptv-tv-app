package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistSourceType

/** Shared summary contract for non-Room system playlists. */
internal fun virtualPlaylistContentSummary(
    playlistId: Long,
    playlistName: String,
    source: String,
    channels: List<Channel>,
    previewComparator: Comparator<Channel>
): PlaylistContentSummary {
    val visible = channels.filterNot(Channel::isHidden)
    val groupCounts = visible
        .mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }
        .groupingBy { it }
        .eachCount()
    return PlaylistContentSummary(
        playlistId = playlistId,
        playlistName = playlistName,
        sourceType = PlaylistSourceType.CUSTOM,
        source = source,
        epgSourceUrl = null,
        totalChannels = channels.size,
        visibleChannels = visible.size,
        hiddenChannels = channels.count(Channel::isHidden),
        channelsWithLogo = visible.count { !it.logo.isNullOrBlank() },
        channelsWithTvgId = visible.count { !it.tvgId.isNullOrBlank() },
        availableChannels = visible.count { it.health == ChannelHealth.AVAILABLE },
        unstableChannels = visible.count { it.health == ChannelHealth.UNSTABLE },
        unavailableChannels = visible.count { it.health == ChannelHealth.UNAVAILABLE },
        unknownHealthChannels = visible.count { it.health == ChannelHealth.UNKNOWN },
        groupCount = groupCounts.size,
        topGroups = groupCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(10)
            .map { it.key to it.value },
        channelPreviews = visible
            .sortedWith(previewComparator)
            .take(50)
            .map { channel ->
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
