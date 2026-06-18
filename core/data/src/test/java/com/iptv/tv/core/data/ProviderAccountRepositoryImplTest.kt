package com.iptv.tv.core.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.repository.ProviderAccountRepositoryImpl
import com.iptv.tv.core.data.security.ProviderSecretCipher
import com.iptv.tv.core.database.dao.PlaylistProviderDao
import com.iptv.tv.core.database.dao.ProviderSyncHistoryDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.PlaylistProviderEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.ProviderAuthType
import com.iptv.tv.core.model.ProviderDiagnosticKind
import com.iptv.tv.core.model.ProviderType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ProviderAccountRepositoryImplTest {
    private lateinit var server: MockWebServer
    private lateinit var providerDao: PlaylistProviderDao
    private lateinit var providerSyncHistoryDao: ProviderSyncHistoryDao
    private lateinit var syncLogDao: SyncLogDao
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var secretCipher: ProviderSecretCipher
    private lateinit var repository: ProviderAccountRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        providerDao = mockk()
        providerSyncHistoryDao = mockk()
        syncLogDao = mockk()
        playlistRepository = mockk()
        secretCipher = mockk()

        every { providerDao.observeProviders() } returns emptyFlow()
        every { providerSyncHistoryDao.observeRecent(any()) } returns emptyFlow()
        coEvery { providerSyncHistoryDao.insert(any()) } returns 1L
        coEvery { providerSyncHistoryDao.trimToLatest(any()) } returns 0
        coEvery { syncLogDao.insert(any()) } returns Unit
        every { secretCipher.encryptOrNull(any()) } answers { firstArg() }
        every { secretCipher.decryptOrNull(any()) } answers { firstArg() }

        repository = ProviderAccountRepositoryImpl(
            providerDao = providerDao,
            providerSyncHistoryDao = providerSyncHistoryDao,
            playlistRepository = playlistRepository,
            syncLogDao = syncLogDao,
            secretCipher = secretCipher,
            okHttpClient = OkHttpClient()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun checkProvider_forPlexCountsDvrsAndChannels() = runTest {
        coEvery { providerDao.findById(9L) } returns plexProviderEntity()
        server.enqueue(MockResponse().setBody("""<MediaContainer><Dvr uuid="dvr-1" key="/livetv/dvrs/dvr-1"/></MediaContainer>"""))
        server.enqueue(
            MockResponse().setBody(
                """
                <MediaContainer>
                    <Channel id="1" title="One"/>
                    <Channel id="2" title="Two"/>
                </MediaContainer>
                """.trimIndent()
            )
        )

        val result = repository.checkProvider(9L)

        assertSuccess(result)
        val status = (result as AppResult.Success).data
        assertTrue(status.ok)
        assertEquals(ProviderType.PLEX, status.type)
        assertEquals("Live TV OK", status.statusText)
        assertEquals(ProviderDiagnosticKind.OK, status.diagnosticKind)
        assertTrue(status.detail.orEmpty().contains("dvrs=1"))
        assertTrue(status.detail.orEmpty().contains("channels=2"))
        assertEquals("/livetv/dvrs?X-Plex-Token=plex-token", server.takeRequest().path)
        assertEquals("/livetv/dvrs/dvr-1/channels?X-Plex-Token=plex-token", server.takeRequest().path)
    }

    @Test
    fun checkProvider_forM3uReturnsAuthDiagnosticFor401() = runTest {
        coEvery { providerDao.findById(9L) } returns m3uProviderEntity()
        server.enqueue(MockResponse().setResponseCode(401))

        val result = repository.checkProvider(9L)

        assertSuccess(result)
        val status = (result as AppResult.Success).data
        assertTrue(!status.ok)
        assertEquals(ProviderDiagnosticKind.AUTH, status.diagnosticKind)
        assertEquals("HTTP 401", status.statusText)
        assertTrue(status.hint.orEmpty().contains("логин") || status.hint.orEmpty().contains("token"))
    }

    @Test
    fun checkProvider_forTvheadendReturnsParserDiagnosticForHtmlResponse() = runTest {
        coEvery { providerDao.findById(9L) } returns tvheadendProviderEntity()
        server.enqueue(MockResponse().setBody("<html>login</html>"))

        val result = repository.checkProvider(9L)

        assertSuccess(result)
        val status = (result as AppResult.Success).data
        assertTrue(!status.ok)
        assertEquals(ProviderDiagnosticKind.PARSER, status.diagnosticKind)
        assertEquals("Ошибка формата ответа", status.statusText)
        assertTrue(status.detail.orEmpty().contains("non-M3U"))
    }

    @Test
    fun syncProvider_forPlexImportsAndMarksProviderSynced() = runTest {
        coEvery { providerDao.findById(9L) } returns plexProviderEntity()
        coEvery {
            playlistRepository.importFromPlex(
                baseUrl = server.url("/").toString().trimEnd('/'),
                token = "plex-token",
                name = "Plex"
            )
        } returns AppResult.Success(
            PlaylistImportReport(
                playlistId = 77L,
                totalParsed = 2,
                totalImported = 2,
                removedDuplicates = 0,
                warnings = emptyList(),
                autoChecked = 0,
                available = 0,
                unstable = 0,
                unavailable = 0
            )
        )
        coEvery { providerDao.markSynced(9L, 77L, any()) } returns 1

        val result = repository.syncProvider(9L)

        assertSuccess(result)
        assertEquals(77L, (result as AppResult.Success).data)
        coVerify(exactly = 1) {
            playlistRepository.importFromPlex(
                baseUrl = server.url("/").toString().trimEnd('/'),
                token = "plex-token",
                name = "Plex"
            )
        }
        coVerify(exactly = 1) { providerDao.markSynced(9L, 77L, any()) }
        coVerify(exactly = 1) {
            syncLogDao.insert(match { it.status == "provider_sync_item_ok" && it.message.contains("providerId=9") })
        }
    }

    @Test
    fun syncProvider_propagatesImportErrorAndDoesNotMarkSynced() = runTest {
        coEvery { providerDao.findById(9L) } returns plexProviderEntity()
        coEvery {
            playlistRepository.importFromPlex(any(), any(), any())
        } returns AppResult.Error("Unable to import Plex")

        val result = repository.syncProvider(9L)

        assertTrue(result is AppResult.Error)
        coVerify(exactly = 0) { providerDao.markSynced(any(), any(), any()) }
        coVerify(exactly = 1) {
            syncLogDao.insert(match { it.status == "provider_sync_item_error" && it.message.contains("provider_error") })
        }
    }

    @Test
    fun syncAllProviders_returnsSuccessfulCountWhenSomeProvidersFail() = runTest {
        val okProvider = plexProviderEntity()
        val failingProvider = plexProviderEntity().copy(id = 10L, name = "Broken Plex")
        coEvery { providerDao.getProviders() } returns listOf(okProvider, failingProvider)
        coEvery { providerDao.findById(9L) } returns okProvider
        coEvery { providerDao.findById(10L) } returns failingProvider
        coEvery {
            playlistRepository.importFromPlex(
                baseUrl = okProvider.baseUrl,
                token = "plex-token",
                name = "Plex"
            )
        } returns AppResult.Success(
            PlaylistImportReport(
                playlistId = 77L,
                totalParsed = 2,
                totalImported = 2,
                removedDuplicates = 0,
                warnings = emptyList(),
                autoChecked = 0,
                available = 0,
                unstable = 0,
                unavailable = 0
            )
        )
        coEvery {
            playlistRepository.importFromPlex(
                baseUrl = failingProvider.baseUrl,
                token = "plex-token",
                name = "Broken Plex"
            )
        } returns AppResult.Error("HTTP 401")
        coEvery { providerDao.markSynced(9L, 77L, any()) } returns 1

        val result = repository.syncAllProviders()

        assertSuccess(result)
        assertEquals(1, (result as AppResult.Success).data)
        coVerify(exactly = 1) { providerDao.markSynced(9L, 77L, any()) }
        coVerify(exactly = 0) { providerDao.markSynced(10L, any(), any()) }
        coVerify(exactly = 1) {
            syncLogDao.insert(match { it.status == "provider_sync_item_error" && it.message.contains("reason=auth") })
        }
    }

    private fun plexProviderEntity(): PlaylistProviderEntity {
        return PlaylistProviderEntity(
            id = 9L,
            type = ProviderType.PLEX.name,
            name = "Plex",
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = null,
            password = null,
            token = "plex-token",
            macAddress = null,
            authType = ProviderAuthType.TOKEN.name,
            linkedPlaylistId = null,
            lastSyncedAt = null,
            createdAt = 1L
        )
    }

    private fun m3uProviderEntity(): PlaylistProviderEntity {
        return PlaylistProviderEntity(
            id = 9L,
            type = ProviderType.M3U.name,
            name = "M3U",
            baseUrl = server.url("/playlist.m3u").toString(),
            username = null,
            password = null,
            token = null,
            macAddress = null,
            authType = ProviderAuthType.NONE.name,
            linkedPlaylistId = null,
            lastSyncedAt = null,
            createdAt = 1L
        )
    }

    private fun tvheadendProviderEntity(): PlaylistProviderEntity {
        return PlaylistProviderEntity(
            id = 9L,
            type = ProviderType.TVHEADEND.name,
            name = "Tvheadend",
            baseUrl = server.url("/").toString().trimEnd('/'),
            username = "demo",
            password = "demo",
            token = null,
            macAddress = null,
            authType = ProviderAuthType.USER_PASSWORD.name,
            linkedPlaylistId = null,
            lastSyncedAt = null,
            createdAt = 1L
        )
    }

    private fun assertSuccess(result: AppResult<*>) {
        if (result !is AppResult.Success) {
            fail((result as? AppResult.Error)?.message ?: "Expected AppResult.Success, got $result")
        }
    }
}
