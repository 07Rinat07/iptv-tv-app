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
class PlaylistEditorRepositoryExportBatchTest {
    @Test
    fun selectedExportUsesChunkedReadsAndPreservesSelectionSemantics() = runTest {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val firstBatch = (1L..900L).toList()
        val secondBatch = listOf(901L)
        val hiddenSelected = channel(
            id = 1L,
            playlistId = 7L,
            name = "Hidden selected",
            orderIndex = 2,
            isHidden = true
        )
        val orderedFirst = channel(
            id = 901L,
            playlistId = 7L,
            name = "Ordered first",
            orderIndex = 1,
            isHidden = false
        )
        val foreignPlaylist = channel(
            id = 900L,
            playlistId = 8L,
            name = "Foreign playlist",
            orderIndex = 0,
            isHidden = false
        )

        coEvery { channelDao.findByIds(firstBatch) } returns listOf(hiddenSelected, foreignPlaylist)
        coEvery { channelDao.findByIds(secondBatch) } returns listOf(orderedFirst)

        val repository = PlaylistEditorRepositoryImpl(
            playlistDao = playlistDao,
            channelDao = channelDao
        )

        val result = repository.exportToM3u(
            playlistId = 7L,
            channelIds = firstBatch + secondBatch + 1L
        )

        val success = result as AppResult.Success
        assertEquals(7L, success.data.playlistId)
        assertEquals(2, success.data.channelCount)
        assertTrue(success.data.m3uContent.contains("Hidden selected"))
        assertTrue(success.data.m3uContent.contains("Ordered first"))
        assertTrue(!success.data.m3uContent.contains("Foreign playlist"))
        assertTrue(
            success.data.m3uContent.indexOf("Ordered first") <
                success.data.m3uContent.indexOf("Hidden selected")
        )

        coVerify(exactly = 1) { channelDao.findByIds(firstBatch) }
        coVerify(exactly = 1) { channelDao.findByIds(secondBatch) }
        coVerify(exactly = 0) { channelDao.getChannels(any()) }
    }

    private fun channel(
        id: Long,
        playlistId: Long,
        name: String,
        orderIndex: Int,
        isHidden: Boolean
    ): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = playlistId,
        tvgId = "tvg-$id",
        name = name,
        groupName = "News",
        logo = null,
        streamUrl = "https://stream.example/$id.m3u8",
        health = "AVAILABLE",
        orderIndex = orderIndex,
        isHidden = isHidden
    )
}
