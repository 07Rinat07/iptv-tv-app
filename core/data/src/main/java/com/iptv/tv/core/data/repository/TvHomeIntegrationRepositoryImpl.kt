package com.iptv.tv.core.data.repository

import android.annotation.TargetApi
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.data.mapper.toEntity
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.RecordingDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.dao.TvHomeChannelDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.RecordingEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.TvHomeIntegrationRepository
import com.iptv.tv.core.model.RecordingStatus
import com.iptv.tv.core.model.TvHomeChannelState
import com.iptv.tv.core.model.TvHomeChannelType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvHomeIntegrationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tvHomeChannelDao: TvHomeChannelDao,
    private val channelDao: ChannelDao,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao,
    private val historyDao: HistoryDao,
    private val recordingDao: RecordingDao,
    private val syncLogDao: SyncLogDao
) : TvHomeIntegrationRepository {
    override fun observeChannelStates(): Flow<List<TvHomeChannelState>> {
        return tvHomeChannelDao.observeStates().map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun publishRecentChannels(): AppResult<Int> = publishPreviewRow(
        type = TvHomeChannelType.RECENT_CHANNELS,
        displayName = "Недавние каналы",
        channels = recentChannels(limit = MAX_PREVIEW_PROGRAMS)
    )

    override suspend fun publishFavorites(): AppResult<Int> = publishPreviewRow(
        type = TvHomeChannelType.FAVORITES,
        displayName = "Избранные каналы",
        channels = favoriteChannelLookupDao.getFavoriteChannelsLimited(MAX_PREVIEW_PROGRAMS)
    )

    override suspend fun publishWatchNext(): AppResult<Int> = withContext(Dispatchers.IO) {
        if (!supportsTvHome()) {
            addLog("tv_home_unsupported", "type=${TvHomeChannelType.WATCH_NEXT.name}, requires Android 8.0+")
            return@withContext unsupportedResult()
        }
        val state = tvHomeChannelDao.findByType(TvHomeChannelType.WATCH_NEXT.name)?.toModel()
            ?: TvHomeChannelState(
                type = TvHomeChannelType.WATCH_NEXT,
                providerChannelId = null,
                enabled = true,
                lastPublishedAt = null
            )
        if (!state.enabled) {
            addLog("tv_home_row_skipped", "type=${TvHomeChannelType.WATCH_NEXT.name}, reason=disabled")
            return@withContext AppResult.Success(0)
        }

        runCatching {
            deleteExistingWatchNext()
            val channel = recentChannels(limit = 1).firstOrNull()
                ?: run {
                    addLog("tv_home_row_empty", "type=${TvHomeChannelType.WATCH_NEXT.name}, reason=no_recent_channels")
                    return@withContext AppResult.Success(0)
                }
            val values = previewValuesForChannel(channel)
            values.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            values.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis())
            val uri = context.contentResolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values)
            val inserted = if (uri != null) 1 else 0
            addLog("tv_home_watch_next_published", "published=$inserted, channelId=${channel.id}")
            AppResult.Success(inserted)
        }.getOrElse { throwable ->
            logAndError("tv_home_watch_next_error", throwable)
        }
    }

    override suspend fun publishRecordings(): AppResult<Int> = withContext(Dispatchers.IO) {
        val recordings = recordingDao.findByStatus(RecordingStatus.COMPLETED.name)
            .filter { it.filePath?.isNotBlank() == true }
            .take(MAX_PREVIEW_PROGRAMS)
        publishPreviewRow(
            type = TvHomeChannelType.RECORDINGS,
            displayName = "Записи эфира",
            recordings = recordings
        )
    }

    override suspend fun setEnabled(state: TvHomeChannelState): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val stateToPersist = if (state.enabled) {
                state
            } else {
                clearPublishedState(state)
            }
            tvHomeChannelDao.upsert(stateToPersist.toEntity())
            addLog("tv_home_row_enabled", "type=${state.type.name}, enabled=${state.enabled}")
            AppResult.Success(Unit)
        }.getOrElse { throwable ->
            logAndError("tv_home_row_enabled_error", throwable)
        }
    }

    private suspend fun publishPreviewRow(
        type: TvHomeChannelType,
        displayName: String,
        channels: List<ChannelEntity> = emptyList(),
        recordings: List<RecordingEntity> = emptyList()
    ): AppResult<Int> = withContext(Dispatchers.IO) {
        if (!supportsTvHome()) {
            addLog("tv_home_unsupported", "type=${type.name}, requires Android 8.0+")
            return@withContext unsupportedResult()
        }
        val state = tvHomeChannelDao.findByType(type.name)?.toModel()
            ?: TvHomeChannelState(type = type, providerChannelId = null, enabled = true, lastPublishedAt = null)
        if (!state.enabled) {
            addLog("tv_home_row_skipped", "type=${type.name}, reason=disabled")
            return@withContext AppResult.Success(0)
        }

        runCatching {
            val itemCount = channels.size + recordings.size
            if (itemCount == 0) {
                state.providerChannelId?.let { providerChannelId ->
                    deletePreviewPrograms(providerChannelId)
                    tvHomeChannelDao.markPublished(type.name, providerChannelId, System.currentTimeMillis())
                }
                addLog("tv_home_row_empty", "type=${type.name}, providerChannelId=${state.providerChannelId}, reason=no_items")
                return@withContext AppResult.Success(0)
            }

            val providerChannelId = ensureProviderChannel(type, displayName, state.providerChannelId)
            deletePreviewPrograms(providerChannelId)

            var inserted = 0
            channels.take(MAX_PREVIEW_PROGRAMS).forEachIndexed { index, channel ->
                val uri = context.contentResolver.insert(
                    TvContract.PreviewPrograms.CONTENT_URI,
                    previewValuesForChannel(channel, providerChannelId, index)
                )
                if (uri != null) inserted += 1
            }
            recordings.take(MAX_PREVIEW_PROGRAMS).forEachIndexed { index, recording ->
                val uri = context.contentResolver.insert(
                    TvContract.PreviewPrograms.CONTENT_URI,
                    previewValuesForRecording(recording, providerChannelId, index)
                )
                if (uri != null) inserted += 1
            }

            tvHomeChannelDao.markPublished(type.name, providerChannelId, System.currentTimeMillis())
            addLog("tv_home_row_published", "type=${type.name}, providerChannelId=$providerChannelId, items=$inserted")
            AppResult.Success(inserted)
        }.getOrElse { throwable ->
            logAndError("tv_home_row_error", throwable)
        }
    }

    @TargetApi(26)
    private suspend fun ensureProviderChannel(
        type: TvHomeChannelType,
        displayName: String,
        existingProviderChannelId: Long?
    ): Long {
        val values = ContentValues().apply {
            put(TvContract.Channels.COLUMN_TYPE, TvContract.Channels.TYPE_PREVIEW)
            put(TvContract.Channels.COLUMN_DISPLAY_NAME, displayName)
            put(TvContract.Channels.COLUMN_DESCRIPTION, "MyScaner IPTV: $displayName")
            put(TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID, providerIdFor(type))
            put(TvContract.Channels.COLUMN_APP_LINK_INTENT_URI, launchIntentUri())
        }

        if (existingProviderChannelId != null) {
            val uri = TvContract.buildChannelUri(existingProviderChannelId)
            val updated = context.contentResolver.update(uri, values, null, null)
            if (
                TvHomeProviderChannelRecovery.decide(
                    existingProviderChannelId = existingProviderChannelId,
                    existingUpdateRows = updated,
                    discoveredProviderChannelId = null,
                    discoveredUpdateRows = null
                ) == TvHomeProviderChannelAction.KEEP_EXISTING
            ) {
                return existingProviderChannelId
            }
            addLog(
                status = "tv_home_channel_missing",
                message = "type=${type.name}, staleProviderChannelId=$existingProviderChannelId"
            )
        }

        val discoveredProviderChannelId = findProviderChannelId(type)
        if (discoveredProviderChannelId != null && discoveredProviderChannelId != existingProviderChannelId) {
            val uri = TvContract.buildChannelUri(discoveredProviderChannelId)
            val updated = context.contentResolver.update(uri, values, null, null)
            if (
                TvHomeProviderChannelRecovery.decide(
                    existingProviderChannelId = existingProviderChannelId,
                    existingUpdateRows = 0,
                    discoveredProviderChannelId = discoveredProviderChannelId,
                    discoveredUpdateRows = updated
                ) == TvHomeProviderChannelAction.REUSE_DISCOVERED
            ) {
                TvContract.requestChannelBrowsable(context, discoveredProviderChannelId)
                addLog(
                    status = "tv_home_channel_recovered",
                    message = "type=${type.name}, providerChannelId=$discoveredProviderChannelId"
                )
                return discoveredProviderChannelId
            }
        }

        val uri = context.contentResolver.insert(TvContract.Channels.CONTENT_URI, values)
            ?: error("TvProvider did not create channel for ${type.name}")
        val channelId = ContentUris.parseId(uri)
        TvContract.requestChannelBrowsable(context, channelId)
        addLog("tv_home_channel_created", "type=${type.name}, providerChannelId=$channelId")
        return channelId
    }

    @TargetApi(26)
    private fun findProviderChannelId(type: TvHomeChannelType): Long? {
        val expectedProviderId = providerIdFor(type)
        context.contentResolver.query(
            TvContract.Channels.CONTENT_URI,
            arrayOf(BaseColumns._ID, TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            "${TvContract.Channels.COLUMN_INTERNAL_PROVIDER_ID} = ?",
            arrayOf(expectedProviderId),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            if (cursor.moveToFirst()) {
                return cursor.getLong(idColumn)
            }
        }
        return null
    }

    @TargetApi(26)
    private fun previewValuesForChannel(
        channel: ChannelEntity,
        providerChannelId: Long? = null,
        weight: Int = 0
    ): ContentValues {
        return ContentValues().apply {
            providerChannelId?.let { put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, it) }
            put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CHANNEL)
            put(TvContract.PreviewPrograms.COLUMN_TITLE, channel.name)
            put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, channel.groupName ?: "IPTV канал")
            put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, launchIntentUri("myscaneriptv://player/${channel.playlistId}/${channel.id}"))
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, "channel:${channel.id}")
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_DATA, channel.streamUrl)
            put(TvContract.PreviewPrograms.COLUMN_LIVE, 1)
            put(TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1)
            put(TvContract.PreviewPrograms.COLUMN_SEARCHABLE, 1)
            put(TvContract.PreviewPrograms.COLUMN_WEIGHT, MAX_PREVIEW_PROGRAMS - weight)
            put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.PreviewPrograms.ASPECT_RATIO_16_9)
            channel.logo?.takeIf { it.isLikelyUri() }?.let { logo ->
                put(TvContract.PreviewPrograms.COLUMN_LOGO_URI, logo)
                put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, logo)
                put(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, logo)
            }
        }
    }

    @TargetApi(26)
    private fun previewValuesForRecording(
        recording: RecordingEntity,
        providerChannelId: Long,
        weight: Int
    ): ContentValues {
        return ContentValues().apply {
            put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, providerChannelId)
            put(TvContract.PreviewPrograms.COLUMN_TYPE, TvContract.PreviewPrograms.TYPE_CLIP)
            put(TvContract.PreviewPrograms.COLUMN_TITLE, recording.programTitle ?: recording.channelName)
            put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, "Запись: ${recording.channelName}")
            put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, launchIntentUri("myscaneriptv://downloads"))
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID, "recording:${recording.id}")
            put(TvContract.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_DATA, recording.filePath.orEmpty())
            put(TvContract.PreviewPrograms.COLUMN_BROWSABLE, 1)
            put(TvContract.PreviewPrograms.COLUMN_SEARCHABLE, 1)
            put(TvContract.PreviewPrograms.COLUMN_WEIGHT, MAX_PREVIEW_PROGRAMS - weight)
            recording.startedAt?.let { put(TvContract.PreviewPrograms.COLUMN_START_TIME_UTC_MILLIS, it) }
            recording.endedAt?.let { put(TvContract.PreviewPrograms.COLUMN_END_TIME_UTC_MILLIS, it) }
        }
    }

    private suspend fun recentChannels(limit: Int): List<ChannelEntity> {
        val history = historyDao.observeHistory(limit = limit * 2).first()
        val ids = history.map { it.channelId }.distinct().take(limit)
        if (ids.isEmpty()) return emptyList()
        val byId = channelDao.findByIds(ids).associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    @TargetApi(26)
    private fun deletePreviewPrograms(providerChannelId: Long) {
        context.contentResolver.query(
            TvContract.buildPreviewProgramsUriForChannel(providerChannelId),
            arrayOf(BaseColumns._ID),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            while (cursor.moveToNext()) {
                val uri = TvContract.buildPreviewProgramUri(cursor.getLong(idColumn))
                context.contentResolver.delete(uri, null, null)
            }
        }
    }

    @TargetApi(26)
    private fun deleteExistingWatchNext() {
        context.contentResolver.query(
            TvContract.WatchNextPrograms.CONTENT_URI,
            arrayOf(BaseColumns._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(BaseColumns._ID)
            val providerColumn = cursor.getColumnIndexOrThrow(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            while (cursor.moveToNext()) {
                val providerId = cursor.getString(providerColumn).orEmpty()
                if (providerId.startsWith("channel:")) {
                    val uri = TvContract.buildWatchNextProgramUri(cursor.getLong(idColumn))
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
    }

    private fun clearPublishedState(state: TvHomeChannelState): TvHomeChannelState {
        if (!supportsTvHome()) {
            return state.copy(providerChannelId = null, lastPublishedAt = null)
        }

        if (state.type == TvHomeChannelType.WATCH_NEXT) {
            deleteExistingWatchNext()
            return state.copy(providerChannelId = null, lastPublishedAt = null)
        }

        state.providerChannelId?.let { providerChannelId ->
            deletePreviewPrograms(providerChannelId)
            context.contentResolver.delete(TvContract.buildChannelUri(providerChannelId), null, null)
        }
        return state.copy(providerChannelId = null, lastPublishedAt = null)
    }

    private fun launchIntentUri(deepLink: String? = null): String {
        val intent = if (deepLink != null) {
            Intent(Intent.ACTION_VIEW, Uri.parse(deepLink)).setPackage(context.packageName)
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return intent.toUri(Intent.URI_INTENT_SCHEME)
    }

    private suspend fun addLog(status: String, message: String) {
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = status,
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun logAndError(status: String, throwable: Throwable): AppResult.Error {
        val message = throwable.toLogSummary(maxDepth = 4)
        addLog(status, message)
        return AppResult.Error(message, throwable)
    }

    private fun providerIdFor(type: TvHomeChannelType): String {
        return "myscaner_iptv_${type.name.lowercase(Locale.US)}"
    }

    private fun supportsTvHome(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    private fun unsupportedResult(): AppResult<Int> {
        return AppResult.Error("Android TV Home integration requires Android 8.0+")
    }

    private fun String.isLikelyUri(): Boolean {
        val scheme = runCatching { Uri.parse(this).scheme }.getOrNull()
        return scheme == "http" || scheme == "https" || scheme == "content" || scheme == "file"
    }

    private companion object {
        const val MAX_PREVIEW_PROGRAMS = 30
    }
}
