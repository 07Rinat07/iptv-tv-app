package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.EpgUserSettings
import kotlinx.coroutines.flow.Flow

/** Persistent EPG-specific user preferences kept separate from general player settings. */
interface EpgSettingsRepository {
    fun observeSettings(): Flow<EpgUserSettings>

    suspend fun currentSettings(): EpgUserSettings

    suspend fun setManualOffsetMinutes(minutes: Int)

    suspend fun setPeriodicRefreshEnabled(enabled: Boolean)

    suspend fun setRefreshOnStartIfStale(enabled: Boolean)

    suspend fun setRefreshIntervalHours(hours: Int)

    suspend fun markSuccessfulRefresh(timestampMs: Long)
}
