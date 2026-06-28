package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.AppStartDestination
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.ParentalControlProfile
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.RecordingStorageInfo
import com.iptv.tv.core.model.RecordingStorageLocation
import com.iptv.tv.core.model.ScannerLearnedQuery
import com.iptv.tv.core.model.ScannerProxySettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeAppStartDestination(): Flow<AppStartDestination>
    fun observeDefaultPlayer(): Flow<PlayerType>
    fun observeBufferProfile(): Flow<BufferProfile>
    fun observeManualBuffer(): Flow<ManualBufferSettings>
    fun observeChannelPlayerOverride(channelId: Long): Flow<PlayerType?>
    fun observeEngineEndpoint(): Flow<String>
    fun observeTorEnabled(): Flow<Boolean>
    fun observeLegalAccepted(): Flow<Boolean>
    fun observeAllowInsecureUrls(): Flow<Boolean>
    fun observeProviderAutoSyncEnabled(): Flow<Boolean>
    fun observeProviderAutoSyncIntervalHours(): Flow<Int>
    fun observeDownloadsWifiOnly(): Flow<Boolean>
    fun observeMaxParallelDownloads(): Flow<Int>
    fun observeRecordingStorageLocation(): Flow<RecordingStorageLocation>
    fun observeRecordingStorageCustomTreeUri(): Flow<String?>
    fun observeScannerAiEnabled(): Flow<Boolean>
    fun observeScannerProxySettings(): Flow<ScannerProxySettings>
    fun observeScannerLearnedQueries(): Flow<List<ScannerLearnedQuery>>
    fun observeParentalControlSettings(): Flow<ParentalControlSettings>
    fun observeParentalControlProfiles(): Flow<List<ParentalControlProfile>>
    suspend fun setAppStartDestination(destination: AppStartDestination)
    suspend fun setDefaultPlayer(playerType: PlayerType)
    suspend fun setBufferProfile(profile: BufferProfile)
    suspend fun setManualBuffer(startMs: Int, rebufferMs: Int, maxMs: Int)
    suspend fun setChannelPlayerOverride(channelId: Long, playerType: PlayerType?)
    suspend fun setEngineEndpoint(endpoint: String)
    suspend fun setTorEnabled(enabled: Boolean)
    suspend fun setLegalAccepted(accepted: Boolean)
    suspend fun setAllowInsecureUrls(allowed: Boolean)
    suspend fun setProviderAutoSyncEnabled(enabled: Boolean)
    suspend fun setProviderAutoSyncIntervalHours(hours: Int)
    suspend fun setDownloadsWifiOnly(enabled: Boolean)
    suspend fun setMaxParallelDownloads(value: Int)
    suspend fun setRecordingStorageLocation(location: RecordingStorageLocation)
    suspend fun setRecordingStorageCustomTreeUri(uri: String?)
    suspend fun getRecordingStorageInfo(location: RecordingStorageLocation): RecordingStorageInfo
    suspend fun setScannerAiEnabled(enabled: Boolean)
    suspend fun setScannerProxySettings(settings: ScannerProxySettings)
    suspend fun recordScannerLearning(query: String, relatedQueries: List<String>, presetId: String?)
    suspend fun clearScannerLearning()
    suspend fun setParentalControl(
        enabled: Boolean,
        pin: String?,
        hideAdultChannels: Boolean,
        blockedKeywords: List<String>
    )
    suspend fun verifyParentalPin(pin: String): Boolean
    suspend fun saveParentalControlProfile(
        name: String,
        pin: String?,
        blockedKeywords: List<String>,
        lockedSettings: Boolean
    ): AppResult<ParentalControlProfile>
    suspend fun activateParentalControlProfile(profileId: Long): AppResult<Unit>
    suspend fun clearActiveParentalControlProfile(): AppResult<Unit>
    suspend fun deleteParentalControlProfile(profileId: Long): AppResult<Unit>
    suspend fun exportParentalControlProfiles(): AppResult<String>
    suspend fun importParentalControlProfiles(payload: String, replaceExisting: Boolean = false): AppResult<Int>
}
