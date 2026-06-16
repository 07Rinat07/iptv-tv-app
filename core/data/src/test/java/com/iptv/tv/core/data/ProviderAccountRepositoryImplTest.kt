package com.iptv.tv.core.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.repository.ProviderAccountRepositoryImpl
import com.iptv.tv.core.data.security.ProviderSecretCipher
import com.iptv.tv.core.database.dao.PlaylistProviderDao
import com.iptv.tv.core.database.entity.PlaylistProviderEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.ProviderAuthType
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
    private lateinit var playlistRepository: PlaylistRepository
    private lateinit var secretCipher: ProviderSecretCipher
    private lateinit var repository: ProviderAccountRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        providerDao = mockk()
        playlistRepository = mockk()
        secretCipher = mockk()

        every { providerDao.observeProviders() } returns emptyFlow()
        every { secretCipher.encryptOrNull(any()) } answers { firstArg() }
        every { secretCipher.decryptOrNull(any()) } answers { firstArg() }

        repository = ProviderAccountRepositoryImpl(
            providerDao = providerDao,
            playlistRepository = playlistRepository,
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
        assertTrue(status.detail.orEmpty().contains("dvrs=1"))
        assertTrue(status.detail.orEmpty().contains("channels=2"))
        assertEquals("/livetv/dvrs?X-Plex-Token=plex-token", server.takeRequest().path)
        assertEquals("/livetv/dvrs/dvr-1/channels?X-Plex-Token=plex-token", server.takeRequest().path)
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

    private fun assertSuccess(result: AppResult<*>) {
        if (result !is AppResult.Success) {
            fail((result as? AppResult.Error)?.message ?: "Expected AppResult.Success, got $result")
        }
    }
}
