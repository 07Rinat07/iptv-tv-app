package com.iptv.tv

import com.iptv.tv.core.model.EpgSettingsPolicy
import com.iptv.tv.core.model.EpgUserSettings

/**
 * Tracks process-local foreground entries so the initial app start can keep its existing startup
 * refresh path while later background -> foreground returns can independently request fresh EPG.
 */
internal class EpgForegroundRefreshGate {
    private var hasEnteredForeground = false

    fun onForegroundEntered(): Boolean {
        val returningToForeground = hasEnteredForeground
        hasEnteredForeground = true
        return returningToForeground
    }
}

internal fun shouldRequestEpgRefreshOnForeground(
    returningToForeground: Boolean,
    settings: EpgUserSettings,
    nowMs: Long = System.currentTimeMillis()
): Boolean =
    returningToForeground &&
        settings.refreshOnStartIfStale &&
        EpgSettingsPolicy.isRefreshStale(settings, nowMs)
