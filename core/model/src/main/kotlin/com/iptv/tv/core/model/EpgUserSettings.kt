package com.iptv.tv.core.model

import kotlin.math.roundToInt

data class EpgUserSettings(
    val manualOffsetMinutes: Int = 0,
    val periodicRefreshEnabled: Boolean = true,
    val refreshOnStartIfStale: Boolean = true,
    val refreshIntervalHours: Int = EpgSettingsPolicy.DEFAULT_REFRESH_INTERVAL_HOURS,
    val lastSuccessfulRefreshAtMs: Long? = null
)

/**
 * Central validation policy for EPG clock correction and background refresh cadence.
 *
 * XMLTV timestamps with an explicit offset remain absolute instants. The manual correction is an
 * opt-in field-alignment tool for broken/non-compliant feeds and therefore defaults to zero. UI
 * controls use 30-minute steps so half-hour time zones and common provider mistakes remain usable.
 */
object EpgSettingsPolicy {
    const val MIN_MANUAL_OFFSET_MINUTES = -12 * 60
    const val MAX_MANUAL_OFFSET_MINUTES = 12 * 60
    const val MANUAL_OFFSET_STEP_MINUTES = 30
    const val DEFAULT_REFRESH_INTERVAL_HOURS = 24

    val supportedRefreshIntervalsHours: List<Int> = listOf(6, 12, 24)

    fun normalizeManualOffsetMinutes(minutes: Int): Int {
        val clamped = minutes.coerceIn(MIN_MANUAL_OFFSET_MINUTES, MAX_MANUAL_OFFSET_MINUTES)
        val steps = (clamped.toDouble() / MANUAL_OFFSET_STEP_MINUTES.toDouble()).roundToInt()
        return (steps * MANUAL_OFFSET_STEP_MINUTES)
            .coerceIn(MIN_MANUAL_OFFSET_MINUTES, MAX_MANUAL_OFFSET_MINUTES)
    }

    fun normalizeRefreshIntervalHours(hours: Int): Int {
        if (hours <= 0) return DEFAULT_REFRESH_INTERVAL_HOURS
        return supportedRefreshIntervalsHours.minBy { candidate ->
            kotlin.math.abs(candidate - hours)
        }
    }

    fun isRefreshStale(
        settings: EpgUserSettings,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val lastRefresh = settings.lastSuccessfulRefreshAtMs ?: return true
        if (lastRefresh <= 0L || nowMs < lastRefresh) return true
        val intervalMs = settings.refreshIntervalHours.toLong() * 60L * 60L * 1_000L
        return nowMs - lastRefresh >= intervalMs
    }

    fun offsetMillis(minutes: Int): Long =
        normalizeManualOffsetMinutes(minutes).toLong() * 60_000L
}
