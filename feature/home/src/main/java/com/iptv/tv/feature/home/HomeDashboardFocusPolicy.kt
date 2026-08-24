package com.iptv.tv.feature.home

internal enum class HomeDashboardFocusSection {
    NAVIGATION,
    CONTENT,
    SOURCES
}

internal enum class HomeDashboardHorizontalDirection {
    LEFT,
    RIGHT
}

internal fun nextHomeDashboardFocusSection(
    current: HomeDashboardFocusSection,
    direction: HomeDashboardHorizontalDirection,
    availableSections: Set<HomeDashboardFocusSection>
): HomeDashboardFocusSection? {
    val ordered = listOf(
        HomeDashboardFocusSection.NAVIGATION,
        HomeDashboardFocusSection.CONTENT,
        HomeDashboardFocusSection.SOURCES
    ).filter(availableSections::contains)
    val currentIndex = ordered.indexOf(current)
    if (currentIndex == -1) return null

    val targetIndex = when (direction) {
        HomeDashboardHorizontalDirection.LEFT -> currentIndex - 1
        HomeDashboardHorizontalDirection.RIGHT -> currentIndex + 1
    }
    return ordered.getOrNull(targetIndex)
}

internal fun resolveHomeDashboardRestoreKey(
    savedKey: String?,
    availableKeys: List<String>,
    fallbackKey: String?
): String? = savedKey
    ?.takeIf(availableKeys::contains)
    ?: fallbackKey?.takeIf(availableKeys::contains)
