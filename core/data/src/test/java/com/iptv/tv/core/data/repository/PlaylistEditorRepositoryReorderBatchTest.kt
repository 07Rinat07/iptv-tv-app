package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelExportSnapshotReader
import com.iptv.tv.core.database.dao.ChannelOrderIndexUpdate
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistEditorRepositoryReorderBatchTest {
    @Test
    fun moveToTopReadsLargePlaylistInBoundedOrderWindows() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        stubLargePlaylistOrder(playlistDao, channelDao)
        val repository = PlaylistEditorRepositoryImpl(
            playlistDao = playlistDao,
            channelDao = channelDao,
            channelExportSnapshotReader = mockk<ChannelExportSnapshotReader>(relaxed = true)
        )

        val result = repository.moveChannelsToTop(
            playlistId = PLAYLIST_ID,
            channelIds = listOf(SELECTED_LAST_ID, SELECTED_FIRST_ID)
        )

        assertTrue(result is AppResult.Success)
        val success = result as AppResult.Success
        assertEquals(2, success.data.affectedCount)
        assertEquals(PLAYLIST_ID, success.data.effectivePlaylistId)

        verifyBoundedOrderReads(channelDao)
        coVerify(exactly = 1) {
            channelDao.updateOrderIndexes(
                listOf(
                    ChannelOrderIndexUpdate(SELECTED_FIRST_ID, 0),
                    ChannelOrderIndexUpdate(SELECTED_LAST_ID, 1),
                    ChannelOrderIndexUpdate(FIRST_ID, 2),
                    ChannelOrderIndexUpdate(MIDDLE_ID, 3)
                )
            )
        }
        coVerify(exactly = 0) { channelDao.updateOrderIndex(any(), any()) }
    }

    @Test
    fun moveToBottomReadsLargePlaylistInBoundedOrderWindows() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        stubLargePlaylistOrder(playlistDao, channelDao)
        val repository = PlaylistEditorRepositoryImpl(
            playlistDao = playlistDao,
            channelDao = channelDao,
            channelExportSnapshotReader = mockk<ChannelExportSnapshotReader>(relaxed = true)
        )

        val result = repository.moveChannelsToBottom(
            playlistId = PLAYLIST_ID,
            channelIds = listOf(SELECTED_LAST_ID, SELECTED_FIRST_ID)
        )

        assertTrue(result is AppResult.Success)
        val success = result as AppResult.Success
        assertEquals(2, success.data.affectedCount)
        assertEquals(PLAYLIST_ID, success.data.effectivePlaylistId)

        verifyBoundedOrderReads(channelDao)
        coVerify(exactly = 1) {
            channelDao.updateOrderIndexes(
                listOf(
                    ChannelOrderIndexUpdate(MIDDLE_ID, 1),
                    ChannelOrderIndexUpdate(SELECTED_FIRST_ID, 2),
                    ChannelOrderIndexUpdate(SELECTED_LAST_ID, 3)
                )
            )
        }
        coVerify(exactly = 0) { channelDao.updateOrderIndex(any(), any()) }
    }

    private fun stubLargePlaylistOrder(
        playlistDao: PlaylistDao,
        channelDao: ChannelDao
    ) {
        coEvery { playlistDao.findById(PLAYLIST_ID) } returns customPlaylist()
        coEvery { channelDao.maxOrderIndex(PLAYLIST_ID) } returns 1800
        coEvery {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, FIRST_BATCH)
        } returns listOf(
            channel(id = FIRST_ID, orderIndex = 0),
            channel(id = SELECTED_FIRST_ID, orderIndex = 899)
        )
        coEvery {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, SECOND_BATCH)
        } returns listOf(channel(id = MIDDLE_ID, orderIndex = 900))
        coEvery {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, THIRD_BATCH)
        } returns listOf(channel(id = SELECTED_LAST_ID, orderIndex = 1800))
    }

    private fun verifyBoundedOrderReads(channelDao: ChannelDao) {
        coVerify(exactly = 0) { channelDao.getChannels(PLAYLIST_ID) }
        coVerify(exactly = 1) { channelDao.maxOrderIndex(PLAYLIST_ID) }
        coVerify(exactly = 1) {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, FIRST_BATCH)
        }
        coVerify(exactly = 1) {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, SECOND_BATCH)
        }
        coVerify(exactly = 1) {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, THIRD_BATCH)
        }
    }

    private fun customPlaylist() = PlaylistEntity(
        id = PLAYLIST_ID,
        name = "Custom",
        sourceType = "CUSTOM",
        source = "manual-builder",
        epgSourceUrl = null,
        scheduleHours = 0,
        lastSyncedAt = null,
        isCustom = true,
        createdAt = 1L,
        catalogOrigin = "USER_IMPORT"
    )

    private fun channel(id: Long, orderIndex: Int) = ChannelEntity(
        id = id,
        playlistId = PLAYLIST_ID,
        tvgId = "tvg-$id",
        name = "Channel $id",
        groupName = "Group",
        logo = null,
        streamUrl = "https://stream.example/$id.m3u8",
        health = "AVAILABLE",
        orderIndex = orderIndex,
        isHidden = false
    )

    private companion object {
        const val PLAYLIST_ID = 10L
        const val FIRST_ID = 10L
        const val SELECTED_FIRST_ID = 20L
        const val MIDDLE_ID = 30L
        const val SELECTED_LAST_ID = 40L

        val FIRST_BATCH = (0..899).toList()
        val SECOND_BATCH = (900..1799).toList()
        val THIRD_BATCH = listOf(1800)
    }
}
