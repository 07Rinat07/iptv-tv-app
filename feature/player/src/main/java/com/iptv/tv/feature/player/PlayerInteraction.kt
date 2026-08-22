package com.iptv.tv.feature.player

/**
 * Returns the next channel id in a stable circular order.
 *
 * This helper deliberately owns no filtering/search logic: callers pass the already prepared
 * channel-id order, keeping Scanner/query semantics outside the playback interaction layer.
 */
fun adjacentChannelId(
    channelIds: List<Long>,
    selectedChannelId: Long?,
    step: Int
): Long? {
    if (channelIds.isEmpty()) return null
    val normalizedStep = if (step < 0) -1 else 1
    val selectedIndex = channelIds.indexOf(selectedChannelId)
    if (selectedIndex < 0) {
        return if (normalizedStep < 0) channelIds.last() else channelIds.first()
    }
    val target = (selectedIndex + normalizedStep + channelIds.size) % channelIds.size
    return channelIds[target]
}
