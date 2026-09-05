package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistEditorRepositoryCreateCustomBatchTest {
    @Test
    fun largeSelectionUsesBoundedReadsAndPreservesRequestedOrder() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val firstBatch = (1L..900L).toList()
        val secondBatch = listOf(901L)

        val firstRequested = channel(id = 1L, playlistId = 10L, name = "First requested")
        val laterFirstBatch = channel(id = 900L, playlistId = 20L, name = "Later first batch")
        val secondBatchChannel = channel(id = 901L, playlistId = 30L, name = "Second batch")

        coEvery { channelDao.findByIds(firstBatch) } returns listOf(laterFirstBatch, firstRequested)
        coEvery { channelDao.findByIds(secondBatch) } returns listOf(secondBatchChannel)
        coEvery { playlistDao.insertPlaylist(any()) } returns 77L
        coEvery { channelDao.insertAll(any()) } returns Unit

        val repository = PlaylistEditorRepositoryImpl(
            playlistDao = playlistDao,
            channelDao = channelDao
        )

        val result = repository.createCustomPlaylistFromChannels(
            name = "  Curated  ",
            channelIds = firstBatch + 901L + 1L
        )

        val success = result as AppResult.Success
        assertEquals(77L, success.data.effectivePlaylistId)
        assertEquals(3, success.data.affectedCount)
        assertTrue(!success.data.createdWorkingCopy)

        coVerify(exactly = 1) { channelDao.findByIds(firstBatch) }
        coVerify(exactly = 1) { channelDao.findByIds(secondBatch) }
        coVerify(exactly = 1) {
            playlistDao.insertPlaylist(
                match { playlist ->
                    playlist.name == "Curated" &&
                        playlist.source == "manual-builder" &&
                        playlist.isCustom
                }
            )
        }
        coVerify(exactly = 1) {
            channelDao.insertAll(
                match { inserted ->
                    inserted.size == 3 &&
                        inserted.all { it.id == 0L && it.playlistId == 77L } &&
                        inserted.map { it.name } == listOf(
                            "First requested",
                            "Later first batch",
                            "Second batch"
                        ) &&
                        inserted.map { it.orderIndex } == listOf(0, 1, 2)
                }
            )
        }
        coVerify(exactly = 0) { channelDao.getChannels(any()) }
    }

    private fun channel(
        id: Long,
        playlistId: Long,
        name: String
    ): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = playlistId,
        tvgId = "tvg-$id",
        name = name,
        groupName = "Group",
        logo = null,
        streamUrl = "https://stream.example/$id.m3u8",
        health = "AVAILABLE",
        orderIndex = id.toInt(),
        isHidden = false
    )
}
