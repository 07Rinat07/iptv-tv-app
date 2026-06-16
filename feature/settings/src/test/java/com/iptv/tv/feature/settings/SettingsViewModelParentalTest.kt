package com.iptv.tv.feature.settings

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.domain.repository.TvHomeIntegrationRepository
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.RecordingStorageInfo
import com.iptv.tv.core.model.RecordingStorageLocation
import com.iptv.tv.core.model.ScannerLearnedQuery
import com.iptv.tv.core.model.ScannerProxySettings
import com.iptv.tv.core.model.TvHomeChannelState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelParentalTest {
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
    fun saveParentalControl_requiresPinWhenEnablingFirstTime() = runTest(dispatcher) {
        val settingsRepository = FakeSettingsRepository(
            parental = ParentalControlSettings(
                enabled = false,
                pinConfigured = false,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            )
        )
        val viewModel = SettingsViewModel(settingsRepository, FakeTvHomeIntegrationRepository())
        advanceUntilIdle()

        viewModel.setParentalEnabled(true)
        viewModel.saveParentalControl()
        advanceUntilIdle()

        assertEquals("Для включения родительского контроля задайте PIN", viewModel.uiState.value.lastError)
        assertEquals(0, settingsRepository.setParentalControlCalls)
    }

    @Test
    fun saveParentalControl_rejectsShortNewPin() = runTest(dispatcher) {
        val settingsRepository = FakeSettingsRepository(
            parental = ParentalControlSettings(
                enabled = false,
                pinConfigured = false,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            )
        )
        val viewModel = SettingsViewModel(settingsRepository, FakeTvHomeIntegrationRepository())
        advanceUntilIdle()

        viewModel.setParentalEnabled(true)
        viewModel.updateParentalNewPin("12")
        viewModel.saveParentalControl()
        advanceUntilIdle()

        assertEquals("PIN должен быть минимум 4 цифры", viewModel.uiState.value.lastError)
        assertEquals(0, settingsRepository.setParentalControlCalls)
    }

    @Test
    fun saveParentalControl_requiresCurrentPinWhenPinAlreadyConfigured() = runTest(dispatcher) {
        val settingsRepository = FakeSettingsRepository(
            parental = ParentalControlSettings(
                enabled = true,
                pinConfigured = true,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            ),
            expectedPin = "1234"
        )
        val viewModel = SettingsViewModel(settingsRepository, FakeTvHomeIntegrationRepository())
        advanceUntilIdle()

        viewModel.updateParentalCurrentPin("0000")
        viewModel.saveParentalControl()
        advanceUntilIdle()

        assertEquals("Текущий PIN неверный", viewModel.uiState.value.lastError)
        assertEquals(1, settingsRepository.verifyParentalPinCalls)
        assertEquals(0, settingsRepository.setParentalControlCalls)
    }

    @Test
    fun saveParentalControl_savesKeywordsAndClearsPinsAfterValidPin() = runTest(dispatcher) {
        val settingsRepository = FakeSettingsRepository(
            parental = ParentalControlSettings(
                enabled = false,
                pinConfigured = false,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            )
        )
        val viewModel = SettingsViewModel(settingsRepository, FakeTvHomeIntegrationRepository())
        advanceUntilIdle()

        viewModel.setParentalEnabled(true)
        viewModel.setParentalHideAdultChannels(false)
        viewModel.updateParentalNewPin("987654")
        viewModel.updateParentalKeywords("adult; xxx\n18+")
        viewModel.saveParentalControl()
        advanceUntilIdle()

        assertEquals(1, settingsRepository.setParentalControlCalls)
        assertTrue(settingsRepository.lastParentalEnabled!!)
        assertEquals("987654", settingsRepository.lastParentalPin)
        assertFalse(settingsRepository.lastParentalHideAdultChannels!!)
        assertEquals(listOf("adult", "xxx", "18+"), settingsRepository.lastParentalKeywords)
        assertEquals("", viewModel.uiState.value.parentalCurrentPin)
        assertEquals("", viewModel.uiState.value.parentalNewPin)
        assertEquals("Родительский контроль сохранён", viewModel.uiState.value.lastInfo)
        assertNull(viewModel.uiState.value.lastError)
    }

    @Test
    fun pinInputs_acceptOnlyDigitsAndLimitLength() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(
            FakeSettingsRepository(
                parental = ParentalControlSettings(
                    enabled = false,
                    pinConfigured = false,
                    hideAdultChannels = true,
                    blockedKeywords = listOf("adult")
                )
            ),
            FakeTvHomeIntegrationRepository()
        )
        advanceUntilIdle()

        viewModel.updateParentalCurrentPin("12ab34567890")
        viewModel.updateParentalNewPin("98xx76543210")

        assertEquals("12345678", viewModel.uiState.value.parentalCurrentPin)
        assertEquals("98765432", viewModel.uiState.value.parentalNewPin)
    }

    private class FakeSettingsRepository(
        parental: ParentalControlSettings,
        private val expectedPin: String = ""
    ) : SettingsRepository {
        private val parentalFlow = MutableStateFlow(parental)
        var setParentalControlCalls = 0
        var verifyParentalPinCalls = 0
        var lastParentalEnabled: Boolean? = null
        var lastParentalPin: String? = null
        var lastParentalHideAdultChannels: Boolean? = null
        var lastParentalKeywords: List<String> = emptyList()

        override fun observeDefaultPlayer(): Flow<PlayerType> = MutableStateFlow(PlayerType.INTERNAL)
        override fun observeBufferProfile(): Flow<BufferProfile> = MutableStateFlow(BufferProfile.STANDARD)
        override fun observeManualBuffer(): Flow<ManualBufferSettings> = MutableStateFlow(
            ManualBufferSettings(startMs = 12000, rebufferMs = 2000, maxMs = 50000)
        )
        override fun observeChannelPlayerOverride(channelId: Long): Flow<PlayerType?> = MutableStateFlow(null)
        override fun observeEngineEndpoint(): Flow<String> = MutableStateFlow("http://127.0.0.1:6878")
        override fun observeTorEnabled(): Flow<Boolean> = MutableStateFlow(false)
        override fun observeLegalAccepted(): Flow<Boolean> = MutableStateFlow(false)
        override fun observeAllowInsecureUrls(): Flow<Boolean> = MutableStateFlow(false)
        override fun observeDownloadsWifiOnly(): Flow<Boolean> = MutableStateFlow(true)
        override fun observeMaxParallelDownloads(): Flow<Int> = MutableStateFlow(1)
        override fun observeRecordingStorageLocation(): Flow<RecordingStorageLocation> =
            MutableStateFlow(RecordingStorageLocation.INTERNAL)
        override fun observeRecordingStorageCustomTreeUri(): Flow<String?> = MutableStateFlow(null)
        override fun observeScannerAiEnabled(): Flow<Boolean> = MutableStateFlow(true)
        override fun observeScannerProxySettings(): Flow<ScannerProxySettings> = MutableStateFlow(
            ScannerProxySettings(enabled = false)
        )
        override fun observeScannerLearnedQueries(): Flow<List<ScannerLearnedQuery>> = emptyFlow()
        override fun observeParentalControlSettings(): Flow<ParentalControlSettings> = parentalFlow

        override suspend fun setDefaultPlayer(playerType: PlayerType) = Unit
        override suspend fun setBufferProfile(profile: BufferProfile) = Unit
        override suspend fun setManualBuffer(startMs: Int, rebufferMs: Int, maxMs: Int) = Unit
        override suspend fun setChannelPlayerOverride(channelId: Long, playerType: PlayerType?) = Unit
        override suspend fun setEngineEndpoint(endpoint: String) = Unit
        override suspend fun setTorEnabled(enabled: Boolean) = Unit
        override suspend fun setLegalAccepted(accepted: Boolean) = Unit
        override suspend fun setAllowInsecureUrls(allowed: Boolean) = Unit
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
                freeBytes = 1024,
                usingFallback = false
            )
        }
        override suspend fun setScannerAiEnabled(enabled: Boolean) = Unit
        override suspend fun setScannerProxySettings(settings: ScannerProxySettings) = Unit
        override suspend fun recordScannerLearning(query: String, relatedQueries: List<String>, presetId: String?) = Unit
        override suspend fun clearScannerLearning() = Unit

        override suspend fun setParentalControl(
            enabled: Boolean,
            pin: String?,
            hideAdultChannels: Boolean,
            blockedKeywords: List<String>
        ) {
            setParentalControlCalls += 1
            lastParentalEnabled = enabled
            lastParentalPin = pin
            lastParentalHideAdultChannels = hideAdultChannels
            lastParentalKeywords = blockedKeywords
            parentalFlow.value = ParentalControlSettings(
                enabled = enabled,
                pinConfigured = pin != null || parentalFlow.value.pinConfigured,
                hideAdultChannels = hideAdultChannels,
                blockedKeywords = blockedKeywords
            )
        }

        override suspend fun verifyParentalPin(pin: String): Boolean {
            verifyParentalPinCalls += 1
            return pin == expectedPin
        }
    }

    private class FakeTvHomeIntegrationRepository : TvHomeIntegrationRepository {
        override fun observeChannelStates(): Flow<List<TvHomeChannelState>> = MutableStateFlow(emptyList())
        override suspend fun publishRecentChannels(): AppResult<Int> = AppResult.Success(0)
        override suspend fun publishFavorites(): AppResult<Int> = AppResult.Success(0)
        override suspend fun publishWatchNext(): AppResult<Int> = AppResult.Success(0)
        override suspend fun publishRecordings(): AppResult<Int> = AppResult.Success(0)
        override suspend fun setEnabled(state: TvHomeChannelState): AppResult<Unit> = AppResult.Success(Unit)
    }
}
