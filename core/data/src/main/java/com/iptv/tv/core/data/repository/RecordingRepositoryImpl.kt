package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toEntity
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.RecordingDao
import com.iptv.tv.core.database.dao.RecordingScheduleDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.RecordingEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.RecordingRepository
import com.iptv.tv.core.model.RecordingSchedule
import com.iptv.tv.core.model.RecordingStatus
import com.iptv.tv.core.model.RecordingTask
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingDao: RecordingDao,
    private val recordingScheduleDao: RecordingScheduleDao,
    private val channelDao: ChannelDao,
    private val syncLogDao: SyncLogDao,
    private val okHttpClient: OkHttpClient
) : RecordingRepository {
    override fun observeRecordings(limit: Int): Flow<List<RecordingTask>> {
        return recordingDao.observeRecordings(limit).map { rows -> rows.map { it.toModel() } }
    }

    override fun observeSchedules(): Flow<List<RecordingSchedule>> {
        return recordingScheduleDao.observeSchedules().map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun scheduleRecording(schedule: RecordingSchedule): AppResult<Long> = withContext(Dispatchers.IO) {
        if (schedule.channelId <= 0) return@withContext AppResult.Error("Некорректный канал для записи")
        if (schedule.endAt <= schedule.startAt) return@withContext AppResult.Error("Время окончания записи должно быть позже начала")

        val channel = channelDao.findById(schedule.channelId)
            ?: return@withContext AppResult.Error("Канал не найден: id=${schedule.channelId}")
        val now = System.currentTimeMillis()
        val normalized = schedule.copy(
            channelName = channel.name,
            createdAt = schedule.createdAt.takeIf { it > 0 } ?: now
        )
        val scheduleId = recordingScheduleDao.upsert(normalized.toEntity())
        val recordingId = recordingDao.upsert(
            RecordingEntity(
                channelId = channel.id,
                channelName = channel.name,
                programTitle = normalized.programTitle,
                streamUrl = channel.streamUrl,
                filePath = null,
                status = RecordingStatus.SCHEDULED.name,
                startedAt = null,
                endedAt = null,
                scheduledStartAt = normalized.startAt,
                scheduledEndAt = normalized.endAt,
                createdAt = now
            )
        )
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = channel.playlistId,
                status = "recording_scheduled",
                message = "scheduleId=$scheduleId, recordingId=$recordingId, channelId=${channel.id}",
                createdAt = now
            )
        )
        AppResult.Success(recordingId)
    }

    override suspend fun startRecordingNow(channelId: Long, programTitle: String?): AppResult<Long> = withContext(Dispatchers.IO) {
        val channel = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Канал не найден: id=$channelId")
        val now = System.currentTimeMillis()
        val recordingId = recordingDao.upsert(
            RecordingEntity(
                channelId = channel.id,
                channelName = channel.name,
                programTitle = programTitle?.trim()?.ifBlank { null } ?: "Ручная запись: ${channel.name}",
                streamUrl = channel.streamUrl,
                filePath = null,
                status = RecordingStatus.SCHEDULED.name,
                startedAt = null,
                endedAt = null,
                scheduledStartAt = now,
                scheduledEndAt = now + DEFAULT_RECORDING_DURATION_MS,
                createdAt = now
            )
        )
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = channel.playlistId,
                status = "recording_now_queued",
                message = "recordingId=$recordingId, channelId=${channel.id}",
                createdAt = now
            )
        )
        AppResult.Success(recordingId)
    }

    override suspend fun cancelRecording(recordingId: Long): AppResult<Int> = withContext(Dispatchers.IO) {
        val current = recordingDao.findById(recordingId)
            ?: return@withContext AppResult.Error("Запись не найдена: id=$recordingId")
        if (current.status == RecordingStatus.COMPLETED.name) {
            return@withContext AppResult.Error("Нельзя отменить завершенную запись")
        }
        val updated = recordingDao.markFinished(
            recordingId = recordingId,
            status = RecordingStatus.CANCELED.name,
            endedAt = System.currentTimeMillis()
        )
        AppResult.Success(updated)
    }

    override suspend fun deleteRecording(recordingId: Long, deleteFile: Boolean): AppResult<Int> = withContext(Dispatchers.IO) {
        val current = recordingDao.findById(recordingId)
            ?: return@withContext AppResult.Error("Запись не найдена: id=$recordingId")
        if (current.status == RecordingStatus.RECORDING.name) {
            return@withContext AppResult.Error("Нельзя удалить запись, которая сейчас идёт. Сначала отмените её.")
        }
        if (deleteFile) {
            deleteRecordingFile(current.filePath)
        }
        val removed = recordingDao.deleteById(recordingId)
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = "recording_deleted",
                message = "recordingId=$recordingId, deleteFile=$deleteFile, removed=$removed",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(removed)
    }

    override suspend fun cleanupOldRecordings(maxAgeDays: Int): AppResult<Int> = withContext(Dispatchers.IO) {
        val normalizedDays = maxAgeDays.coerceIn(1, 365)
        val before = System.currentTimeMillis() - normalizedDays * 24L * 60L * 60L * 1000L
        val oldRecordings = recordingDao.findOlderThan(
            statuses = listOf(
                RecordingStatus.COMPLETED.name,
                RecordingStatus.FAILED.name,
                RecordingStatus.CANCELED.name
            ),
            beforeEpochMs = before
        )
        var removed = 0
        oldRecordings.forEach { recording ->
            deleteRecordingFile(recording.filePath)
            removed += recordingDao.deleteById(recording.id)
        }
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = "recording_cleanup",
                message = "maxAgeDays=$normalizedDays, removed=$removed",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(removed)
    }

    override suspend fun deleteSchedule(scheduleId: Long): AppResult<Int> = withContext(Dispatchers.IO) {
        val removed = recordingScheduleDao.deleteById(scheduleId)
        if (removed <= 0) return@withContext AppResult.Error("Расписание не найдено: id=$scheduleId")
        AppResult.Success(removed)
    }

    override suspend fun processDueRecordings(maxConcurrent: Int): AppResult<Int> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val due = recordingDao.findByStatus(RecordingStatus.SCHEDULED.name)
            .filter { recording ->
                val startAt = recording.scheduledStartAt ?: recording.createdAt
                startAt <= now
            }
            .take(maxConcurrent.coerceIn(1, MAX_CONCURRENT_RECORDINGS))

        var processed = 0
        due.forEach { recording ->
            val result = runCatching { recordToInternalFile(recording) }
            if (result.getOrDefault(false)) {
                processed += 1
            }
        }
        AppResult.Success(processed)
    }

    private suspend fun recordToInternalFile(recording: RecordingEntity): Boolean {
        val prepared = parseKodiStyleStream(recording.streamUrl)
        val streamUrl = prepared.first
        if (!streamUrl.startsWith("http://", ignoreCase = true) &&
            !streamUrl.startsWith("https://", ignoreCase = true)
        ) {
            markRecordingFailed(recording, "Only http/https streams can be recorded in MVP")
            return false
        }

        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        val extension = extensionForStreamUrl(streamUrl)
        val file = File(directory, recordingFileName(recording, extension))
        val startedAt = System.currentTimeMillis()
        recordingDao.markStarted(
            recordingId = recording.id,
            status = RecordingStatus.RECORDING.name,
            filePath = file.absolutePath,
            startedAt = startedAt
        )

        val requestBuilder = Request.Builder().url(streamUrl).get()
        prepared.second.forEach { (key, value) -> requestBuilder.header(key, value) }

        try {
            okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty stream body")
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        val hardEndAt = listOfNotNull(
                            recording.scheduledEndAt,
                            startedAt + MAX_RECORDING_DURATION_MS
                        ).minOrNull() ?: (startedAt + MAX_RECORDING_DURATION_MS)

                        while (System.currentTimeMillis() < hardEndAt && written < MAX_RECORDING_BYTES) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read
                        }
                    }
                }
            }
            recordingDao.markFinished(recording.id, RecordingStatus.COMPLETED.name, System.currentTimeMillis())
            syncLogDao.insert(
                SyncLogEntity(
                    playlistId = null,
                    status = "recording_completed",
                    message = "recordingId=${recording.id}, file=${file.absolutePath}",
                    createdAt = System.currentTimeMillis()
                )
            )
            return true
        } catch (throwable: Throwable) {
            markRecordingFailed(recording, throwable.message ?: throwable.javaClass.simpleName)
            return false
        }
    }

    private suspend fun markRecordingFailed(recording: RecordingEntity, reason: String) {
        recordingDao.markFinished(recording.id, RecordingStatus.FAILED.name, System.currentTimeMillis())
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = "recording_failed",
                message = "recordingId=${recording.id}, reason=${reason.take(250)}",
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun deleteRecordingFile(filePath: String?) {
        val normalizedPath = filePath?.trim().orEmpty()
        if (normalizedPath.isBlank()) return
        val file = File(normalizedPath)
        val recordingsDir = File(context.filesDir, "recordings").canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (!target.path.startsWith(recordingsDir.path)) return
        if (target.exists()) target.delete()
    }

    private fun recordingFileName(recording: RecordingEntity, extension: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = "${recording.channelName}_${recording.programTitle.orEmpty()}"
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('_')
            .ifBlank { "recording_${recording.id}" }
            .take(80)
        return "${timestamp}_${recording.id}_$safeName.$extension"
    }

    private fun extensionForStreamUrl(url: String): String {
        val lowered = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            lowered.endsWith(".mp4") -> "mp4"
            lowered.endsWith(".mkv") -> "mkv"
            lowered.endsWith(".m3u8") -> "ts"
            else -> "ts"
        }
    }

    private fun parseKodiStyleStream(raw: String): Pair<String, Map<String, String>> {
        val parts = raw.split("|", limit = 2)
        val url = parts.first().trim()
        val headers = parts.getOrNull(1)
            ?.split("&")
            ?.mapNotNull { item ->
                val pair = item.split("=", limit = 2)
                if (pair.size != 2) null else pair[0].trim() to pair[1].trim()
            }
            ?.associate { (key, value) ->
                when (key.lowercase(Locale.ROOT)) {
                    "user-agent" -> "User-Agent"
                    "referer", "referrer" -> "Referer"
                    else -> key
                } to value
            }
            .orEmpty()
        return url to headers
    }

    private companion object {
        const val DEFAULT_RECORDING_DURATION_MS = 2 * 60 * 60 * 1000L
        const val MAX_RECORDING_DURATION_MS = 15 * 60 * 1000L
        const val MAX_RECORDING_BYTES = 256L * 1024L * 1024L
        const val MAX_CONCURRENT_RECORDINGS = 2
    }
}
