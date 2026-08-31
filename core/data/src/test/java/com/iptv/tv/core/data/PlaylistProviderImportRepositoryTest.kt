package com.iptv.tv.core.data

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.repository.PlaylistRepositoryImpl
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.EpgSnapshotDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaylistProviderImportRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var playlistDao: PlaylistDao
    private lateinit var channelDao: ChannelDao
    private lateinit var syncLogDao: SyncLogDao
    private lateinit var repository: PlaylistRepositoryImpl
    private lateinit var insertedPlaylist: CapturingSlot<PlaylistEntity>
    private lateinit var insertedChannels: CapturingSlot<List<ChannelEntity>>

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        playlistDao = mockk()
        channelDao = mockk()
        syncLogDao = mockk()
        insertedPlaylist = slot()
        insertedChannels = slot()

        every { playlistDao.observePlaylistsWithCount() } returns emptyFlow()
        coEvery { playlistDao.insertPlaylist(capture(insertedPlaylist)) } returns 42L
        coEvery { channelDao.insertAll(capture(insertedChannels)) } returns Unit
        coEvery { channelDao.getChannels(42L) } returns emptyList()
        coEvery { syncLogDao.insert(any()) } returns Unit

        repository = PlaylistRepositoryImpl(
            context = mockk<Context>(relaxed = true),
            playlistDao = playlistDao,
            channelDao = channelDao,
            favoriteDao = mockk<FavoriteDao>(relaxed = true),
            historyDao = mockk<HistoryDao>(relaxed = true),
            syncLogDao = syncLogDao,
            epgSnapshotDao = mockk<EpgSnapshotDao>(relaxed = true),
            parser = M3uParser(),
            okHttpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun importFromPlex_readsDvrsAndChannelsWithoutStoringTokenInPlaylistSource() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """<MediaContainer><Dvr uuid="dvr-1" key="/livetv/dvrs/dvr-1" title="Home DVR"/></MediaContainer>"""
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """
                <MediaContainer>
                    <Channel id="100" title="Plex News" channelNumber="1.1" channelIdentifier="plex-news" thumb="/library/metadata/100/thumb"/>
                    <Channel id="200" title="Plex Sport" channelNumber="2.1" key="/livetv/dvrs/dvr-1/channels/200"/>
                </MediaContainer>
                """.trimIndent()
            )
        )

        val result = repository.importFromPlex(server.url("/").toString(), "secret-token", "Plex")

        assertImportSuccess(result)
        assertEquals(PlaylistSourceType.PLEX.name, insertedPlaylist.captured.sourceType)
        assertEquals(CatalogOriginKind.PROVIDER.name, insertedPlaylist.captured.catalogOrigin)
        assertEquals("${server.url("/").toString().trimEnd('/')}/livetv/dvrs", insertedPlaylist.captured.source)
        assertFalse(insertedPlaylist.captured.source.contains("secret-token"))
        assertEquals(2, insertedChannels.captured.size)
        assertEquals("Plex News", insertedChannels.captured[0].name)
        assertEquals("plex-news", insertedChannels.captured[0].tvgId)
        assertTrue(insertedChannels.captured[0].logo!!.contains("X-Plex-Token=secret-token"))
        assertTrue(insertedChannels.captured[0].streamUrl.contains("/livetv/dvrs/dvr-1/channels/100/tune"))
        assertTrue(insertedChannels.captured[0].streamUrl.contains("X-Plex-Token=secret-token"))
        assertEquals("Plex Sport", insertedChannels.captured[1].name)
        assertTrue(insertedChannels.captured[1].streamUrl.contains("/livetv/dvrs/dvr-1/channels/200/tune"))
        assertEquals("/livetv/dvrs?X-Plex-Token=secret-token", server.takeRequest().path)
        assertEquals("/livetv/dvrs/dvr-1/channels?X-Plex-Token=secret-token", server.takeRequest().path)
    }

    @Test
    fun importFromJellyfin_buildsPlaylistAndKeepsApiKeyOutOfPlaylistSource() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "Items": [
                    {
                      "Id": "channel-1",
                      "Name": "Jelly News",
                      "ChannelNumber": "7",
                      "PrimaryImageTag": "tag-1"
                    }
                  ]
                }
                """.trimIndent()
            )
        )

        val result = repository.importFromJellyfin(server.url("/").toString(), "api-secret", "Jellyfin")

        assertImportSuccess(result)
        assertEquals(PlaylistSourceType.JELLYFIN.name, insertedPlaylist.captured.sourceType)
        assertEquals(CatalogOriginKind.PROVIDER.name, insertedPlaylist.captured.catalogOrigin)
        assertEquals("${server.url("/").toString().trimEnd('/')}/LiveTv/Channels", insertedPlaylist.captured.source)
        assertFalse(insertedPlaylist.captured.source.contains("api-secret"))
        assertEquals(1, insertedChannels.captured.size)
        assertEquals("Jelly News", insertedChannels.captured.single().name)
        assertTrue(insertedChannels.captured.single().logo!!.contains("api_key=api-secret"))
        assertTrue(insertedChannels.captured.single().streamUrl.contains("/LiveTv/Channels/channel-1/Stream"))
        assertTrue(insertedChannels.captured.single().streamUrl.contains("api_key=api-secret"))
        assertEquals("/LiveTv/Channels?api_key=api-secret", server.takeRequest().path)
    }

    @Test
    fun importFromHdHomeRun_usesLineupJsonAndBuildsM3u() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                [
                  {"GuideNumber":"5.1","GuideName":"Local News","URL":"http://stream.example/news"},
                  {"GuideNumber":"7.2","GuideName":"Sports","URL":"http://stream.example/sports"}
                ]
                """.trimIndent()
            )
        )

        val result = repository.importFromHdHomeRun(server.url("/").toString(), "HDHomeRun")

        assertImportSuccess(result)
        assertEquals(PlaylistSourceType.HDHOMERUN.name, insertedPlaylist.captured.sourceType)
        assertEquals(CatalogOriginKind.PROVIDER.name, insertedPlaylist.captured.catalogOrigin)
        assertEquals("${server.url("/").toString().trimEnd('/')}/lineup.json", insertedPlaylist.captured.source)
        assertEquals(2, insertedChannels.captured.size)
        assertEquals("Local News", insertedChannels.captured[0].name)
        assertEquals("5.1", insertedChannels.captured[0].tvgId)
        assertEquals("http://stream.example/news", insertedChannels.captured[0].streamUrl)
        assertEquals("/lineup.json", server.takeRequest().path)
    }

    @Test
    fun importFromTvheadend_usesBasicAuthAndChannelPlaylistEndpoint() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="news" tvg-name="TVH News",TVH News
                http://stream.example/tvh-news
                """.trimIndent()
            )
        )

        val result = repository.importFromTvheadend(
            baseUrl = server.url("/").toString(),
            username = "alice",
            password = "secret",
            name = "TVHeadend"
        )

        assertImportSuccess(result)
        assertEquals(PlaylistSourceType.TVHEADEND.name, insertedPlaylist.captured.sourceType)
        assertEquals(CatalogOriginKind.PROVIDER.name, insertedPlaylist.captured.catalogOrigin)
        assertEquals("${server.url("/").toString().trimEnd('/')}/playlist/channels.m3u", insertedPlaylist.captured.source)
        assertEquals(1, insertedChannels.captured.size)
        val auth = server.takeRequest().getHeader("Authorization").orEmpty()
        assertTrue(auth.startsWith("Basic "))
    }

    @Test
    fun importFromM3u_usesExistingUrlImportPath() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                #EXTM3U
                #EXTINF:-1 tvg-id="m3u-news" tvg-name="M3U News",M3U News
                http://stream.example/m3u-news
                """.trimIndent()
            )
        )

        val result = repository.importFromUrl(server.url("/provider.m3u").toString(), "M3U")

        assertImportSuccess(result)
        assertEquals(PlaylistSourceType.URL.name, insertedPlaylist.captured.sourceType)
        assertEquals(CatalogOriginKind.USER_IMPORT.name, insertedPlaylist.captured.catalogOrigin)
        assertEquals(server.url("/provider.m3u").toString(), insertedPlaylist.captured.source)
        assertEquals(1, insertedChannels.captured.size)
        assertEquals("M3U News", insertedChannels.captured.single().name)
        assertEquals("/provider.m3u", server.takeRequest().path)
    }

    private fun assertImportSuccess(result: AppResult<com.iptv.tv.core.model.PlaylistImportReport>) {
        if (result !is AppResult.Success) {
            fail("Expected success but was $result")
        }
    }
}
