package com.iptv.tv.feature.home

internal const val HOME_DASHBOARD_MIN_WIDTH_DP = 1000f
internal const val HOME_DASHBOARD_MIN_HEIGHT_DP = 560f

internal fun shouldUseWideHomeDashboard(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= HOME_DASHBOARD_MIN_WIDTH_DP && heightDp >= HOME_DASHBOARD_MIN_HEIGHT_DP
