package com.iptv.tv.core.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.repository.ChannelMetadataRepositoryImpl
import com.iptv.tv.core.data.repository.parseMetadataRules
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelExportSnapshotReader
import com.iptv.tv.core.database.dao.ChannelMetadataDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.ChannelMetadataEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelMetadataRepositoryImplTest {

    @Test
    fun setManualMetadata_storesManualOverridesAsEffectiveMetadata() = runTest {
        val metadataDao = mockk<ChannelMetadataDao>()
        val channelDao = mockk<ChannelDao>()
        val channelSnapshotReader = mockk<ChannelExportSnapshotReader>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>()
        val syncLogDao = mockk<SyncLogDao>()

        coEvery { channelDao.findById(10) } returns channel(id = 10, name = "Kazakh News", tvgId = null, logo = null)
        coEvery { metadataDao.findByChannelId(10) } returns null
        coEvery { metadataDao.upsert(any()) } returns Unit
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = metadataDao,
            channelDao = channelDao,
            channelSnapshotReader = channelSnapshotReader,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao
        )
        val result = repository.setManualMetadata(
            channelId = 10,
            country = " KZ ",
            language = " kk ",
            category = " Local "
        )

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).data)
        coVerify(exactly = 0) { channelSnapshotReader.snapshotPlaylistChannelIdsInOrder(any()) }
        coVerify {
            metadataDao.upsert(
                match<ChannelMetadataEntity> {
                    it.channelId == 10L &&
                        it.country == "KZ" &&
                        it.language == "kk" &&
                        it.category == "Local" &&
                        it.manualCountry == "KZ" &&
                        it.manualLanguage == "kk" &&
                        it.manualCategory == "Local" &&
                        it.metadataSource == "manual_metadata"
                }
            )
        }
        coVerify {
            syncLogDao.insert(match<SyncLogEntity> { it.status == "metadata_manual_fields" })
        }
    }

    @Test
    fun setManualMetadataBulk_appliesOverridesToExistingChannels() = runTest {
        val metadataDao = mockk<ChannelMetadataDao>()
        val channelDao = mockk<ChannelDao>()
        val channelSnapshotReader = mockk<ChannelExportSnapshotReader>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val firstChannel = channel(id = 10, name = "Kazakh News", tvgId = null, logo = null)
        val secondChannel = channel(id = 11, name = "Kazakh Sport", tvgId = null, logo = null)

        coEvery { channelDao.findByIds(listOf(10, 11, 99)) } returns listOf(firstChannel, secondChannel)
        coEvery { metadataDao.findByChannelIds(listOf(10, 11)) } returns emptyList()
        coEvery { metadataDao.upsert(any()) } returns Unit
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = metadataDao,
            channelDao = channelDao,
            channelSnapshotReader = channelSnapshotReader,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao
        )
        val result = repository.setManualMetadataBulk(
            channelIds = listOf(10, 11, 10, 99),
            country = " KZ ",
            language = " kk ",
            category = " Local "
        )

        assertTrue(result is AppResult.Success)
        assertEquals(2, (result as AppResult.Success).data)
        coVerify(exactly = 1) { channelDao.findByIds(listOf(10, 11, 99)) }
        coVerify(exactly = 0) { channelDao.findById(any()) }
        coVerify(exactly = 1) { metadataDao.findByChannelIds(listOf(10, 11)) }
        coVerify(exactly = 0) { metadataDao.findByChannelId(any()) }
        coVerify(exactly = 0) { channelSnapshotReader.snapshotPlaylistChannelIdsInOrder(any()) }
        coVerify(exactly = 2) {
            metadataDao.upsert(
                match<ChannelMetadataEntity> {
                    it.country == "KZ" &&
                        it.language == "kk" &&
                        it.category == "Local" &&
                        it.manualCountry == "KZ" &&
                        it.manualLanguage == "kk" &&
                        it.manualCategory == "Local" &&
                        it.metadataSource == "manual_metadata"
                }
            )
        }
        coVerify {
            syncLogDao.insert(
                match<SyncLogEntity> {
                    it.status == "metadata_manual_fields_bulk" &&
                        it.message.contains("requested=3") &&
                        it.message.contains("updated=2")
                }
            )
        }
    }

    @Test
    fun refreshMetadataWithLogoPack_appliesExternalLogoPackToPlaylistChannels() = runTest {
        val metadataDao = mockk<ChannelMetadataDao>()
        val channelDao = mockk<ChannelDao>()
        val channelSnapshotReader = mockk<ChannelExportSnapshotReader>()
        val playlistDao = mockk<PlaylistDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val targetChannel = channel(id = 10, name = "External News HD", tvgId = "external.news", logo = null)

        coEvery { playlistDao.findById(1) } returns playlist()
        coEvery { channelSnapshotReader.snapshotPlaylistChannelIdsInOrder(1) } returns listOf(10)
        coEvery { channelDao.findByIds(listOf(10)) } returns listOf(targetChannel)
        coEvery { metadataDao.findByChannelIds(listOf(10)) } returns emptyList()
        coEvery { metadataDao.upsert(any()) } returns Unit
        coEvery {
            channelDao.updateChannelFields(
                channelId = 10,
                name = "External News HD",
                groupName = "News",
                logo = "https://cdn.example.com/news.png",
                streamUrl = "https://stream.example/news.m3u8"
            )
        } returns 1
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = metadataDao,
            channelDao = channelDao,
            channelSnapshotReader = channelSnapshotReader,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao
        )
        val result = repository.refreshMetadataWithLogoPack(
            playlistId = 1,
            logoPackJson = """
                {
                  "logos": [
                    {
                      "tvgId": "external.news",
                      "logoUrl": "https://cdn.example.com/news.png",
                      "name": "External News",
                      "countryCode": "US",
                      "lang": "en",
                      "group": "News"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).data)
        coVerify(exactly = 1) { channelSnapshotReader.snapshotPlaylistChannelIdsInOrder(1) }
        coVerify(exactly = 1) { channelDao.findByIds(listOf(10)) }
        coVerify(exactly = 0) { channelDao.getChannels(any()) }
        coVerify {
            metadataDao.upsert(
                match<ChannelMetadataEntity> {
                    it.channelId == 10L &&
                        it.resolvedLogoUrl == "https://cdn.example.com/news.png" &&
                        it.metadataSource == "logo-pack:tvg-id" &&
                        it.country == "US" &&
                        it.language == "en" &&
                        it.category == "News"
                }
            )
        }
        coVerify {
            syncLogDao.insert(match<SyncLogEntity> { it.status == "metadata_external_logo_pack" })
        }
    }

    @Test
    fun refreshMetadataWithLogoPack_preservesManualMetadataOverrides() = runTest {
        val metadataDao = mockk<ChannelMetadataDao>()
        val channelDao = mockk<ChannelDao>()
        val channelSnapshotReader = mockk<ChannelExportSnapshotReader>()
        val playlistDao = mockk<PlaylistDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val targetChannel = channel(id = 10, name = "External News HD", tvgId = "external.news", logo = null)

        coEvery { playlistDao.findById(1) } returns playlist()
        coEvery { channelSnapshotReader.snapshotPlaylistChannelIdsInOrder(1) } returns listOf(10)
        coEvery { channelDao.findByIds(listOf(10)) } returns listOf(targetChannel)
        coEvery { metadataDao.findByChannelIds(listOf(10)) } returns listOf(
            metadataEntity(
                channelId = 10,
                manualCountry = "KZ",
                manualLanguage = "kk",
                manualCategory = "Local"
            )
        )
        coEvery { metadataDao.upsert(any()) } returns Unit
        coEvery {
            channelDao.updateChannelFields(
                channelId = 10,
                name = "External News HD",
                groupName = "News",
                logo = "https://cdn.example.com/news.png",
                streamUrl = "https://stream.example/news.m3u8"
            )
        } returns 1
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = metadataDao,
            channelDao = channelDao,
            channelSnapshotReader = channelSnapshotReader,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao
        )
        val result = repository.refreshMetadataWithLogoPack(
            playlistId = 1,
            logoPackJson = """
                {
                  "logos": [
                    {
                      "tvgId": "external.news",
                      "logoUrl": "https://cdn.example.com/news.png",
                      "name": "External News",
                      "countryCode": "US",
                      "lang": "en",
                      "group": "News"
                    }
                  ]
                }
            """.trimIndent()
        )

        assertTrue(result is AppResult.Success)
        assertEquals(1, (result as AppResult.Success).data)
        coVerify(exactly = 1) { channelSnapshotReader.snapshotPlaylistChannelIdsInOrder(1) }
        coVerify(exactly = 1) { channelDao.findByIds(listOf(10)) }
        coVerify(exactly = 0) { channelDao.getChannels(any()) }
        coVerify {
            metadataDao.upsert(
                match<ChannelMetadataEntity> {
                    it.channelId == 10L &&
                        it.resolvedLogoUrl == "https://cdn.example.com/news.png" &&
                        it.country == "KZ" &&
                        it.language == "kk" &&
                        it.category == "Local" &&
                        it.manualCountry == "KZ" &&
                        it.manualLanguage == "kk" &&
                        it.manualCategory == "Local" &&
                        it.metadataSource == "manual_metadata"
                }
            )
        }
    }

    @Test
    fun parseMetadataRules_ignoresInvalidLinesAndKeepsRuleFields() {
        val rules = parseMetadataRules(
            """
                # comment
                match=news; country=US; language=en; category=News
                group=sport; category=Sports
                country=KZ
            """.trimIndent()
        )

        assertEquals(2, rules.size)
        assertEquals("news", rules[0].match)
        assertEquals("US", rules[0].country)
        assertEquals("en", rules[0].language)
        assertEquals("News", rules[0].category)
        assertEquals("sport", rules[1].group)
        assertEquals("Sports", rules[1].category)
    }

    @Test
    fun applyMetadataRules_appliesRulesToRequestedChannels() = runTest {
        val metadataDao = mockk<ChannelMetadataDao>()
        val channelDao = mockk<ChannelDao>()
        val channelSnapshotReader = mockk<ChannelExportSnapshotReader>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>()
        val syncLogDao = mockk<SyncLogDao>()
        val newsChannel = channel(id = 10, name = "Kazakh News", tvgId = "kz.news", logo = null)
            .copy(orderIndex = 1)
        val movieChannel = channel(id = 11, name = "Movie One", tvgId = "movie.one", logo = null)
            .copy(orderIndex = 2)
        val foreignChannel = channel(id = 99, name = "Foreign News", tvgId = "foreign.news", logo = null)
            .copy(playlistId = 2, orderIndex = 0)

        coEvery { playlistDao.findById(1) } returns playlist()
        coEvery { channelDao.findByIds(listOf(10, 11, 99)) } returns listOf(
            foreignChannel,
            movieChannel,
            newsChannel
        )
        coEvery { metadataDao.findByChannelIds(listOf(10, 11)) } returns emptyList()
        coEvery { metadataDao.upsert(any()) } returns Unit
        coEvery { syncLogDao.insert(any()) } returns Unit

        val repository = ChannelMetadataRepositoryImpl(
            channelMetadataDao = metadataDao,
            channelDao = channelDao,
            channelSnapshotReader = channelSnapshotReader,
            playlistDao = playlistDao,
            syncLogDao = syncLogDao
        )
        val result = repository.applyMetadataRules(
            playlistId = 1,
            rulesText = """
                match=kazakh; country=KZ; language=kk; category=Local
                name=movie; category=Movies
            """.trimIndent(),
            channelIds = listOf(10, 11, 99, 10)
        )

        assertTrue(result is AppResult.Success)
        assertEquals(2, (result as AppResult.Success).data)
        coVerify(exactly = 1) { channelDao.findByIds(listOf(10, 11, 99)) }
        coVerify(exactly = 0) { channelDao.getChannels(any()) }
        coVerify(exactly = 1) { metadataDao.findByChannelIds(listOf(10, 11)) }
        coVerify(exactly = 0) { channelSnapshotReader.snapshotPlaylistChannelIdsInOrder(any()) }
        coVerify {
            metadataDao.upsert(
                match<ChannelMetadataEntity> {
                    it.channelId == 10L &&
                        it.manualCountry == "KZ" &&
                        it.manualLanguage == "kk" &&
                        it.manualCategory == "Local" &&
                        it.metadataSource == "manual_metadata"
                }
            )
            metadataDao.upsert(
                match<ChannelMetadataEntity> {
                    it.channelId == 11L &&
                        it.manualCountry == null &&
                        it.manualLanguage == null &&
                        it.manualCategory == "Movies" &&
                        it.category == "Movies" &&
                        it.metadataSource == "manual_metadata"
                }
            )
            syncLogDao.insert(
                match<SyncLogEntity> {
                    it.status == "metadata_rules_applied" &&
                        it.message.contains("target=2") &&
                        it.message.contains("updated=2")
                }
            )
        }
    }

    private fun playlist(): PlaylistEntity {
        return PlaylistEntity(
            id = 1,
            name = "Playlist",
            sourceType = "URL",
            source = "https://provider.example/list.m3u",
            epgSourceUrl = null,
            scheduleHours = 12,
            lastSyncedAt = null,
            isCustom = false,
            createdAt = 1
        )
    }

    private fun channel(
        id: Long,
        name: String,
        tvgId: String?,
        logo: String?
    ): ChannelEntity {
        return ChannelEntity(
            id = id,
            playlistId = 1,
            tvgId = tvgId,
            name = name,
            groupName = "News",
            logo = logo,
            streamUrl = "https://stream.example/news.m3u8",
            health = "UNKNOWN",
            orderIndex = 0,
            isHidden = false
        )
    }

    private fun metadataEntity(
        channelId: Long,
        manualCountry: String?,
        manualLanguage: String?,
        manualCategory: String?
    ): ChannelMetadataEntity {
        return ChannelMetadataEntity(
            channelId = channelId,
            normalizedName = "external news hd",
            country = null,
            language = null,
            category = null,
            resolvedLogoUrl = null,
            manualLogoUrl = null,
            manualCountry = manualCountry,
            manualLanguage = manualLanguage,
            manualCategory = manualCategory,
            metadataSource = null,
            updatedAt = 1
        )
    }
}
