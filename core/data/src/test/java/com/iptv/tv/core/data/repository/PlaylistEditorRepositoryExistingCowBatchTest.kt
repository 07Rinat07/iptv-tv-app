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
class PlaylistEditorRepositoryExistingCowBatchTest {
    @Test
    fun existingCowWithoutSelectionSkipsChannelMaterialization() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        coEvery { playlistDao.findById(SOURCE_PLAYLIST_ID) } returns sourcePlaylist()
        coEvery { playlistDao.findLatestCustomBySource("cow:$SOURCE_PLAYLIST_ID") } returns cowPlaylist()

        val repository = PlaylistEditorRepositoryImpl(
            playlistDao = playlistDao,
            channelDao = channelDao
        )

        val result = repository.ensureEditablePlaylist(SOURCE_PLAYLIST_ID)

        assertTrue(result is AppResult.Success)
        val success = result as AppResult.Success
        assertEquals(COW_PLAYLIST_ID, success.data.effectivePlaylistId)
        assertTrue(!success.data.createdWorkingCopy)
        coVerify(exactly = 0) { channelDao.getChannels(any()) }
        coVerify(exactly = 0) { channelDao.findByIds(any()) }
    }

    @Test
    fun largeExistingCowSelectionUsesBoundedSourceReads() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val firstBatch = (1L..900L).toList()
        val secondBatch = listOf(901L)
        val selectedIds = firstBatch + secondBatch
        val sourceChannels = selectedIds.map { id ->
            channel(
                id = id,
                playlistId = SOURCE_PLAYLIST_ID,
                orderIndex = id.toInt() - 1
            )
        }
        val cowChannels = selectedIds.map { id ->
            channel(
                id = COW_CHANNEL_ID_OFFSET + id,
                playlistId = COW_PLAYLIST_ID,
                orderIndex = id.toInt() - 1
            )
        }
        val expectedCowIds = selectedIds.map { COW_CHANNEL_ID_OFFSET + it }

        coEvery { playlistDao.findById(SOURCE_PLAYLIST_ID) } returns sourcePlaylist()
        coEvery { playlistDao.findLatestCustomBySource("cow:$SOURCE_PLAYLIST_ID") } returns cowPlaylist()
        coEvery { channelDao.findByIds(firstBatch) } returns sourceChannels.take(900)
        coEvery { channelDao.findByIds(secondBatch) } returns sourceChannels.drop(900)
        coEvery { channelDao.getChannels(COW_PLAYLIST_ID) } returns cowChannels
        coEvery { channelDao.setHidden(expectedCowIds, true) } returns expectedCowIds.size

        val repository = PlaylistEditorRepositoryImpl(
            playlistDao = playlistDao,
            channelDao = channelDao
        )

        val result = repository.bulkHide(
            playlistId = SOURCE_PLAYLIST_ID,
            channelIds = selectedIds,
            hidden = true
        )

        assertTrue(result is AppResult.Success)
        val success = result as AppResult.Success
        assertEquals(COW_PLAYLIST_ID, success.data.effectivePlaylistId)
        assertEquals(901, success.data.affectedCount)
        assertTrue(!success.data.createdWorkingCopy)

        coVerify(exactly = 1) { channelDao.findByIds(firstBatch) }
        coVerify(exactly = 1) { channelDao.findByIds(secondBatch) }
        coVerify(exactly = 0) { channelDao.getChannels(SOURCE_PLAYLIST_ID) }
        coVerify(exactly = 1) { channelDao.getChannels(COW_PLAYLIST_ID) }
        coVerify(exactly = 1) { channelDao.setHidden(expectedCowIds, true) }
    }

    private fun sourcePlaylist() = PlaylistEntity(
        id = SOURCE_PLAYLIST_ID,
        name = "Source",
        sourceType = "URL",
        source = "https://example.com/source.m3u",
        epgSourceUrl = null,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = 1L,
        catalogOrigin = "USER_IMPORT"
    )

    private fun cowPlaylist() = PlaylistEntity(
        id = COW_PLAYLIST_ID,
        name = "Source (COW)",
        sourceType = "CUSTOM",
        source = "cow:$SOURCE_PLAYLIST_ID",
        epgSourceUrl = null,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = true,
        createdAt = 2L,
        catalogOrigin = "USER_IMPORT"
    )

    private fun channel(
        id: Long,
        playlistId: Long,
        orderIndex: Int
    ): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = playlistId,
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
        const val SOURCE_PLAYLIST_ID = 10L
        const val COW_PLAYLIST_ID = 20L
        const val COW_CHANNEL_ID_OFFSET = 10_000L
    }
}
