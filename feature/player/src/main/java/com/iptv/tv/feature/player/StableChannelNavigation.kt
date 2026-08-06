package com.iptv.tv.feature.player

/**
 * Чистая навигационная политика для списка каналов.
 *
 * Не зависит от Compose, Media3 или LibVLC, поэтому одинаково используется мышью,
 * пультом и экранными кнопками и безопасно тестируется обычными unit-тестами.
 */
internal object StableChannelNavigation {

    fun adjacentId(
        channelIds: List<Long>,
        selectedChannelId: Long?,
        step: Int
    ): Long? {
        if (channelIds.isEmpty()) return null
        val direction = if (step < 0) -1 else 1
        val selectedIndex = channelIds.indexOf(selectedChannelId)
        if (selectedIndex < 0) {
            return if (direction < 0) channelIds.last() else channelIds.first()
        }
        return channelIds[Math.floorMod(selectedIndex + direction, channelIds.size)]
    }

    fun selectionAfterFilter(
        visibleChannelIds: List<Long>,
        previousSelectedChannelId: Long?
    ): Long? {
        if (visibleChannelIds.isEmpty()) return null
        return previousSelectedChannelId
            ?.takeIf(visibleChannelIds::contains)
            ?: visibleChannelIds.first()
    }

    fun pageTargetIndex(
        currentIndex: Int,
        itemCount: Int,
        pageSize: Int,
        direction: Int
    ): Int {
        if (itemCount <= 0) return 0
        val safeCurrent = currentIndex.coerceIn(0, itemCount - 1)
        val safePage = pageSize.coerceAtLeast(1)
        val delta = if (direction < 0) -safePage else safePage
        return (safeCurrent + delta).coerceIn(0, itemCount - 1)
    }

    fun normalizeGroupSelection(
        selectedGroup: String?,
        selectedSubGroup: String?,
        availableGroups: List<String>,
        availableSubGroups: List<String>
    ): Pair<String?, String?> {
        val normalizedGroup = selectedGroup
            ?.takeIf { selected -> availableGroups.any { it == selected } }
        if (normalizedGroup == null) return null to null

        val normalizedSubGroup = selectedSubGroup
            ?.takeIf { selected -> availableSubGroups.any { it == selected } }
        return normalizedGroup to normalizedSubGroup
    }
}
