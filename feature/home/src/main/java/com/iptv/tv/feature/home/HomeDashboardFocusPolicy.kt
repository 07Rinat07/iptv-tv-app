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

internal enum class HomeDashboardQuickFocusAnchor {
    READY_PLAYLIST,
    SCANNER,
    PRIMARY_ACTION,
    NONE
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
    HomeDashboardFocusZone.values().firstOrNull { it.name == savedName }
        ?: HomeDashboardFocusZone.MAIN_CONTENT

internal fun homeDashboardQuickFocusItemIndex(
    anchor: HomeDashboardQuickFocusAnchor,
    readySourceCount: Int,
    hasScanner: Boolean
): Int? = when (anchor) {
    HomeDashboardQuickFocusAnchor.READY_PLAYLIST -> if (readySourceCount > 0) 1 else null
    HomeDashboardQuickFocusAnchor.SCANNER -> if (hasScanner) 1 + readySourceCount else null
    HomeDashboardQuickFocusAnchor.PRIMARY_ACTION -> 1 + readySourceCount + if (hasScanner) 1 else 0
    HomeDashboardQuickFocusAnchor.NONE -> null
}
