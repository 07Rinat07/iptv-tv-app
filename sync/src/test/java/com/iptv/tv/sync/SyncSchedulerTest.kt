package com.iptv.tv.sync

import com.iptv.tv.core.model.EpgSettingsPolicy
import org.junit.Assert.assertEquals
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
}
