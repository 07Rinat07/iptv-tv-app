package com.iptv.tv.feature.home

import com.iptv.tv.core.model.Channel

internal const val HOME_CHANNEL_RAIL_LIMIT = 12

internal fun homeChannelRailItems(
    channels: List<Channel>,
    limit: Int = HOME_CHANNEL_RAIL_LIMIT
): List<Channel> = channels
    .asSequence()
    .filterNot { it.isHidden }
    .sortedBy { it.orderIndex }
    .take(limit.coerceAtLeast(0))
    .toList()

internal fun homeChannelRailFocusIndex(
    channels: List<Channel>,
    selectedChannelId: Long?
): Int? {
    if (channels.isEmpty()) return null
    val selectedIndex = selectedChannelId?.let { id -> channels.indexOfFirst { it.id == id } } ?: -1
    return selectedIndex.takeIf { it >= 0 } ?: 0
}
