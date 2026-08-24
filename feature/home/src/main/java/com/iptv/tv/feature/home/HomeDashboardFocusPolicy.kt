package com.iptv.tv.feature.home

internal enum class HomeDashboardFocusZone {
    NAVIGATION,
    MAIN_CONTENT,
    QUICK_SOURCES
}

internal enum class HomeDashboardFocusDirection {
    LEFT,
    RIGHT
}

internal fun nextHomeDashboardFocusZone(
    current: HomeDashboardFocusZone,
    direction: HomeDashboardFocusDirection
): HomeDashboardFocusZone? = when (current) {
    HomeDashboardFocusZone.NAVIGATION -> when (direction) {
        HomeDashboardFocusDirection.LEFT -> null
        HomeDashboardFocusDirection.RIGHT -> HomeDashboardFocusZone.MAIN_CONTENT
    }

    HomeDashboardFocusZone.MAIN_CONTENT -> when (direction) {
        HomeDashboardFocusDirection.LEFT -> HomeDashboardFocusZone.NAVIGATION
        HomeDashboardFocusDirection.RIGHT -> HomeDashboardFocusZone.QUICK_SOURCES
    }

    HomeDashboardFocusZone.QUICK_SOURCES -> when (direction) {
        HomeDashboardFocusDirection.LEFT -> HomeDashboardFocusZone.MAIN_CONTENT
        HomeDashboardFocusDirection.RIGHT -> null
    }
}

internal fun restoreHomeDashboardFocusZone(savedName: String?): HomeDashboardFocusZone =
    HomeDashboardFocusZone.entries.firstOrNull { it.name == savedName }
        ?: HomeDashboardFocusZone.MAIN_CONTENT
