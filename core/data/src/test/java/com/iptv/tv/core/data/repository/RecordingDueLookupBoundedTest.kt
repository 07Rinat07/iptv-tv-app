package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.RecordingDao
import com.iptv.tv.core.database.dao.RecordingScheduleDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.RecordingStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Test

class RecordingDueLookupBoundedTest {

    @Test
    fun processDueRecordings_readsOnlyRequestedDueCandidates() = runTest {
        val recordingDao = mockk<RecordingDao>(relaxed = true)
        coEvery {
            recordingDao.findDueByStatusLimited(
                status = RecordingStatus.SCHEDULED.name,
                beforeEpochMs = any(),
                limit = 1
            )
        } returns emptyList()

        val repository = RecordingRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            recordingDao = recordingDao,
            recordingScheduleDao = mockk<RecordingScheduleDao>(relaxed = true),
            channelDao = mockk<ChannelDao>(relaxed = true),
            syncLogDao = mockk<SyncLogDao>(relaxed = true),
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true)
        )

        repository.processDueRecordings(maxConcurrent = 1)

        coVerify(exactly = 1) {
            recordingDao.findDueByStatusLimited(
                status = RecordingStatus.SCHEDULED.name,
                beforeEpochMs = any(),
                limit = 1
            )
        }
        coVerify(exactly = 0) {
            recordingDao.findByStatus(any())
        }
    }
}
