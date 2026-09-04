package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelMetadataDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.ChannelMetadataEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelMetadataRepositoryBulkReadBatchTest {
    @Test
    fun bulkManualMetadataUsesBatchReadsAndPreservesWrites() = runTest {
        val channelMetadataDao = mockk<ChannelMetadataDao>(relaxed = true)
        val channelDao = mockk<ChannelDao>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val syncLogDao = mockk<SyncLogDao>(relaxed = true)
        val logoCatalogResolver = mockk<LogoCatalogResolver>(relaxed = true)
        val firstChannel = channel(id = 101L, playlistId = 7L, name = "News One")
        val secondChannel = channel(id = 102L, playlistId = 8L, name = "News Two")
        val existingFirstMetadata = ChannelMetadataEntity(
            channelId = firstChannel.id,
            normalizedName = "news one",
            country = "KZ",
            language = "kk",
            category = "News",
            resolvedLogoUrl = "https://logo.example/news-one.png",
            manualLogoUrl = "https://manual.example/news-one.png",
            manualCountry = "KZ",
            manualLanguage = "kk",
            manualCategory = "News",
            metadataSource = "manual_metadata",
            updatedAt = 1L
        )

        coEvery { channelDao.findByIds(listOf(101L, 102L)) } returns listOf(firstChannel, secondChannel)
        coEvery {
            channelMetadataDao.findByChannelIds(listOf(101L, 102L))
        } returns listOf(existingFirstMetadata)

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = channelMetadataDao,
            channelDao = channelDao,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao,
            logoCatalogResolver = logoCatalogResolver
        )

        repository.setManualMetadataBulk(
            channelIds = listOf(101L, 102L, 101L),
            country = " US ",
            language = " en ",
            category = " News "
        )

        coVerify(exactly = 1) { channelDao.findByIds(listOf(101L, 102L)) }
        coVerify(exactly = 0) { channelDao.findById(any()) }
        coVerify(exactly = 1) { channelMetadataDao.findByChannelIds(listOf(101L, 102L)) }
        coVerify(exactly = 0) { channelMetadataDao.findByChannelId(any()) }
        coVerify(exactly = 1) {
            channelMetadataDao.upsert(
                match { metadata ->
                    metadata.channelId == 101L &&
                        metadata.manualCountry == "US" &&
                        metadata.manualLanguage == "en" &&
                        metadata.manualCategory == "News" &&
                        metadata.manualLogoUrl == existingFirstMetadata.manualLogoUrl
                }
            )
        }
        coVerify(exactly = 1) {
            channelMetadataDao.upsert(
                match { metadata ->
                    metadata.channelId == 102L &&
                        metadata.manualCountry == "US" &&
                        metadata.manualLanguage == "en" &&
                        metadata.manualCategory == "News"
                }
            )
        }
    }

    private fun channel(id: Long, playlistId: Long, name: String): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = playlistId,
        tvgId = null,
        name = name,
        groupName = "News",
        logo = null,
        streamUrl = "https://stream.example/$id.m3u8",
        health = "AVAILABLE",
        orderIndex = 0,
        isHidden = false
    )
}
