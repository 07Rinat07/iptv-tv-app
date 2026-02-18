package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.PlayerType
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    fun observeDefaultPlayer(): Flow<PlayerType>
    fun observeBufferProfile(): Flow<BufferProfile>
    fun observeManualBuffer(): Flow<ManualBufferSettings>
    fun observeChannelPlayerOverride(channelId: Long): Flow<PlayerType?>
    fun observeEngineEndpoint(): Flow<String>
    fun observeTorEnabled(): Flow<Boolean>
    fun observeLegalAccepted(): Flow<Boolean>
    fun observeAllowInsecureUrls(): Flow<Boolean>
    fun observeDownloadsWifiOnly(): Flow<Boolean>
    fun observeMaxParallelDownloads(): Flow<Int>
    suspend fun setDefaultPlayer(playerType: PlayerType)
    suspend fun setBufferProfile(profile: BufferProfile)
    suspend fun setManualBuffer(startMs: Int, rebufferMs: Int, maxMs: Int)
    suspend fun setChannelPlayerOverride(channelId: Long, playerType: PlayerType?)
    suspend fun setEngineEndpoint(endpoint: String)
    suspend fun setTorEnabled(enabled: Boolean)
    suspend fun setLegalAccepted(accepted: Boolean)
    suspend fun setAllowInsecureUrls(allowed: Boolean)
    suspend fun setDownloadsWifiOnly(enabled: Boolean)
    suspend fun setMaxParallelDownloads(value: Int)
}
