package com.iptv.tv.core.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.repository.PlaylistEditorRepositoryImpl
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.ChannelHealth
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistEditorRepositoryImplTest {

    @Test
    fun ensureEditablePlaylist_createsCowCopyForNonCustomSource() = runTest {
        val playlistDao = mockk<PlaylistDao>()
        val channelDao = mockk<ChannelDao>()

        val sourcePlaylist = PlaylistEntity(
            id = 1,
            name = "Source",
            sourceType = "URL",
            source = "https://example.com/list.m3u",
            scheduleHours = 12,
            lastSyncedAt = null,
            isCustom = false,
            createdAt = 1L
        )
        val sourceChannels = listOf(
            channel(id = 10, playlistId = 1, orderIndex = 0, name = "News", url = "https://a"),
            channel(id = 11, playlistId = 1, orderIndex = 1, name = "Sport", url = "https://b")
        )
        val copiedChannels = listOf(
            channel(id = 20, playlistId = 2, orderIndex = 0, name = "News", url = "https://a"),
            channel(id = 21, playlistId = 2, orderIndex = 1, name = "Sport", url = "https://b")
        )

        coEvery { playlistDao.findById(1) } returns sourcePlaylist
        coEvery { playlistDao.findLatestCustomBySource("cow:1") } returns null
        coEvery { channelDao.getChannels(1) } returns sourceChannels
        coEvery { playlistDao.insertPlaylist(any()) } returns 2
        coEvery { channelDao.insertAll(any()) } returns Unit
        coEvery { channelDao.getChannels(2) } returns copiedChannels

        val repository = PlaylistEditorRepositoryImpl(playlistDao, channelDao)
        val result = repository.ensureEditablePlaylist(1)

        assertTrue(result is AppResult.Success)
        val data = (result as AppResult.Success).data
        assertEquals(2L, data.effectivePlaylistId)
        assertTrue(data.createdWorkingCopy)

        coVerify(exactly = 1) {
            playlistDao.insertPlaylist(match { it.isCustom && it.source == "cow:1" })
        }
        coVerify(exactly = 1) {
            channelDao.insertAll(match { inserted -> inserted.size == 2 && inserted.all { it.playlistId == 2L } })
        }
    }

    @Test
    fun ensureEditablePlaylist_reusesExistingCowCopyWhenPresent() = runTest {
        val playlistDao = mockk<PlaylistDao>()
        val channelDao = mockk<ChannelDao>()

        val sourcePlaylist = PlaylistEntity(
            id = 1,
            name = "Source",
            sourceType = "URL",
            source = "https://example.com/list.m3u",
            scheduleHours = 12,
            lastSyncedAt = null,
            isCustom = false,
            createdAt = 1L
        )
        val existingCow = PlaylistEntity(
            id = 5,
            name = "Source (COW)",
            sourceType = "CUSTOM",
            source = "cow:1",
            scheduleHours = 12,
            lastSyncedAt = null,
            isCustom = true,
            createdAt = 2L
        )

        coEvery { playlistDao.findById(1) } returns sourcePlaylist
        coEvery { playlistDao.findLatestCustomBySource("cow:1") } returns existingCow
        coEvery { channelDao.getChannels(1) } returns emptyList()

        val repository = PlaylistEditorRepositoryImpl(playlistDao, channelDao)
        val result = repository.ensureEditablePlaylist(1)

        assertTrue(result is AppResult.Success)
        val data = (result as AppResult.Success).data
        assertEquals(5L, data.effectivePlaylistId)
        assertTrue(!data.createdWorkingCopy)

        coVerify(exactly = 0) { playlistDao.insertPlaylist(any()) }
        coVerify(exactly = 0) { channelDao.insertAll(any()) }
    }

    @Test
    fun bulkDelete_worksOnCustomPlaylistWithoutCreatingCopy() = runTest {
        val playlistDao = mockk<PlaylistDao>()
        val channelDao = mockk<ChannelDao>()

        coEvery { playlistDao.findById(5) } returns PlaylistEntity(
            id = 5,
            name = "Custom",
            sourceType = "CUSTOM",
            source = "manual-builder",
            scheduleHours = 0,
            lastSyncedAt = null,
            isCustom = true,
            createdAt = 1L
        )
        coEvery { channelDao.deleteByIds(listOf(100L)) } returns 1
        coEvery { channelDao.getChannels(5) } returns listOf(
            channel(id = 101, playlistId = 5, orderIndex = 1, name = "AfterDelete", url = "https://x")
        )
        coEvery { channelDao.updateOrderIndex(101, 0) } returns Unit

        val repository = PlaylistEditorRepositoryImpl(playlistDao, channelDao)
        val result = repository.bulkDelete(5, listOf(100))

        assertTrue(result is AppResult.Success)
        val data = (result as AppResult.Success).data
        assertEquals(5L, data.effectivePlaylistId)
        assertEquals(1, data.affectedCount)
        assertTrue(!data.createdWorkingCopy)

        coVerify(exactly = 0) { playlistDao.insertPlaylist(any()) }
        coVerify(exactly = 1) { channelDao.updateOrderIndex(101, 0) }
    }

    @Test
    fun exportToM3u_excludesHiddenChannelsWhenNoExplicitSelection() = runTest {
        val playlistDao = mockk<PlaylistDao>()
        val channelDao = mockk<ChannelDao>()
        val repository = PlaylistEditorRepositoryImpl(playlistDao, channelDao)

        coEvery { channelDao.getChannels(9) } returns listOf(
            channel(id = 1, playlistId = 9, orderIndex = 0, name = "Visible", url = "https://visible"),
            channel(id = 2, playlistId = 9, orderIndex = 1, name = "Hidden", url = "https://hidden", hidden = true)
        )

        val result = repository.exportToM3u(9, emptyList())
        assertTrue(result is AppResult.Success)
        val exported = (result as AppResult.Success).data
        assertEquals(1, exported.channelCount)
        assertTrue(exported.m3uContent.contains("Visible"))
        assertTrue(!exported.m3uContent.contains("Hidden"))
    }

    private fun channel(
        id: Long,
        playlistId: Long,
        orderIndex: Int,
        name: String,
        url: String,
        hidden: Boolean = false
    ): ChannelEntity {
        return ChannelEntity(
            id = id,
            playlistId = playlistId,
            tvgId = null,
            name = name,
            groupName = null,
            logo = null,
            streamUrl = url,
            health = ChannelHealth.UNKNOWN.name,
            orderIndex = orderIndex,
            isHidden = hidden
        )
    }
}
