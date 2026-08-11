package com.iptv.tv.feature.player

import androidx.lifecycle.SavedStateHandle
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.AppStartDestination
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EngineStatus
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.ParentalControlProfile
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlaybackHistoryItem
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.RecordingStorageInfo
import com.iptv.tv.core.model.RecordingStorageLocation
import com.iptv.tv.core.model.ScannerLearnedQuery
import com.iptv.tv.core.model.ScannerProxySettings
import com.iptv.tv.core.model.SyncLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelMultiviewTest {
    private val dispatcher: TestDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun calculateSupportedMultiviewPaneCount_returnsFourForStrongDevice() {
        val supported = calculateSupportedMultiviewPaneCount(
            cpuCount = 8,
            maxMemoryBytes = 768L * 1024L * 1024L
        )

        assertEquals(4, supported)
    }

    @Test
    fun calculateSupportedMultiviewPaneCount_returnsTwoForWeakDevice() {
        val supported = calculateSupportedMultiviewPaneCount(
            cpuCount = 4,
            maxMemoryBytes = 256L * 1024L * 1024L
        )

        assertEquals(2, supported)
    }

    @Test
    fun evaluateMultiviewDeviceCapability_explainsFourUpLimit() {
        val capability = evaluateMultiviewDeviceCapability(
            cpuCount = 4,
            maxMemoryBytes = 256L * 1024L * 1024L
        )

        assertEquals(2, capability.supportedPaneCount)
        assertTrue(capability.summary.contains("4-up отключён"))
        assertTrue(capability.warnings.any { it.contains("CPU") })
        assertTrue(capability.warnings.any { it.contains("heap") })
    }

    @Test
    fun playChannelInPane_startsSecondPaneAndEnablesTwoUp() = runTest(dispatcher) {
        val channels = listOf(
            testChannel(id = 10L, name = "News HD", streamUrl = "https://example.com/live/news.m3u8")
        )
        val viewModel = createViewModel(channels = channels)
        advanceUntilIdle()

        viewModel.playChannelInPane(channelId = 10L, paneIndex = 2)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.multiviewEnabled)
        assertEquals(MultiviewMode.TWO_UP, state.multiviewMode)
        assertNotNull(state.secondaryInternalSession)
        assertEquals("News HD", state.secondaryInternalSession?.channelName)
        assertNull(state.lastError)
    }

    @Test
    fun playChannelInPane_startsThirdPaneAndEnablesFourUp() = runTest(dispatcher) {
        val channels = listOf(
            testChannel(id = 10L, name = "News HD", streamUrl = "https://example.com/live/news.m3u8"),
            testChannel(id = 11L, name = "Sports HD", streamUrl = "https://example.com/live/sports.m3u8")
        )
        val viewModel = createViewModel(channels = channels, supportedPaneCount = 4)
        advanceUntilIdle()

        viewModel.playChannelInPane(channelId = 11L, paneIndex = 3)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.multiviewEnabled)
        assertEquals(MultiviewMode.FOUR_UP, state.multiviewMode)
        assertNotNull(state.tertiaryInternalSession)
        assertEquals("Sports HD", state.tertiaryInternalSession?.channelName)
        assertNull(state.lastError)
    }

    @Test
    fun playChannelInPane_rejectsAceDescriptorForAdditionalPane() = runTest(dispatcher) {
        val channels = listOf(
            testChannel(id = 10L, name = "Torrent Channel", streamUrl = "acestream://abcdef1234567890")
        )
        val viewModel = createViewModel(channels = channels)
        advanceUntilIdle()

        viewModel.playChannelInPane(channelId = 10L, paneIndex = 2)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.secondaryInternalSession)
        assertTrue(state.lastError?.contains("http/https") == true)
    }

    @Test
    fun playSelectedInternal_preservesLegacyAceInfoHashForEmbeddedLiveRuntime() = runTest(dispatcher) {
        val infoHash = "568159b1059c7bbe3eaf40f123541fef86ef83cb"
        val source = "http://127.0.0.1:6878/ace/getstream?infohash=$infoHash"
        val engineRepository = FakeEngineRepository()
        val viewModel = createViewModel(
            channels = listOf(testChannel(id = 10L, name = "Animal Planet HD", streamUrl = source)),
            engineRepository = engineRepository
        )
        advanceUntilIdle()

        viewModel.playSelectedInternal()
        advanceUntilIdle()

        assertEquals("acestream:?infohash=$infoHash", engineRepository.lastResolvedSource)
        assertNull(viewModel.uiState.value.lastError)
    }

    @Test
    fun enableFourUpMultiview_enablesFourPanesWhenDeviceSupportsIt() = runTest(dispatcher) {
        val channels = listOf(
            testChannel(id = 10L, name = "News HD", streamUrl = "https://example.com/live/news.m3u8"),
            testChannel(id = 11L, name = "Sports HD", streamUrl = "https://example.com/live/sports.m3u8")
        )
        val viewModel = createViewModel(channels = channels, supportedPaneCount = 4)
        advanceUntilIdle()

        viewModel.enableFourUpMultiview()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MultiviewMode.FOUR_UP, state.multiviewMode)
        assertTrue(state.multiviewEnabled)
        assertNull(state.lastError)
    }

    @Test
    fun enableFourUpMultiview_rejectsFourPanesWhenDeviceDoesNotSupportIt() = runTest(dispatcher) {
        val channels = listOf(
            testChannel(id = 10L, name = "News HD", streamUrl = "https://example.com/live/news.m3u8"),
            testChannel(id = 11L, name = "Sports HD", streamUrl = "https://example.com/live/sports.m3u8")
        )
        val viewModel = createViewModel(channels = channels, supportedPaneCount = 2)
        advanceUntilIdle()

        viewModel.enableFourUpMultiview()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(MultiviewMode.OFF, state.multiviewMode)
        assertTrue(state.lastError?.contains("4-up") == true)
        assertTrue(state.lastError?.contains("Причина") == true)
    }

    @Test
    fun stopPane_clearsRequestedAdditionalPane() = runTest(dispatcher) {
        val channels = listOf(
            testChannel(id = 10L, name = "News HD", streamUrl = "https://example.com/live/news.m3u8")
        )
        val viewModel = createViewModel(channels = channels)
        advanceUntilIdle()

        viewModel.playChannelInPane(channelId = 10L, paneIndex = 2)
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.secondaryInternalSession)

        viewModel.stopPane(2)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.secondaryInternalSession)
        assertEquals("Окно 2 остановлено", state.lastInfo)
    }

    @Test
    fun onInternalPlaybackError_ignoresStaleSessionError() = runTest(dispatcher) {
        val channels = listOf(
            testChannel(id = 10L, name = "News HD", streamUrl = "https://example.com/live/news.m3u8"),
            testChannel(id = 11L, name = "Sports HD", streamUrl = "https://example.com/live/sports.m3u8")
        )
        val viewModel = createViewModel(channels = channels)
        advanceUntilIdle()

        viewModel.playSelectedInternal()
        advanceUntilIdle()
        val firstSession = viewModel.uiState.value.internalSession
        assertNotNull(firstSession)

        viewModel.playChannelInternal(11L)
        advanceUntilIdle()
        val secondSession = viewModel.uiState.value.internalSession
        assertNotNull(secondSession)

        viewModel.onInternalPlaybackError(
            message = "Source error: InvalidResponseCodeException: Response code: 504",
            sessionId = firstSession?.sessionId
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(11L, state.internalSession?.channelId)
        assertEquals(secondSession?.sessionId, state.internalSession?.sessionId)
        assertEquals(0, state.retryAttempt)
    }

    @Test
    fun p2pSourceErrorRebuildsEngineSessionInsteadOfRetryingDeadLoopbackUrl() = runTest(dispatcher) {
        val contentId = "50bc2f512793f1e745fb5bd5b5a6afca199c2d19"
        val source = "http://127.0.0.1:6878/ace/getstream?id=$contentId"
        val engineRepository = FakeEngineRepository()
        val viewModel = createViewModel(
            channels = listOf(testChannel(id = 10L, name = "Torrent TV", streamUrl = source)),
            engineRepository = engineRepository
        )
        advanceUntilIdle()

        viewModel.playSelectedInternal()
        advanceUntilIdle()
        val firstSession = requireNotNull(viewModel.uiState.value.internalSession)

        viewModel.onInternalPlaybackError(
            message = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Source error",
            sessionId = firstSession.sessionId
        )
        advanceUntilIdle()

        val restarted = requireNotNull(viewModel.uiState.value.internalSession)
        assertEquals(2, engineRepository.resolveCount)
        assertTrue(restarted.streamUrl != firstSession.streamUrl)
        assertEquals(1, viewModel.uiState.value.retryAttempt)
        assertTrue(viewModel.uiState.value.lastInfo?.startsWith("P2P-сессия переподключена") == true)
    }

    private fun createViewModel(
        channels: List<Channel>,
        supportedPaneCount: Int = 4,
        engineRepository: FakeEngineRepository = FakeEngineRepository()
    ): PlayerViewModel {
        val playlist = Playlist(
            id = 1L,
            name = "Demo",
            sourceType = PlaylistSourceType.URL,
            source = "https://example.com/playlist.m3u",
            epgSourceUrl = null,
            scheduleHours = 0,
            lastSyncedAt = null,
            channelCount = channels.size,
            isCustom = false
        )
        return PlayerViewModel(
            playlistRepository = FakePlaylistRepository(playlist = playlist, channels = channels),
            settingsRepository = FakeSettingsRepository(),
            engineRepository = engineRepository,
            favoritesRepository = FakeFavoritesRepository(),
            diagnosticsRepository = FakeDiagnosticsRepository(),
            historyRepository = FakeHistoryRepository(),
            savedStateHandle = SavedStateHandle(
                mapOf(PLAYER_MULTIVIEW_SUPPORTED_PANES_ARG to supportedPaneCount)
            )
        )
    }

    private fun testChannel(id: Long, name: String, streamUrl: String): Channel {
        return Channel(
            id = id,
            playlistId = 1L,
            tvgId = "tvg$id",
            name = name,
            group = "General",
            logo = null,
            streamUrl = streamUrl,
            health = ChannelHealth.AVAILABLE,
            orderIndex = id.toInt(),
            isHidden = false
        )
    }

    private class FakePlaylistRepository(
        playlist: Playlist,
        channels: List<Channel>
    ) : PlaylistRepository {
        private val playlistsFlow = MutableStateFlow(listOf(playlist))
        private val channelsFlow = MutableStateFlow(channels)

        override fun observePlaylists(): Flow<List<Playlist>> = playlistsFlow
        override fun observeChannels(playlistId: Long): Flow<List<Channel>> = channelsFlow
        override suspend fun importFromUrl(
            url: String,
            name: String,
            catalogOrigin: CatalogOriginKind
        ): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromXtream(baseUrl: String, username: String, password: String, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromStalker(portalUrl: String, macAddress: String, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromHdHomeRun(baseUrl: String, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromTvheadend(baseUrl: String, username: String?, password: String?, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromJellyfin(baseUrl: String, apiKey: String, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromPlex(baseUrl: String, token: String, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromText(text: String, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun importFromFile(pathOrUri: String, name: String): AppResult<PlaylistImportReport> = error("Not used")
        override suspend fun validatePlaylist(playlistId: Long): AppResult<PlaylistValidationReport> = error("Not used")
        override suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun refreshAllPlaylists(): AppResult<Int> = AppResult.Success(1)
        override suspend fun deletePlaylist(playlistId: Long): AppResult<Int> = AppResult.Success(1)
        override suspend fun setPlaylistEpgSource(playlistId: Long, epgSourceUrl: String?): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun getChannelById(channelId: Long): AppResult<Channel> {
            val channel = channelsFlow.value.firstOrNull { it.id == channelId }
                ?: return AppResult.Error("Channel not found")
            return AppResult.Success(channel)
        }
        override suspend fun getPlaylistContentSummary(playlistId: Long): AppResult<PlaylistContentSummary> = error("Not used")
        override suspend fun getChannelEpgNowNext(channelId: Long): AppResult<ChannelEpgInfo> {
            val channel = channelsFlow.value.first { it.id == channelId }
            return AppResult.Success(
                ChannelEpgInfo(
                    channelId = channel.id,
                    channelName = channel.name,
                    tvgId = channel.tvgId,
                    epgSourceUrl = null,
                    matchedBy = "test",
                    now = null,
                    next = null,
                    upcoming = emptyList()
                )
            )
        }
        override suspend fun getPlaylistEpgWindow(
            playlistId: Long,
            startEpochMs: Long,
            endEpochMs: Long,
            query: String?
        ): AppResult<Map<Long, List<EpgProgram>>> = AppResult.Success(emptyMap())
    }

    private class FakeSettingsRepository : SettingsRepository {
        override fun observeAppStartDestination(): Flow<AppStartDestination> =
            MutableStateFlow(AppStartDestination.HOME)
        override fun observeDefaultPlayer(): Flow<PlayerType> = MutableStateFlow(PlayerType.INTERNAL)
        override fun observeBufferProfile(): Flow<BufferProfile> = MutableStateFlow(BufferProfile.STANDARD)
        override fun observeManualBuffer(): Flow<ManualBufferSettings> = MutableStateFlow(
            ManualBufferSettings(12_000, 2_000, 50_000)
        )
        override fun observeChannelPlayerOverride(channelId: Long): Flow<PlayerType?> = MutableStateFlow(null)
        override fun observeEngineEndpoint(): Flow<String> = MutableStateFlow("http://127.0.0.1:6878")
        override fun observeTorEnabled(): Flow<Boolean> = MutableStateFlow(false)
        override fun observeLegalAccepted(): Flow<Boolean> = MutableStateFlow(true)
        override fun observeAllowInsecureUrls(): Flow<Boolean> = MutableStateFlow(false)
        override fun observeProviderAutoSyncEnabled(): Flow<Boolean> = MutableStateFlow(true)
        override fun observeProviderAutoSyncIntervalHours(): Flow<Int> = MutableStateFlow(12)
        override fun observeDownloadsWifiOnly(): Flow<Boolean> = MutableStateFlow(true)
        override fun observeMaxParallelDownloads(): Flow<Int> = MutableStateFlow(1)
        override fun observeRecordingStorageLocation(): Flow<RecordingStorageLocation> =
            MutableStateFlow(RecordingStorageLocation.INTERNAL)
        override fun observeRecordingStorageCustomTreeUri(): Flow<String?> = MutableStateFlow(null)
        override fun observeScannerAiEnabled(): Flow<Boolean> = MutableStateFlow(true)
        override fun observeScannerProxySettings(): Flow<ScannerProxySettings> =
            MutableStateFlow(ScannerProxySettings(enabled = false))
        override fun observeScannerLearnedQueries(): Flow<List<ScannerLearnedQuery>> = emptyFlow()
        override fun observeParentalControlSettings(): Flow<ParentalControlSettings> = MutableStateFlow(
            ParentalControlSettings(
                enabled = false,
                pinConfigured = false,
                hideAdultChannels = true,
                blockedKeywords = emptyList()
            )
        )
        override fun observeParentalControlProfiles(): Flow<List<ParentalControlProfile>> = emptyFlow()

        override suspend fun setAppStartDestination(destination: AppStartDestination) = Unit
        override suspend fun setDefaultPlayer(playerType: PlayerType) = Unit
        override suspend fun setBufferProfile(profile: BufferProfile) = Unit
        override suspend fun setManualBuffer(startMs: Int, rebufferMs: Int, maxMs: Int) = Unit
        override suspend fun setChannelPlayerOverride(channelId: Long, playerType: PlayerType?) = Unit
        override suspend fun setEngineEndpoint(endpoint: String) = Unit
        override suspend fun setTorEnabled(enabled: Boolean) = Unit
        override suspend fun setLegalAccepted(accepted: Boolean) = Unit
        override suspend fun setAllowInsecureUrls(allowed: Boolean) = Unit
        override suspend fun setProviderAutoSyncEnabled(enabled: Boolean) = Unit
        override suspend fun setProviderAutoSyncIntervalHours(hours: Int) = Unit
        override suspend fun setDownloadsWifiOnly(enabled: Boolean) = Unit
        override suspend fun setMaxParallelDownloads(value: Int) = Unit
        override suspend fun setRecordingStorageLocation(location: RecordingStorageLocation) = Unit
        override suspend fun setRecordingStorageCustomTreeUri(uri: String?) = Unit
        override suspend fun getRecordingStorageInfo(location: RecordingStorageLocation): RecordingStorageInfo {
            return RecordingStorageInfo(
                location = location,
                path = "/tmp/recordings",
                exists = true,
                writable = true,
                freeBytes = 1024L * 1024L,
                usingFallback = false
            )
        }
        override suspend fun setScannerAiEnabled(enabled: Boolean) = Unit
        override suspend fun setScannerProxySettings(settings: ScannerProxySettings) = Unit
        override suspend fun recordScannerLearning(query: String, relatedQueries: List<String>, presetId: String?) = Unit
        override suspend fun clearScannerLearning() = Unit
        override suspend fun setParentalControl(enabled: Boolean, pin: String?, hideAdultChannels: Boolean, blockedKeywords: List<String>) = Unit
        override suspend fun verifyParentalPin(pin: String): Boolean = true
        override suspend fun saveParentalControlProfile(
            name: String,
            pin: String?,
            blockedKeywords: List<String>,
            lockedSettings: Boolean
        ): AppResult<ParentalControlProfile> = AppResult.Error("unsupported")
        override suspend fun activateParentalControlProfile(profileId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun clearActiveParentalControlProfile(): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun deleteParentalControlProfile(profileId: Long): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun exportParentalControlProfiles(): AppResult<String> = AppResult.Success("""{"profiles":[]}""")
        override suspend fun importParentalControlProfiles(payload: String, replaceExisting: Boolean): AppResult<Int> =
            AppResult.Success(0)
    }

    private class FakeEngineRepository : EngineRepository {
        var lastResolvedSource: String? = null
            private set
        var resolveCount: Int = 0
            private set

        override suspend fun connect(endpoint: String): AppResult<Unit> = AppResult.Success(Unit)
        override suspend fun refreshStatus(): AppResult<EngineStatus> = AppResult.Success(
            EngineStatus(connected = false, peers = 0, speedKbps = 0, message = "idle")
        )
        override fun observeStatus(): Flow<EngineStatus> = MutableStateFlow(
            EngineStatus(connected = false, peers = 0, speedKbps = 0, message = "idle")
        )
        override suspend fun resolveTorrentStream(magnetOrAce: String): AppResult<String> {
            lastResolvedSource = magnetOrAce
            resolveCount += 1
            return AppResult.Success("http://127.0.0.1:${62_000 + resolveCount}/live.ts")
        }
    }

    private class FakeFavoritesRepository : FavoritesRepository {
        override fun observeFavorites(): Flow<List<Channel>> = MutableStateFlow(emptyList())
        override fun observeFavoriteChannelIds(): Flow<Set<Long>> = MutableStateFlow(emptySet())
        override suspend fun toggleFavorite(channelId: Long) = Unit
    }

    private class FakeDiagnosticsRepository : DiagnosticsRepository {
        override fun observeLogs(limit: Int): Flow<List<SyncLog>> = MutableStateFlow(emptyList())
        override suspend fun addLog(status: String, message: String, playlistId: Long?) = Unit
    }

    private class FakeHistoryRepository : HistoryRepository {
        override fun observeHistory(limit: Int): Flow<List<PlaybackHistoryItem>> = MutableStateFlow(emptyList())
        override suspend fun add(channelId: Long, channelName: String) = Unit
        override suspend fun clear() = Unit
    }
}
