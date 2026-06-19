package com.iptv.tv.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
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
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.RecordingSchedule
import com.iptv.tv.core.model.RecordingStatus
import com.iptv.tv.core.model.RecordingStorageLocation
import com.iptv.tv.core.model.RecordingTask
import com.iptv.tv.core.model.TimeshiftBufferPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
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
    private val settingsRepository: SettingsRepository,
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
                scheduledEndAt = RecordingLimits.defaultScheduledEndAt(now),
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

    override suspend fun setScheduleEnabled(scheduleId: Long, enabled: Boolean): AppResult<Int> = withContext(Dispatchers.IO) {
        val schedule = recordingScheduleDao.findById(scheduleId)
            ?: return@withContext AppResult.Error("Расписание не найдено: id=$scheduleId")
        val updated = recordingScheduleDao.setEnabled(scheduleId, enabled)
        if (updated <= 0) return@withContext AppResult.Error("Расписание не обновлено: id=$scheduleId")

        val now = System.currentTimeMillis()
        val linkedUpdates = if (!enabled) {
            recordingDao.updateMatchingScheduledStatus(
                channelId = schedule.channelId,
                startAt = schedule.startAt,
                endAt = schedule.endAt,
                currentStatus = RecordingStatus.SCHEDULED.name,
                status = RecordingStatus.CANCELED.name,
                endedAt = now
            )
        } else {
            ensureScheduledRecording(schedule.toModel(), now)
        }
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = "recording_schedule_enabled_changed",
                message = "scheduleId=$scheduleId, enabled=$enabled, linkedUpdates=$linkedUpdates",
                createdAt = now
            )
        )
        AppResult.Success(updated + linkedUpdates)
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

    override suspend fun planTimeshiftBuffer(
        channelId: Long,
        requestedMinutes: Int
    ): AppResult<TimeshiftBufferPlan> = withContext(Dispatchers.IO) {
        val channel = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Канал не найден: id=$channelId")
        val location = settingsRepository.observeRecordingStorageLocation().first()
        val storageInfo = settingsRepository.getRecordingStorageInfo(location)
        val plan = TimeshiftBufferPlanner.plan(
            channelId = channel.id,
            channelName = channel.name,
            rawStreamUrl = channel.streamUrl,
            storageInfo = storageInfo,
            requestedMinutes = requestedMinutes
        )
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = channel.playlistId,
                status = if (plan.supported) "timeshift_plan_ready" else "timeshift_plan_blocked",
                message = "channelId=${channel.id}, minutes=${plan.requestedDurationMinutes}, " +
                    "maxMinutes=${plan.maxDurationMinutes}, source=${plan.sourceType}, reason=${plan.reason}",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(plan)
    }

    private suspend fun ensureScheduledRecording(schedule: RecordingSchedule, now: Long): Int {
        val existing = recordingDao.findMatchingScheduled(
            channelId = schedule.channelId,
            startAt = schedule.startAt,
            endAt = schedule.endAt,
            statuses = listOf(RecordingStatus.SCHEDULED.name, RecordingStatus.RECORDING.name)
        )
        if (existing != null) return 0
        val channel = channelDao.findById(schedule.channelId) ?: return 0
        recordingDao.upsert(
            RecordingEntity(
                channelId = channel.id,
                channelName = channel.name,
                programTitle = schedule.programTitle,
                streamUrl = channel.streamUrl,
                filePath = null,
                status = RecordingStatus.SCHEDULED.name,
                startedAt = null,
                endedAt = null,
                scheduledStartAt = schedule.startAt,
                scheduledEndAt = schedule.endAt,
                createdAt = now
            )
        )
        return 1
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

        val extension = extensionForStreamUrl(streamUrl)
        val storageLocation = settingsRepository.observeRecordingStorageLocation().first()
        val target = prepareRecordingTarget(recording, storageLocation, extension) ?: return false
        val startedAt = System.currentTimeMillis()
        val maxBytes = target.maxBytes
        val hardEndAt = RecordingLimits.hardEndAt(startedAt, recording.scheduledEndAt)
        recordingDao.markStarted(
            recordingId = recording.id,
            status = RecordingStatus.RECORDING.name,
            filePath = target.storedPath,
            startedAt = startedAt
        )

        val requestBuilder = Request.Builder().url(streamUrl).get()
        prepared.second.forEach { (key, value) -> requestBuilder.header(key, value) }

        try {
            if (streamUrl.isHlsPlaylistUrl()) {
                writeHlsMergedStream(
                    recordingId = recording.id,
                    playlistUrl = streamUrl,
                    headers = prepared.second,
                    target = target,
                    hardEndAt = hardEndAt
                )
            } else {
                writeDirectStream(
                    request = requestBuilder.build(),
                    target = target,
                    hardEndAt = hardEndAt
                )
            }
            recordingDao.markFinished(recording.id, RecordingStatus.COMPLETED.name, System.currentTimeMillis())
            syncLogDao.insert(
                SyncLogEntity(
                    playlistId = null,
                    status = "recording_completed",
                    message = "recordingId=${recording.id}, storage=${storageLocation.name}, bytesLimit=$maxBytes, hardEndAt=$hardEndAt, file=${target.debugPath}",
                    createdAt = System.currentTimeMillis()
                )
            )
            return true
        } catch (throwable: Throwable) {
            markRecordingFailed(recording, throwable.message ?: throwable.javaClass.simpleName)
            return false
        }
    }

    private suspend fun writeDirectStream(
        request: Request,
        target: RecordingTarget,
        hardEndAt: Long
    ) {
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty stream body")
            target.openOutputStream().use { output ->
                body.byteStream().use { input ->
                    copyInputStream(
                        initialWritten = 0L,
                        maxBytes = target.maxBytes,
                        hardEndAt = hardEndAt,
                        input = input,
                        output = output
                    )
                }
            }
        }
    }

    private suspend fun writeHlsMergedStream(
        recordingId: Long,
        playlistUrl: String,
        headers: Map<String, String>,
        target: RecordingTarget,
        hardEndAt: Long
    ) {
        var currentPlaylistUrl = playlistUrl
        val seenSegments = linkedSetOf<String>()
        var written = 0L
        var loggedDiscontinuities = 0

        target.openOutputStream().use { output ->
            while (System.currentTimeMillis() < hardEndAt && written < target.maxBytes) {
                when (val manifest = fetchHlsManifest(currentPlaylistUrl, headers)) {
                    is HlsPlaylistParser.Manifest.Master -> {
                        currentPlaylistUrl = HlsPlaylistParser.selectPreferredVariant(manifest)
                            ?: throw IOException("Empty HLS master playlist")
                    }

                    is HlsPlaylistParser.Manifest.Media -> {
                        if (manifest.encrypted) {
                            val encryptionMethods = manifest.encryptionMethodsSummary()
                            syncLogDao.insert(
                                SyncLogEntity(
                                    playlistId = null,
                                    status = "recording_hls_encrypted",
                                    message = "recordingId=$recordingId, playlist=$currentPlaylistUrl, methods=$encryptionMethods",
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            throw IOException("Encrypted HLS playlists are not supported yet: $encryptionMethods")
                        }
                        if (manifest.discontinuityCount > loggedDiscontinuities) {
                            syncLogDao.insert(
                                SyncLogEntity(
                                    playlistId = null,
                                    status = "recording_hls_discontinuity",
                                    message = "recordingId=$recordingId, playlist=$currentPlaylistUrl, discontinuities=${manifest.discontinuityCount}",
                                    createdAt = System.currentTimeMillis()
                                )
                            )
                            loggedDiscontinuities = manifest.discontinuityCount
                        }
                        val newSegments = manifest.segments.filterNot(seenSegments::contains)
                        if (newSegments.isEmpty()) {
                            if (manifest.endList) break
                            delay(manifest.pollDelayMs())
                            continue
                        }

                        newSegments.forEach { segmentUrl ->
                            if (System.currentTimeMillis() >= hardEndAt || written >= target.maxBytes) return@forEach
                            val appended = appendHttpResource(
                                url = segmentUrl,
                                headers = headers,
                                output = output,
                                initialWritten = written,
                                maxBytes = target.maxBytes,
                                hardEndAt = hardEndAt
                            )
                            written += appended
                            seenSegments += segmentUrl
                        }

                        if (manifest.endList && seenSegments.containsAll(manifest.segments)) {
                            break
                        }
                        if (!manifest.endList) {
                            delay(manifest.pollDelayMs())
                        }
                    }
                }
            }
        }
    }

    private fun fetchHlsManifest(
        url: String,
        headers: Map<String, String>
    ): HlsPlaylistParser.Manifest {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty HLS playlist body")
            return HlsPlaylistParser.parse(url = url, content = body)
        }
    }

    private fun appendHttpResource(
        url: String,
        headers: Map<String, String>,
        output: OutputStream,
        initialWritten: Long,
        maxBytes: Long,
        hardEndAt: Long
    ): Long {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty HLS segment body")
            body.byteStream().use { input ->
                return copyInputStream(
                    initialWritten = initialWritten,
                    maxBytes = maxBytes,
                    hardEndAt = hardEndAt,
                    input = input,
                    output = output
                )
            }
        }
    }

    private fun copyInputStream(
        initialWritten: Long,
        maxBytes: Long,
        hardEndAt: Long,
        input: java.io.InputStream,
        output: OutputStream
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var appended = 0L
        while (System.currentTimeMillis() < hardEndAt && initialWritten + appended < maxBytes) {
            val read = input.read(buffer)
            if (read <= 0) break
            val remaining = maxBytes - initialWritten - appended
            if (remaining <= 0L) break
            val bytesToWrite = minOf(read.toLong(), remaining).toInt()
            output.write(buffer, 0, bytesToWrite)
            appended += bytesToWrite
            if (bytesToWrite < read) break
        }
        return appended
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

    private suspend fun prepareRecordingTarget(
        recording: RecordingEntity,
        location: RecordingStorageLocation,
        extension: String
    ): RecordingTarget? {
        return when (location) {
            RecordingStorageLocation.CUSTOM_EXTERNAL -> prepareCustomRecordingTarget(recording, extension)
            RecordingStorageLocation.INTERNAL,
            RecordingStorageLocation.APP_EXTERNAL -> prepareFileRecordingTarget(recording, location, extension)
        }
    }

    private suspend fun prepareFileRecordingTarget(
        recording: RecordingEntity,
        location: RecordingStorageLocation,
        extension: String
    ): RecordingTarget? {
        val directory = recordingDirectory(location)
        if (!directory.exists() && !directory.mkdirs()) {
            markRecordingFailed(recording, "Cannot create recording folder: ${directory.absolutePath}")
            return null
        }
        if (!directory.isDirectory || !directory.canWrite()) {
            markRecordingFailed(recording, "Recording folder is not writable: ${directory.absolutePath}")
            return null
        }
        val maxBytes = RecordingLimits.maxRecordingBytes(directory.usableSpace)
        if (maxBytes <= 0L) {
            markRecordingFailed(
                recording,
                "Not enough free space in recording folder: ${directory.usableSpace} bytes"
            )
            return null
        }
        val file = File(directory, recordingFileName(recording, extension))
        return RecordingTarget(
            storedPath = file.absolutePath,
            debugPath = file.absolutePath,
            maxBytes = maxBytes,
            openOutputStream = { file.outputStream() }
        )
    }

    private suspend fun prepareCustomRecordingTarget(
        recording: RecordingEntity,
        extension: String
    ): RecordingTarget? {
        val treeUri = settingsRepository.observeRecordingStorageCustomTreeUri().first()
        if (treeUri.isNullOrBlank()) {
            markRecordingFailed(recording, "Custom recording folder is not configured")
            return null
        }
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
        if (root == null || !root.exists()) {
            markRecordingFailed(recording, "Custom recording folder is unavailable")
            return null
        }
        if (!root.canWrite()) {
            markRecordingFailed(recording, "Custom recording folder is not writable")
            return null
        }
        val fileName = recordingFileName(recording, extension)
        val document = root.createFile(mimeTypeForRecordingExtension(extension), fileName)
        if (document == null) {
            markRecordingFailed(recording, "Unable to create recording file in custom folder")
            return null
        }
        return RecordingTarget(
            storedPath = document.uri.toString(),
            debugPath = document.uri.toString(),
            maxBytes = RecordingLimits.ABSOLUTE_MAX_RECORDING_BYTES,
            openOutputStream = {
                context.contentResolver.openOutputStream(document.uri, "w")
                    ?: throw IOException("Cannot open output stream for ${document.uri}")
            }
        )
    }

    private fun deleteRecordingFile(filePath: String?) {
        val normalizedPath = filePath?.trim().orEmpty()
        if (normalizedPath.isBlank()) return
        if (normalizedPath.startsWith("content://", ignoreCase = true)) {
            deleteContentRecordingFile(normalizedPath)
            return
        }
        val file = File(normalizedPath)
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        val allowedRoots = recordingDirectories().mapNotNull { root ->
            runCatching { root.canonicalFile }.getOrNull()
        }
        if (allowedRoots.none { root -> target.path.startsWith(root.path) }) return
        if (target.exists()) target.delete()
    }

    private fun deleteContentRecordingFile(filePath: String) {
        val uri = runCatching { Uri.parse(filePath) }.getOrNull() ?: return
        val document = DocumentFile.fromSingleUri(context, uri)
        if (document?.exists() == true) {
            document.delete()
        }
    }

    private fun recordingDirectory(location: RecordingStorageLocation): File {
        return when (location) {
            RecordingStorageLocation.INTERNAL -> File(context.filesDir, "recordings")
            RecordingStorageLocation.APP_EXTERNAL -> {
                val external = context.getExternalFilesDir(null)
                if (external == null) {
                    File(context.filesDir, "recordings")
                } else {
                    File(external, "recordings")
                }
            }
            RecordingStorageLocation.CUSTOM_EXTERNAL -> File(context.filesDir, "recordings")
        }
    }

    private fun recordingDirectories(): List<File> {
        return listOfNotNull(
            File(context.filesDir, "recordings"),
            context.getExternalFilesDir(null)?.let { File(it, "recordings") }
        )
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

    private fun mimeTypeForRecordingExtension(extension: String): String {
        return when (extension.lowercase(Locale.ROOT)) {
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "ts", "m2ts" -> "video/mp2t"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "aac" -> "audio/aac"
            else -> "video/*"
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
        const val MAX_CONCURRENT_RECORDINGS = 2
    }
}

private data class RecordingTarget(
    val storedPath: String,
    val debugPath: String,
    val maxBytes: Long,
    val openOutputStream: () -> OutputStream
)

private fun String.isHlsPlaylistUrl(): Boolean {
    return substringBefore('?').endsWith(".m3u8", ignoreCase = true)
}

private fun HlsPlaylistParser.Manifest.Media.pollDelayMs(): Long {
    return (targetDurationSeconds ?: 5L).coerceIn(1L, 15L) * 1000L
}

private fun HlsPlaylistParser.Manifest.Media.encryptionMethodsSummary(): String {
    return encryptionMethods.sorted().joinToString(separator = ", ").ifBlank { "UNKNOWN" }
}
