package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelMetadataDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
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
class ChannelMetadataRepositoryExistingReadBatchTest {
    @Test
    fun applyMetadataRulesBatchesExistingMetadataReadsAt901Channels() = runTest {
        val channelMetadataDao = mockk<ChannelMetadataDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val syncLogDao = mockk<SyncLogDao>(relaxed = true)
        val logoCatalogResolver = mockk<LogoCatalogResolver>(relaxed = true)
        val ids = (1L..901L).toList()
        val channels = ids.mapIndexed { index, id ->
            channel(
                id = id,
                name = if (id == 1L) "Batch Target" else "Other $id",
                orderIndex = index
            )
        }

        coEvery { playlistDao.findById(1L) } returns playlist()
        coEvery { channelDao.getChannels(1L) } returns channels
        coEvery { channelMetadataDao.findByChannelIds(ids.take(900)) } returns emptyList()
        coEvery { channelMetadataDao.findByChannelIds(ids.drop(900)) } returns emptyList()

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = channelMetadataDao,
            channelDao = channelDao,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao,
            logoCatalogResolver = logoCatalogResolver
        )

        val result = repository.applyMetadataRules(
            playlistId = 1L,
            rulesText = "name=batch target; category=News",
            channelIds = emptyList()
        )

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).data)
        coVerify(exactly = 1) { channelMetadataDao.findByChannelIds(ids.take(900)) }
        coVerify(exactly = 1) { channelMetadataDao.findByChannelIds(ids.drop(900)) }
        coVerify(exactly = 0) { channelDao.findByIds(any()) }
    }

    @Test
    fun refreshMetadataBatchesExistingMetadataReadsAt901Channels() = runTest {
        val channelMetadataDao = mockk<ChannelMetadataDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val syncLogDao = mockk<SyncLogDao>(relaxed = true)
        val logoCatalogResolver = mockk<LogoCatalogResolver>(relaxed = true)
        val ids = (1L..901L).toList()
        val channels = ids.mapIndexed { index, id ->
            channel(id = id, name = "Channel $id", orderIndex = index)
        }

        coEvery { playlistDao.findById(1L) } returns playlist()
        coEvery { channelDao.getChannels(1L) } returns channels
        coEvery { channelMetadataDao.findByChannelIds(ids.take(900)) } returns emptyList()
        coEvery { channelMetadataDao.findByChannelIds(ids.drop(900)) } returns emptyList()

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = channelMetadataDao,
            channelDao = channelDao,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao,
            logoCatalogResolver = logoCatalogResolver
        )

        val result = repository.refreshMetadata(playlistId = 1L)

        assertTrue(result is AppResult.Success)
        assertEquals(0, (result as AppResult.Success).data)
        coVerify(exactly = 1) { channelMetadataDao.findByChannelIds(ids.take(900)) }
        coVerify(exactly = 1) { channelMetadataDao.findByChannelIds(ids.drop(900)) }
    }

    private fun playlist() = PlaylistEntity(
        id = 1L,
        name = "Large playlist",
        sourceType = "URL",
        source = "https://provider.example/list.m3u",
        epgSourceUrl = null,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = 1L
    )

    private fun channel(id: Long, name: String, orderIndex: Int) = ChannelEntity(
        id = id,
        playlistId = 1L,
        tvgId = null,
        name = name,
        groupName = "General",
        logo = null,
        streamUrl = "https://stream.example/$id.m3u8",
        health = "UNKNOWN",
        orderIndex = orderIndex,
        isHidden = false
    )
}
