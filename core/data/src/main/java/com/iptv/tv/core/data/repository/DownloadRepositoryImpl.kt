package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.DownloadRepository
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.DownloadSourceType
import com.iptv.tv.core.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao,
    private val syncLogDao: SyncLogDao,
    private val engineRepository: EngineRepository
) : DownloadRepository {
    internal var storagePreflight = DownloadStoragePreflight { context.downloadStorageAvailableBytes() }
    internal var artifactWriter: DownloadArtifactWriter = AppDownloadArtifactWriter(context)

    override fun observeDownloads(limit: Int): Flow<List<DownloadTask>> {
        return downloadDao.observeDownloads(limit).map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun enqueue(source: String): AppResult<DownloadTask> = withContext(Dispatchers.IO) {
        val normalized = source.trim()
        if (normalized.isBlank()) {
            return@withContext AppResult.Error("Источник загрузки пуст")
        }
        val sourceType = DownloadSourceClassifier.classify(normalized)

        val entity = DownloadEntity(
            source = normalized,
            progress = 0,
            status = DownloadStatus.QUEUED.name,
            createdAt = System.currentTimeMillis()
        )
        val id = downloadDao.insert(entity)
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = "download_enqueued",
                message = "Task enqueued id=$id, sourceType=${sourceType.name}, externalEngine=${DownloadSourceClassifier.requiresExternalEngine(sourceType)}",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(entity.copy(id = id).toModel())
    }

    override suspend fun pause(downloadId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        val current = downloadDao.findById(downloadId)
            ?: return@withContext AppResult.Error("Задача не найдена: id=$downloadId")
        if (current.status == DownloadStatus.COMPLETED.name || current.status == DownloadStatus.CANCELED.name) {
            return@withContext AppResult.Error("Нельзя поставить на паузу завершенную/отмененную задачу")
        }
        downloadDao.updateStatus(downloadId, DownloadStatus.PAUSED.name)
        AppResult.Success(Unit)
    }

    override suspend fun resume(downloadId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        val current = downloadDao.findById(downloadId)
            ?: return@withContext AppResult.Error("Задача не найдена: id=$downloadId")
        if (current.status != DownloadStatus.PAUSED.name) {
            return@withContext AppResult.Error("Возобновлять можно только задачу в статусе PAUSED")
        }
        downloadDao.updateStatus(downloadId, DownloadStatus.QUEUED.name)
        AppResult.Success(Unit)
    }

    override suspend fun cancel(downloadId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        val current = downloadDao.findById(downloadId)
            ?: return@withContext AppResult.Error("Задача не найдена: id=$downloadId")
        if (current.status == DownloadStatus.COMPLETED.name) {
            return@withContext AppResult.Error("Нельзя отменить уже завершенную задачу")
        }
        downloadDao.updateStatus(downloadId, DownloadStatus.CANCELED.name)
        AppResult.Success(Unit)
    }

    override suspend fun remove(downloadId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        val removed = downloadDao.deleteById(downloadId)
        if (removed <= 0) return@withContext AppResult.Error("Задача не найдена: id=$downloadId")
        AppResult.Success(Unit)
    }

    override suspend fun tickQueue(maxConcurrent: Int): AppResult<Int> = withContext(Dispatchers.IO) {
        val safeConcurrent = maxConcurrent.coerceIn(1, MAX_CONCURRENT_DOWNLOADS)
        val running = downloadDao.findByStatus(DownloadStatus.RUNNING.name).toMutableList()
        val availableSlots = (safeConcurrent - running.size).coerceAtLeast(0)

        val startedTypes = mutableListOf<DownloadSourceType>()
        val resolvedSources = mutableMapOf<Long, ResolvedDownloadSource>()
        repeat(availableSlots) {
            val nextQueued = downloadDao.findFirstByStatus(DownloadStatus.QUEUED.name) ?: return@repeat
            val sourceType = DownloadSourceClassifier.classify(nextQueued.source)
            val storage = storagePreflight.evaluate(nextQueued.source, sourceType)
            if (!storage.allowed) {
                downloadDao.updateState(
                    downloadId = nextQueued.id,
                    status = DownloadStatus.FAILED.name,
                    progress = nextQueued.progress
                )
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = "download_storage_error",
                        message = "downloadId=${nextQueued.id}, sourceType=${sourceType.name}, " +
                            "estimatedBytes=${storage.estimatedBytes}, availableBytes=${storage.availableBytes}, " +
                            "reserveBytes=${storage.reserveBytes}, reason=${storage.reason}",
                        createdAt = System.currentTimeMillis()
                    )
                )
                return@repeat
            }
            val startProgress = if (DownloadSourceClassifier.requiresExternalEngine(sourceType)) {
                when (val resolveResult = engineRepository.resolveTorrentStream(nextQueued.source)) {
                    is AppResult.Success -> {
                        val resolvedSourceType = DownloadSourceClassifier.classify(resolveResult.data)
                        downloadDao.updateResolvedSource(nextQueued.id, resolveResult.data, resolvedSourceType.name)
                        resolvedSources[nextQueued.id] = ResolvedDownloadSource(
                            source = resolveResult.data,
                            sourceType = resolvedSourceType,
                            originalSourceType = sourceType
                        )
                        syncLogDao.insert(
                            SyncLogEntity(
                                playlistId = null,
                                status = "download_engine_resolved",
                                message = "downloadId=${nextQueued.id}, sourceType=${sourceType.name}, resolvedType=${resolvedSourceType.name}, resolved=${resolveResult.data.take(LOG_VALUE_LIMIT)}",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        nextQueued.progress.coerceAtLeast(ENGINE_RESOLVED_PROGRESS)
                    }
                    is AppResult.Error -> {
                        downloadDao.updateState(
                            downloadId = nextQueued.id,
                            status = DownloadStatus.FAILED.name,
                            progress = nextQueued.progress
                        )
                        syncLogDao.insert(
                            SyncLogEntity(
                                playlistId = null,
                                status = DownloadSourceClassifier.engineFailureLogStatus(resolveResult.message),
                                message = "downloadId=${nextQueued.id}, sourceType=${sourceType.name}, reason=${resolveResult.message.take(LOG_VALUE_LIMIT)}",
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        return@repeat
                    }
                    AppResult.Loading -> return@repeat
                }
            } else {
                nextQueued.progress.coerceAtLeast(1)
            }
            downloadDao.updateState(
                downloadId = nextQueued.id,
                status = DownloadStatus.RUNNING.name,
                progress = startProgress
            )
            startedTypes += sourceType
            running += nextQueued.copy(
                status = DownloadStatus.RUNNING.name,
                progress = startProgress
            )
        }

        var processed = 0
        val processedTypes = mutableMapOf<DownloadSourceType, Int>()
        running.forEach { task ->
            val fresh = downloadDao.findById(task.id) ?: return@forEach
            if (fresh.status != DownloadStatus.RUNNING.name) return@forEach

            val sourceType = DownloadSourceClassifier.classify(fresh.source)
            val artifactSource = resolvedSources[fresh.id]
                ?: fresh.toResolvedDownloadSource(sourceType)
                ?: prepareArtifactSource(fresh.id, fresh.source, sourceType, fresh.progress)
                ?: return@forEach
            when (val artifact = artifactWriter.write(fresh.id, artifactSource.source, artifactSource.sourceType)) {
                is DownloadArtifactResult.Completed -> {
                    downloadDao.updateState(fresh.id, DownloadStatus.COMPLETED.name, 100)
                    syncLogDao.insert(
                        SyncLogEntity(
                            playlistId = null,
                            status = "download_file_saved",
                            message = "downloadId=${fresh.id}, sourceType=${artifactSource.originalSourceType.name}, " +
                                "artifactType=${artifactSource.sourceType.name}, " +
                                "bytes=${artifact.bytesWritten}, file=${artifact.filePath.take(LOG_VALUE_LIMIT)}",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                is DownloadArtifactResult.Failed -> {
                    downloadDao.updateState(fresh.id, DownloadStatus.FAILED.name, fresh.progress)
                    syncLogDao.insert(
                        SyncLogEntity(
                            playlistId = null,
                            status = "download_file_error",
                            message = "downloadId=${fresh.id}, sourceType=${artifactSource.originalSourceType.name}, " +
                                "artifactType=${artifactSource.sourceType.name}, reason=${artifact.reason.take(LOG_VALUE_LIMIT)}",
                            createdAt = System.currentTimeMillis()
                        )
                    )
                }
                DownloadArtifactResult.Unsupported -> {
                    val nextProgress = (fresh.progress + progressStepFor(fresh.source, artifactSource.originalSourceType)).coerceAtMost(100)
                    val nextStatus = if (nextProgress >= 100) DownloadStatus.COMPLETED else DownloadStatus.RUNNING
                    downloadDao.updateState(fresh.id, nextStatus.name, nextProgress)
                }
            }
            processedTypes[artifactSource.sourceType] = (processedTypes[artifactSource.sourceType] ?: 0) + 1
            processed += 1
        }

        if (processed > 0) {
            syncLogDao.insert(
                SyncLogEntity(
                    playlistId = null,
                    status = "download_tick",
                    message = "Download queue processed tasks=$processed, started=${startedTypes.toTypeSummary()}, processedTypes=${processedTypes.toTypeSummary()}",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        AppResult.Success(processed)
    }

    private suspend fun prepareArtifactSource(
        downloadId: Long,
        source: String,
        sourceType: DownloadSourceType,
        currentProgress: Int
    ): ResolvedDownloadSource? {
        if (!DownloadSourceClassifier.requiresExternalEngine(sourceType)) {
            return ResolvedDownloadSource(
                source = source,
                sourceType = sourceType,
                originalSourceType = sourceType
            )
        }
        return when (val resolveResult = engineRepository.resolveTorrentStream(source)) {
            is AppResult.Success -> {
                val resolvedSourceType = DownloadSourceClassifier.classify(resolveResult.data)
                downloadDao.updateResolvedSource(downloadId, resolveResult.data, resolvedSourceType.name)
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = "download_engine_resolved",
                        message = "downloadId=$downloadId, sourceType=${sourceType.name}, resolvedType=${resolvedSourceType.name}, resolved=${resolveResult.data.take(LOG_VALUE_LIMIT)}",
                        createdAt = System.currentTimeMillis()
                    )
                )
                ResolvedDownloadSource(
                    source = resolveResult.data,
                    sourceType = resolvedSourceType,
                    originalSourceType = sourceType
                )
            }
            is AppResult.Error -> {
                downloadDao.updateState(
                    downloadId = downloadId,
                    status = DownloadStatus.FAILED.name,
                    progress = currentProgress
                )
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = DownloadSourceClassifier.engineFailureLogStatus(resolveResult.message),
                        message = "downloadId=$downloadId, sourceType=${sourceType.name}, reason=${resolveResult.message.take(LOG_VALUE_LIMIT)}",
                        createdAt = System.currentTimeMillis()
                    )
                )
                null
            }
            AppResult.Loading -> null
        }
    }

    private fun progressStepFor(source: String, sourceType: DownloadSourceType): Int {
        val stableBucket = source.hashCode() and Int.MAX_VALUE
        val typeBase = when (sourceType) {
            DownloadSourceType.MAGNET,
            DownloadSourceType.ACESTREAM,
            DownloadSourceType.TORRENT_FILE -> TORRENT_PROGRESS_STEP_BASE
            DownloadSourceType.HLS_PLAYLIST -> HLS_PROGRESS_STEP_BASE
            DownloadSourceType.HTTP_STREAM,
            DownloadSourceType.LOCAL_FILE,
            DownloadSourceType.CUSTOM -> PROGRESS_STEP_BASE
        }
        val base = typeBase + (stableBucket % 6)
        return base.coerceIn(6, 18)
    }

    private fun List<DownloadSourceType>.toTypeSummary(): String {
        if (isEmpty()) return "none"
        return groupingBy { it }.eachCount()
            .entries
            .joinToString("|") { "${it.key.name}:${it.value}" }
    }

    private fun Map<DownloadSourceType, Int>.toTypeSummary(): String {
        if (isEmpty()) return "none"
        return entries.joinToString("|") { "${it.key.name}:${it.value}" }
    }

    private companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 5
        const val PROGRESS_STEP_BASE = 8
        const val HLS_PROGRESS_STEP_BASE = 7
        const val TORRENT_PROGRESS_STEP_BASE = 6
        const val ENGINE_RESOLVED_PROGRESS = 10
        const val LOG_VALUE_LIMIT = 160
    }
}

private data class ResolvedDownloadSource(
    val source: String,
    val sourceType: DownloadSourceType,
    val originalSourceType: DownloadSourceType
)

private fun DownloadEntity.toResolvedDownloadSource(originalSourceType: DownloadSourceType): ResolvedDownloadSource? {
    if (!DownloadSourceClassifier.requiresExternalEngine(originalSourceType)) return null
    val source = resolvedSource?.takeIf { it.isNotBlank() } ?: return null
    val type = resolvedSourceType
        ?.let { raw -> runCatching { DownloadSourceType.valueOf(raw) }.getOrNull() }
        ?: DownloadSourceClassifier.classify(source)
    return ResolvedDownloadSource(
        source = source,
        sourceType = type,
        originalSourceType = originalSourceType
    )
}

private fun Context.downloadStorageAvailableBytes(): Long? {
    return listOfNotNull(
        filesDir,
        cacheDir,
        runCatching { getExternalFilesDir(null) }.getOrNull()
    )
        .mapNotNull { dir -> dir.safeUsableSpace() }
        .maxOrNull()
}

private fun File.safeUsableSpace(): Long? {
    return runCatching {
        takeIf { exists() || mkdirs() }?.usableSpace
    }.getOrNull()
}
