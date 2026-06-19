package com.iptv.tv.core.data

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.repository.DownloadRepositoryImpl
import com.iptv.tv.core.data.repository.DownloadStoragePreflight
import com.iptv.tv.core.database.dao.DownloadDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.EngineStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRepositoryImplTest {

    @Test
    fun tickQueue_resolvesTorrentTaskThroughEngineBeforeRunning() = runTest {
        val downloadDao = mockk<DownloadDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val engineRepository = fakeEngineRepository(
            resolveResult = AppResult.Success("https://resolved.example/live.m3u8")
        )
        val queued = download(source = "magnet:?xt=urn:btih:abcdef")

        coEvery { downloadDao.findByStatus(DownloadStatus.RUNNING.name) } returns emptyList()
        coEvery { downloadDao.findFirstByStatus(DownloadStatus.QUEUED.name) } returns queued
        coEvery { downloadDao.updateState(any(), any(), any()) } returns 1
        coEvery { downloadDao.findById(1) } returns queued.copy(
            status = DownloadStatus.RUNNING.name,
            progress = 10
        )
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = repository(downloadDao, syncLogDao, engineRepository)
        val result = repository.tickQueue(maxConcurrent = 1)

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).data)
        coVerify {
            engineRepository.resolveTorrentStream("magnet:?xt=urn:btih:abcdef")
            downloadDao.updateState(1, DownloadStatus.RUNNING.name, 10)
            syncLogDao.insert(match<SyncLogEntity> { it.status == "download_engine_resolved" })
        }
    }

    @Test
    fun tickQueue_marksTorrentTaskFailedWhenEngineResolveFails() = runTest {
        val downloadDao = mockk<DownloadDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val engineRepository = fakeEngineRepository(
            resolveResult = AppResult.Error("Engine offline")
        )
        val queued = download(source = "acestream://abcdef")

        coEvery { downloadDao.findByStatus(DownloadStatus.RUNNING.name) } returns emptyList()
        coEvery { downloadDao.findFirstByStatus(DownloadStatus.QUEUED.name) } returns queued
        coEvery { downloadDao.updateState(any(), any(), any()) } returns 1
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = repository(downloadDao, syncLogDao, engineRepository)
        val result = repository.tickQueue(maxConcurrent = 1)

        assertTrue(result is AppResult.Success)
        assertEquals(0, (result as AppResult.Success).data)
        coVerify {
            engineRepository.resolveTorrentStream("acestream://abcdef")
            downloadDao.updateState(1, DownloadStatus.FAILED.name, 0)
            syncLogDao.insert(match<SyncLogEntity> { it.status == "download_engine_error" })
        }
    }

    @Test
    fun tickQueue_marksTaskFailedWhenStoragePreflightFails() = runTest {
        val downloadDao = mockk<DownloadDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val engineRepository = fakeEngineRepository(
            resolveResult = AppResult.Success("https://resolved.example/live.m3u8")
        )
        val queued = download(source = "https://example.com/movie.ts?size=10gb")

        coEvery { downloadDao.findByStatus(DownloadStatus.RUNNING.name) } returns emptyList()
        coEvery { downloadDao.findFirstByStatus(DownloadStatus.QUEUED.name) } returns queued
        coEvery { downloadDao.updateState(any(), any(), any()) } returns 1
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = repository(
            downloadDao = downloadDao,
            syncLogDao = syncLogDao,
            engineRepository = engineRepository,
            availableBytes = 1024L * 1024L * 1024L
        )
        val result = repository.tickQueue(maxConcurrent = 1)

        assertTrue(result is AppResult.Success)
        assertEquals(0, (result as AppResult.Success).data)
        coVerify(exactly = 0) {
            engineRepository.resolveTorrentStream(any())
        }
        coVerify {
            downloadDao.updateState(1, DownloadStatus.FAILED.name, 0)
            syncLogDao.insert(match<SyncLogEntity> { it.status == "download_storage_error" })
        }
    }

    private fun repository(
        downloadDao: DownloadDao,
        syncLogDao: SyncLogDao,
        engineRepository: EngineRepository,
        availableBytes: Long = 16L * 1024L * 1024L * 1024L
    ): DownloadRepositoryImpl {
        return DownloadRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            downloadDao = downloadDao,
            syncLogDao = syncLogDao,
            engineRepository = engineRepository
        ).apply {
            storagePreflight = DownloadStoragePreflight { availableBytes }
        }
    }

    private fun fakeEngineRepository(resolveResult: AppResult<String>): EngineRepository {
        return mockk {
            coEvery { connect(any()) } returns AppResult.Success(Unit)
            coEvery { refreshStatus() } returns AppResult.Success(
                EngineStatus(connected = true, peers = 0, speedKbps = 0, message = "ok")
            )
            every { observeStatus() } returns emptyFlow()
            coEvery { resolveTorrentStream(any()) } returns resolveResult
        }
    }

    private fun download(source: String): DownloadEntity {
        return DownloadEntity(
            id = 1,
            source = source,
            progress = 0,
            status = DownloadStatus.QUEUED.name,
            createdAt = 1
        )
    }
}
