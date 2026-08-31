package com.iptv.tv

import com.iptv.tv.core.model.EpgUserSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgForegroundRefreshPolicyTest {
    @Test
    fun firstForegroundEntryDoesNotRequestReturnRefresh() {
        val gate = EpgForegroundRefreshGate()

        assertFalse(gate.onForegroundEntered())
        assertTrue(gate.onForegroundEntered())
    }

    @Test
    fun foregroundReturnRequestsRefreshOnlyWhenOptedInAndStale() {
        val nowMs = 10_000_000L
        val staleSettings = EpgUserSettings(
            refreshOnStartIfStale = true,
            refreshIntervalHours = 6,
            lastSuccessfulRefreshAtMs = nowMs - 7 * 60 * 60 * 1_000L
        )

        assertTrue(
            shouldRequestEpgRefreshOnForeground(
                returningToForeground = true,
                settings = staleSettings,
                nowMs = nowMs
            )
        )
        assertFalse(
            shouldRequestEpgRefreshOnForeground(
                returningToForeground = false,
                settings = staleSettings,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun foregroundReturnDoesNotRequestRefreshWhenGuideIsFresh() {
        val nowMs = 10_000_000L
        val freshSettings = EpgUserSettings(
            refreshOnStartIfStale = true,
            refreshIntervalHours = 6,
            lastSuccessfulRefreshAtMs = nowMs - 30 * 60 * 1_000L
        )

        assertFalse(
            shouldRequestEpgRefreshOnForeground(
                returningToForeground = true,
                settings = freshSettings,
                nowMs = nowMs
            )
        )
    }

    @Test
    fun foregroundReturnRespectsDisabledRefreshOnStartSetting() {
        val settings = EpgUserSettings(
            refreshOnStartIfStale = false,
            lastSuccessfulRefreshAtMs = null
        )

        assertFalse(
            shouldRequestEpgRefreshOnForeground(
                returningToForeground = true,
                settings = settings,
                nowMs = 10_000_000L
            )
        )
    }
}
