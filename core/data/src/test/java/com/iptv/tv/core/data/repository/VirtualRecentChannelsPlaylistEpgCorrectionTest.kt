package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.domain.repository.EpgSettingsRepository
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.EpgUserSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualRecentChannelsPlaylistEpgCorrectionTest {
    @Test
    fun correctedWindowQueriesSourceTimelineAndReturnsDisplayTimeline() = runTest {
        val delegate = mockk<VirtualAllChannelsPlaylistRepository>()
        val epgSettingsRepository = mockk<EpgSettingsRepository>()
        coEvery { epgSettingsRepository.currentSettings() } returns EpgUserSettings(
            manualOffsetMinutes = 60
        )

        val sourceProgram = EpgProgram(
            title = "News",
            description = null,
            category = null,
            startEpochMs = 500_000L,
            endEpochMs = 1_000_000L
        )
        coEvery {
            delegate.getPlaylistEpgWindow(
                playlistId = 1L,
                startEpochMs = 400_000L,
                endEpochMs = 1_400_000L,
                query = null
            )
        } returns AppResult.Success(mapOf(10L to listOf(sourceProgram)))

        val repository = VirtualRecentChannelsPlaylistRepository(
            delegate = delegate,
            historyRepository = mockk<HistoryRepository>(relaxed = true),
            settingsRepository = mockk<SettingsRepository>(relaxed = true),
            epgSettingsRepository = epgSettingsRepository,
            favoritesRepository = mockk<FavoritesRepository>(relaxed = true),
            favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>(relaxed = true),
            favoriteSnapshotDao = mockk<FavoriteSnapshotDao>(relaxed = true),
            aggregateScope = VirtualPlaylistAggregateScope.forTest(backgroundScope)
        )

        val result = repository.getPlaylistEpgWindow(
            playlistId = 1L,
            startEpochMs = 4_000_000L,
            endEpochMs = 5_000_000L,
            query = null
        )

        assertTrue(result is AppResult.Success)
        val corrected = (result as AppResult.Success).data.getValue(10L).single()
        assertEquals(4_100_000L, corrected.startEpochMs)
        assertEquals(4_600_000L, corrected.endEpochMs)
        coVerify(exactly = 1) {
            delegate.getPlaylistEpgWindow(
                playlistId = 1L,
                startEpochMs = 400_000L,
                endEpochMs = 1_400_000L,
                query = null
            )
        }
    }
}
