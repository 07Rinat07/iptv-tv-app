package com.iptv.tv.sync

import com.iptv.tv.core.model.EpgSettingsPolicy
import com.iptv.tv.core.model.EpgUserSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerTest {
    @Test
    fun normalizeSyncHours_picksSupportedIntervals() {
        assertEquals(12, SyncScheduler.normalizeSyncHours(0))
        assertEquals(6, SyncScheduler.normalizeSyncHours(5))
        assertEquals(12, SyncScheduler.normalizeSyncHours(11))
        assertEquals(24, SyncScheduler.normalizeSyncHours(23))
        assertEquals(24, SyncScheduler.normalizeSyncHours(48))
    }

    @Test
    fun epgRefreshPolicyDefaultsToDailyAndSupportsOnlyBoundedCadences() {
        assertEquals(24, EpgSettingsPolicy.normalizeRefreshIntervalHours(0))
        assertEquals(6, EpgSettingsPolicy.normalizeRefreshIntervalHours(5))
        assertEquals(12, EpgSettingsPolicy.normalizeRefreshIntervalHours(11))
        assertEquals(24, EpgSettingsPolicy.normalizeRefreshIntervalHours(23))
        assertEquals(24, EpgSettingsPolicy.normalizeRefreshIntervalHours(72))
    }

    @Test
    fun foregroundReturnRequestsRefreshWhenEnabledAndStale() {
        val now = 30L * 60L * 60L * 1_000L
        val settings = EpgUserSettings(
            refreshOnStartIfStale = true,
            refreshIntervalHours = 24,
            lastSuccessfulRefreshAtMs = now - 25L * 60L * 60L * 1_000L
        )

        assertTrue(shouldRequestEpgRefreshOnForegroundReturn(settings, nowMs = now))
    }

    @Test
    fun foregroundReturnDoesNotRequestRefreshWhenGuideIsFresh() {
        val now = 30L * 60L * 60L * 1_000L
        val settings = EpgUserSettings(
            refreshOnStartIfStale = true,
            refreshIntervalHours = 24,
            lastSuccessfulRefreshAtMs = now - 2L * 60L * 60L * 1_000L
        )

        assertFalse(shouldRequestEpgRefreshOnForegroundReturn(settings, nowMs = now))
    }

    @Test
    fun foregroundReturnRespectsRefreshOnStartOptOut() {
        val settings = EpgUserSettings(
            refreshOnStartIfStale = false,
            lastSuccessfulRefreshAtMs = null
        )

        assertFalse(shouldRequestEpgRefreshOnForegroundReturn(settings, nowMs = 1_000L))
    }

    @Test
    fun foregroundReturnTreatsFutureRefreshTimestampAsStale() {
        val now = 1_000L
        val settings = EpgUserSettings(
            refreshOnStartIfStale = true,
            lastSuccessfulRefreshAtMs = now + 1L
        )

        assertTrue(shouldRequestEpgRefreshOnForegroundReturn(settings, nowMs = now))
    }
}
