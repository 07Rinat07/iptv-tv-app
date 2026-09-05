package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
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
class PlaylistEditorRepositoryNormalizeOrderBatchTest {
    @Test
    fun bulkDeleteNormalizesOrderWithBoundedReads() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val firstBatch = (0..899).toList()
        val secondBatch = (900..1799).toList()
        val thirdBatch = listOf(1800)

        coEvery { playlistDao.findById(PLAYLIST_ID) } returns customPlaylist()
        coEvery { channelDao.deleteByIds(listOf(DELETED_CHANNEL_ID)) } returns 1
        coEvery { channelDao.maxOrderIndex(PLAYLIST_ID) } returns 1800
        coEvery {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, firstBatch)
        } returns listOf(channel(id = 10L, orderIndex = 0))
        coEvery {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, secondBatch)
        } returns listOf(channel(id = 20L, orderIndex = 900))
        coEvery {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, thirdBatch)
        } returns listOf(channel(id = 30L, orderIndex = 1800))

        val repository = PlaylistEditorRepositoryImpl(
            playlistDao = playlistDao,
            channelDao = channelDao
        )

        val result = repository.bulkDelete(
            playlistId = PLAYLIST_ID,
            channelIds = listOf(DELETED_CHANNEL_ID)
        )

        assertTrue(result is AppResult.Success)
        val success = result as AppResult.Success
        assertEquals(1, success.data.affectedCount)
        assertEquals(PLAYLIST_ID, success.data.effectivePlaylistId)

        coVerify(exactly = 0) { channelDao.getChannels(PLAYLIST_ID) }
        coVerify(exactly = 1) {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, firstBatch)
        }
        coVerify(exactly = 1) {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, secondBatch)
        }
        coVerify(exactly = 1) {
            channelDao.findByPlaylistIdAndOrderIndexes(PLAYLIST_ID, thirdBatch)
        }
        coVerify(exactly = 1) { channelDao.updateOrderIndex(20L, 1) }
        coVerify(exactly = 1) { channelDao.updateOrderIndex(30L, 2) }
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
        const val DELETED_CHANNEL_ID = 99L
    }
}
