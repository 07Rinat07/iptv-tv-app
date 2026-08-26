package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgUserSettingsTest {
    @Test
    fun manualOffsetIsClampedAndRoundedToHalfHourSteps() {
        assertEquals(0, EpgSettingsPolicy.normalizeManualOffsetMinutes(14))
        assertEquals(30, EpgSettingsPolicy.normalizeManualOffsetMinutes(16))
        assertEquals(-30, EpgSettingsPolicy.normalizeManualOffsetMinutes(-16))
        assertEquals(330, EpgSettingsPolicy.normalizeManualOffsetMinutes(329))
        assertEquals(720, EpgSettingsPolicy.normalizeManualOffsetMinutes(900))
        assertEquals(-720, EpgSettingsPolicy.normalizeManualOffsetMinutes(-900))
    }

    @Test
    fun refreshIntervalUsesOnlySupportedCadences() {
        assertEquals(24, EpgSettingsPolicy.normalizeRefreshIntervalHours(0))
        assertEquals(6, EpgSettingsPolicy.normalizeRefreshIntervalHours(5))
        assertEquals(12, EpgSettingsPolicy.normalizeRefreshIntervalHours(11))
        assertEquals(24, EpgSettingsPolicy.normalizeRefreshIntervalHours(23))
    }

    @Test
    fun stalePolicyRefreshesMissingExpiredOrClockInvalidState() {
        val now = 1_000_000_000L
        val base = EpgUserSettings(refreshIntervalHours = 24)

        assertTrue(EpgSettingsPolicy.isRefreshStale(base, now))
        assertTrue(
            EpgSettingsPolicy.isRefreshStale(
                base.copy(lastSuccessfulRefreshAtMs = now - 24L * 60L * 60L * 1_000L),
                now
            )
        )
        assertFalse(
            EpgSettingsPolicy.isRefreshStale(
                base.copy(lastSuccessfulRefreshAtMs = now - 60L * 60L * 1_000L),
                now
            )
        )
        assertTrue(
            EpgSettingsPolicy.isRefreshStale(
                base.copy(lastSuccessfulRefreshAtMs = now + 1L),
                now
            )
        )
    }

    @Test
    fun offsetMillisUsesNormalizedValue() {
        assertEquals(19_800_000L, EpgSettingsPolicy.offsetMillis(329))
        assertEquals(-18_000_000L, EpgSettingsPolicy.offsetMillis(-300))
    }
}
