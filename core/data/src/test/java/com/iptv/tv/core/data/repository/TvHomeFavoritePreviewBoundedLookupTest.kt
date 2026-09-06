package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.RecordingDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.dao.TvHomeChannelDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TvHomeFavoritePreviewBoundedLookupTest {

    @Test
    fun publishFavorites_readsOnlyPreviewSizedFavoriteCandidates() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tvHomeChannelDao = mockk<TvHomeChannelDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>(relaxed = true)
        val historyDao = mockk<HistoryDao>(relaxed = true)
        val recordingDao = mockk<RecordingDao>(relaxed = true)
        val syncLogDao = mockk<SyncLogDao>(relaxed = true)
        coEvery { favoriteChannelLookupDao.getFavoriteChannelsLimited(PREVIEW_LIMIT) } returns emptyList()

        val repository = TvHomeIntegrationRepositoryImpl(
            context = context,
            tvHomeChannelDao = tvHomeChannelDao,
            channelDao = channelDao,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            historyDao = historyDao,
            recordingDao = recordingDao,
            syncLogDao = syncLogDao
        )

        repository.publishFavorites()

        coVerify(exactly = 1) {
            favoriteChannelLookupDao.getFavoriteChannelsLimited(PREVIEW_LIMIT)
        }
        verify(exactly = 0) { channelDao.observeFavoriteChannels() }
    }

    private companion object {
        const val PREVIEW_LIMIT = 30
    }
}
