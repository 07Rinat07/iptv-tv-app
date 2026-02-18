package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.DownloadRepository
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.DownloadTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val downloadDao: DownloadDao,
    private val syncLogDao: SyncLogDao
) : DownloadRepository {
    override fun observeDownloads(limit: Int): Flow<List<DownloadTask>> {
        return downloadDao.observeDownloads(limit).map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun enqueue(source: String): AppResult<DownloadTask> = withContext(Dispatchers.IO) {
        val normalized = source.trim()
        if (normalized.isBlank()) {
            return@withContext AppResult.Error("Источник загрузки пуст")
        }

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
                message = "Task enqueued id=$id",
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

        repeat(availableSlots) {
            val nextQueued = downloadDao.findFirstByStatus(DownloadStatus.QUEUED.name) ?: return@repeat
            downloadDao.updateState(
                downloadId = nextQueued.id,
                status = DownloadStatus.RUNNING.name,
                progress = nextQueued.progress.coerceAtLeast(1)
            )
            running += nextQueued.copy(
                status = DownloadStatus.RUNNING.name,
                progress = nextQueued.progress.coerceAtLeast(1)
            )
        }

        var processed = 0
        running.forEach { task ->
            val fresh = downloadDao.findById(task.id) ?: return@forEach
            if (fresh.status != DownloadStatus.RUNNING.name) return@forEach

            val nextProgress = (fresh.progress + progressStepFor(fresh.source)).coerceAtMost(100)
            val nextStatus = if (nextProgress >= 100) DownloadStatus.COMPLETED else DownloadStatus.RUNNING
            downloadDao.updateState(fresh.id, nextStatus.name, nextProgress)
            processed += 1
        }

        if (processed > 0) {
            syncLogDao.insert(
                SyncLogEntity(
                    playlistId = null,
                    status = "download_tick",
                    message = "Download queue processed tasks=$processed",
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        AppResult.Success(processed)
    }

    private fun progressStepFor(source: String): Int {
        val stableBucket = source.hashCode() and Int.MAX_VALUE
        val base = PROGRESS_STEP_BASE + (stableBucket % 6)
        return base.coerceIn(6, 18)
    }

    private companion object {
        const val MAX_CONCURRENT_DOWNLOADS = 5
        const val PROGRESS_STEP_BASE = 8
    }
}
