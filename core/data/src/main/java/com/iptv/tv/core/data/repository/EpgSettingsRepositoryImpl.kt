package com.iptv.tv.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import com.iptv.tv.core.data.settings.SettingsKeys
import com.iptv.tv.core.data.settings.settingsDataStore
import com.iptv.tv.core.domain.repository.EpgSettingsRepository
import com.iptv.tv.core.model.EpgSettingsPolicy
import com.iptv.tv.core.model.EpgUserSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
class EpgSettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : EpgSettingsRepository {
    override fun observeSettings(): Flow<EpgUserSettings> {
        return context.settingsDataStore.data.map { preferences ->
            EpgUserSettings(
                manualOffsetMinutes = EpgSettingsPolicy.normalizeManualOffsetMinutes(
                    preferences[SettingsKeys.epgManualOffsetMinutes] ?: 0
                ),
                periodicRefreshEnabled = preferences[SettingsKeys.epgPeriodicRefreshEnabled] ?: true,
                refreshOnStartIfStale = preferences[SettingsKeys.epgRefreshOnStartIfStale] ?: true,
                refreshIntervalHours = EpgSettingsPolicy.normalizeRefreshIntervalHours(
                    preferences[SettingsKeys.epgRefreshIntervalHours]
                        ?: EpgSettingsPolicy.DEFAULT_REFRESH_INTERVAL_HOURS
                ),
                lastSuccessfulRefreshAtMs = preferences[SettingsKeys.epgLastSuccessfulRefreshAtMs]
                    ?.takeIf { it > 0L }
            )
        }
    }

    override suspend fun currentSettings(): EpgUserSettings = observeSettings().first()

    override suspend fun setManualOffsetMinutes(minutes: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.epgManualOffsetMinutes] =
                EpgSettingsPolicy.normalizeManualOffsetMinutes(minutes)
        }
    }

    override suspend fun setPeriodicRefreshEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.epgPeriodicRefreshEnabled] = enabled
        }
    }

    override suspend fun setRefreshOnStartIfStale(enabled: Boolean) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.epgRefreshOnStartIfStale] = enabled
        }
    }

    override suspend fun setRefreshIntervalHours(hours: Int) {
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.epgRefreshIntervalHours] =
                EpgSettingsPolicy.normalizeRefreshIntervalHours(hours)
        }
    }

    override suspend fun markSuccessfulRefresh(timestampMs: Long) {
        if (timestampMs <= 0L) return
        context.settingsDataStore.edit { preferences ->
            preferences[SettingsKeys.epgLastSuccessfulRefreshAtMs] = timestampMs
        }
    }
}
