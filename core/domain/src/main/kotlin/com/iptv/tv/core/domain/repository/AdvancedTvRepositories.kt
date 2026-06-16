package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.ChannelMetadata
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.ParentalControlProfile
import com.iptv.tv.core.model.ProviderAccountStatus
import com.iptv.tv.core.model.PlaylistProvider
import com.iptv.tv.core.model.ProviderType
import com.iptv.tv.core.model.RecordingSchedule
import com.iptv.tv.core.model.RecordingTask
import com.iptv.tv.core.model.TvHomeChannelState
import kotlinx.coroutines.flow.Flow

interface EpgGuideRepository {
    suspend fun getProgramsForWindow(
        playlistId: Long,
        startEpochMs: Long,
        endEpochMs: Long,
        query: String? = null
    ): AppResult<Map<Long, List<EpgProgram>>>
}

interface ProviderAccountRepository {
    fun observeProviders(): Flow<List<PlaylistProvider>>
    suspend fun saveProvider(provider: PlaylistProvider): AppResult<Long>
    suspend fun checkProvider(providerId: Long): AppResult<ProviderAccountStatus>
    suspend fun syncProvider(providerId: Long): AppResult<Long>
    suspend fun syncAllProviders(): AppResult<Int>
    suspend fun deleteProvider(providerId: Long): AppResult<Int>
    suspend fun getProvidersByType(type: ProviderType): AppResult<List<PlaylistProvider>>
}

interface RecordingRepository {
    fun observeRecordings(limit: Int = 100): Flow<List<RecordingTask>>
    fun observeSchedules(): Flow<List<RecordingSchedule>>
    suspend fun scheduleRecording(schedule: RecordingSchedule): AppResult<Long>
    suspend fun startRecordingNow(channelId: Long, programTitle: String? = null): AppResult<Long>
    suspend fun cancelRecording(recordingId: Long): AppResult<Int>
    suspend fun deleteRecording(recordingId: Long, deleteFile: Boolean = true): AppResult<Int>
    suspend fun cleanupOldRecordings(maxAgeDays: Int): AppResult<Int>
    suspend fun setScheduleEnabled(scheduleId: Long, enabled: Boolean): AppResult<Int>
    suspend fun deleteSchedule(scheduleId: Long): AppResult<Int>
    suspend fun processDueRecordings(maxConcurrent: Int = 1): AppResult<Int>
}

interface ParentalControlRepository {
    fun observeProfiles(): Flow<List<ParentalControlProfile>>
    suspend fun saveProfile(profile: ParentalControlProfile): AppResult<Long>
    suspend fun verifyPin(rawPin: String): AppResult<Boolean>
    suspend fun isChannelBlocked(channelName: String, groupName: String?): AppResult<Boolean>
}

interface ChannelMetadataRepository {
    suspend fun resolveMetadata(channelId: Long): AppResult<ChannelMetadata?>
    suspend fun setManualLogo(channelId: Long, logoUrl: String?): AppResult<Int>
    suspend fun refreshMetadata(playlistId: Long): AppResult<Int>
}

interface TvHomeIntegrationRepository {
    fun observeChannelStates(): Flow<List<TvHomeChannelState>>
    suspend fun publishRecentChannels(): AppResult<Int>
    suspend fun publishFavorites(): AppResult<Int>
    suspend fun publishWatchNext(): AppResult<Int>
    suspend fun publishRecordings(): AppResult<Int>
    suspend fun setEnabled(state: TvHomeChannelState): AppResult<Unit>
}
