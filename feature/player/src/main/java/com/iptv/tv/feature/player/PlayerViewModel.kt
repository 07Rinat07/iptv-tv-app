package com.iptv.tv.feature.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.player.BufferConfig
import com.iptv.tv.core.player.ExternalVlcLauncher
import com.iptv.tv.core.player.bufferConfigForProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import javax.inject.Inject

const val PLAYER_PLAYLIST_ID_ARG = "playlistId"
const val PLAYER_CHANNEL_ID_ARG = "channelId"

data class InternalPlaybackSession(
    val sessionId: Long,
    val channelId: Long,
    val channelName: String,
    val streamUrl: String,
    val requestHeaders: Map<String, String>,
    val bufferConfig: BufferConfig
)

enum class PlayerVideoScale {
    FIT,
    FILL,
    ZOOM
}

data class PlayerUiState(
    val title: String = "Плеер",
    val description: String = "Встроенный Media3 или внешний VLC с fallback",
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: Long? = null,
    val channels: List<Channel> = emptyList(),
    val availableGroups: List<String> = emptyList(),
    val selectedGroup: String? = null,
    val channelQuery: String = "",
    val selectedChannelId: Long? = null,
    val favoriteChannelIds: Set<Long> = emptySet(),
    val defaultPlayer: PlayerType = PlayerType.INTERNAL,
    val channelPlayerOverride: PlayerType? = null,
    val effectivePlayer: PlayerType = PlayerType.INTERNAL,
    val selectedAceDescriptor: String? = null,
    val selectedChannelAceCapable: Boolean = false,
    val bufferProfile: BufferProfile = BufferProfile.STANDARD,
    val manualBuffer: ManualBufferSettings = ManualBufferSettings(12_000, 2_000, 50_000),
    val engineEndpoint: String = "http://127.0.0.1:6878",
    val torEnabled: Boolean = false,
    val engineConnected: Boolean = false,
    val enginePeers: Int = 0,
    val engineSpeedKbps: Int = 0,
    val engineMessage: String = "Engine not connected",
    val selectedStreamKind: String = "Канал не выбран",
    val resolvedStreamUrl: String? = null,
    val channelEpgInfo: ChannelEpgInfo? = null,
    val epgStatus: String = "EPG: нет данных",
    val internalSession: InternalPlaybackSession? = null,
    val playerVideoScale: PlayerVideoScale = PlayerVideoScale.FIT,
    val internalPlayerExpanded: Boolean = false,
    val testStreamUrl: String = "",
    val testStreamResult: String? = null,
    val isTestingStream: Boolean = false,
    val isStartingPlayback: Boolean = false,
    val retryAttempt: Int = 0,
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val engineRepository: EngineRepository,
    private val favoritesRepository: FavoritesRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val historyRepository: HistoryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val requestedPlaylistId: Long? = savedStateHandle.get<Long>(PLAYER_PLAYLIST_ID_ARG)
        ?: savedStateHandle.get<String>(PLAYER_PLAYLIST_ID_ARG)?.toLongOrNull()
    private var requestedChannelId: Long? = savedStateHandle.get<Long>(PLAYER_CHANNEL_ID_ARG)
        ?: savedStateHandle.get<String>(PLAYER_CHANNEL_ID_ARG)?.toLongOrNull()

    private var observedPlaylistId: Long? = null
    private var channelsJob: Job? = null
    private var overrideJob: Job? = null
    private var epgJob: Job? = null
    private var internalStartElapsedMs: Long = 0L
    private val vlcLauncher = ExternalVlcLauncher()

    init {
        observePlaylists()
        observeSettings()
        observeEngineStatus()
        observeFavorites()
    }

    fun selectPlaylist(playlistId: Long) {
        _uiState.update {
            it.copy(
                selectedPlaylistId = playlistId,
                selectedGroup = null,
                selectedChannelId = null,
                channelPlayerOverride = null,
                selectedStreamKind = "Канал не выбран",
                selectedAceDescriptor = null,
                selectedChannelAceCapable = false,
                resolvedStreamUrl = null,
                channelEpgInfo = null,
                epgStatus = "EPG: загрузка...",
                internalSession = null,
                lastError = null,
                lastInfo = null
            )
        }
        observeChannels(playlistId)
    }

    fun selectChannel(channelId: Long) {
        val channel = _uiState.value.channels.firstOrNull { it.id == channelId }
        val aceDescriptor = channel?.let { detectAceDescriptor(it.streamUrl) }
        _uiState.update { state ->
            state.copy(
                selectedChannelId = channelId,
                selectedStreamKind = channel?.let { describeStreamKind(it.streamUrl) } ?: "Канал не выбран",
                selectedAceDescriptor = aceDescriptor,
                selectedChannelAceCapable = aceDescriptor != null,
                testStreamUrl = channel?.streamUrl ?: state.testStreamUrl,
                testStreamResult = null,
                resolvedStreamUrl = null,
                channelEpgInfo = null,
                epgStatus = "EPG: загрузка...",
                lastError = null,
                lastInfo = null
            )
        }
        observeChannelOverride(channelId)
        loadEpgForChannel(channelId)
    }

    fun selectGroup(group: String?) {
        _uiState.update { state ->
            val normalized = group?.trim()?.ifBlank { null }
            val channelsByGroup = state.channels.filter { channel ->
                normalized == null || channel.group?.trim() == normalized
            }
            val selected = state.selectedChannelId?.takeIf { selectedId ->
                channelsByGroup.any { it.id == selectedId }
            } ?: channelsByGroup.firstOrNull()?.id
            val selectedChannel = channelsByGroup.firstOrNull { it.id == selected }
            val aceDescriptor = selectedChannel?.let { detectAceDescriptor(it.streamUrl) }
            state.copy(
                selectedGroup = normalized,
                selectedChannelId = selected,
                selectedStreamKind = selectedChannel?.let { describeStreamKind(it.streamUrl) } ?: "Канал не выбран",
                selectedAceDescriptor = aceDescriptor,
                selectedChannelAceCapable = aceDescriptor != null,
                resolvedStreamUrl = null,
                channelPlayerOverride = null,
                channelEpgInfo = null,
                epgStatus = if (selectedChannel != null) "EPG: загрузка..." else "EPG: канал не выбран",
                internalSession = null,
                lastError = null,
                lastInfo = null
            )
        }
        val selectedId = _uiState.value.selectedChannelId
        if (selectedId != null) {
            observeChannelOverride(selectedId)
            loadEpgForChannel(selectedId)
        } else {
            overrideJob?.cancel()
            epgJob?.cancel()
            _uiState.update {
                it.copy(
                    channelPlayerOverride = null,
                    effectivePlayer = it.defaultPlayer,
                    selectedAceDescriptor = null,
                    selectedChannelAceCapable = false
                )
            }
        }
    }

    fun updateChannelQuery(value: String) {
        _uiState.update { it.copy(channelQuery = value) }
    }

    fun updateTestStreamUrl(value: String) {
        _uiState.update { it.copy(testStreamUrl = value, testStreamResult = null) }
    }

    fun fillTestStreamFromSelected() {
        val state = _uiState.value
        val selected = state.channels.firstOrNull { it.id == state.selectedChannelId }
        val url = selected?.streamUrl
        if (url.isNullOrBlank()) {
            _uiState.update { it.copy(lastError = "Сначала выберите канал", lastInfo = null) }
            return
        }
        _uiState.update {
            it.copy(
                testStreamUrl = url,
                testStreamResult = "URL взят из выбранного канала",
                lastError = null,
                lastInfo = null
            )
        }
    }

    fun probeTestStream() {
        val state = _uiState.value
        val raw = state.testStreamUrl.trim()
        if (raw.isBlank()) {
            _uiState.update { it.copy(lastError = "Введите URL для теста", lastInfo = null) }
            return
        }
        val prepared = parseKodiStyleStream(raw)
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingStream = true, testStreamResult = "Проверка потока...") }
            val result = probeStreamUrl(prepared.streamUrl, prepared.headers)
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isTestingStream = false,
                            testStreamResult = result.data,
                            lastInfo = "Тест потока выполнен",
                            lastError = null
                        )
                    }
                    safeLog(
                        status = "player_stream_probe_ok",
                        message = "url=${prepared.streamUrl.take(MAX_PROBE_URL_LOG)} | ${result.data}",
                        playlistId = _uiState.value.selectedPlaylistId
                    )
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isTestingStream = false,
                            testStreamResult = result.message,
                            lastError = "Проба потока: ${result.message}",
                            lastInfo = null
                        )
                    }
                    safeLog(
                        status = "player_stream_probe_error",
                        message = "url=${prepared.streamUrl.take(MAX_PROBE_URL_LOG)} | ${result.message}",
                        playlistId = _uiState.value.selectedPlaylistId
                    )
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun toggleInternalPlayerSize() {
        _uiState.update { current ->
            val expanded = !current.internalPlayerExpanded
            current.copy(
                internalPlayerExpanded = expanded,
                lastInfo = if (expanded) {
                    "Режим плеера: большой экран"
                } else {
                    "Режим плеера: малый экран"
                },
                lastError = null
            )
        }
    }

    fun setInternalPlayerExpanded(expanded: Boolean) {
        _uiState.update {
            it.copy(
                internalPlayerExpanded = expanded,
                lastInfo = if (expanded) {
                    "Режим плеера: большой экран"
                } else {
                    "Режим плеера: малый экран"
                },
                lastError = null
            )
        }
    }

    fun cycleVideoScale() {
        _uiState.update { state ->
            val next = when (state.playerVideoScale) {
                PlayerVideoScale.FIT -> PlayerVideoScale.FILL
                PlayerVideoScale.FILL -> PlayerVideoScale.ZOOM
                PlayerVideoScale.ZOOM -> PlayerVideoScale.FIT
            }
            state.copy(
                playerVideoScale = next,
                lastInfo = "Режим кадра переключен: $next",
                lastError = null
            )
        }
    }

    fun clearChannelOverride() {
        val channelId = _uiState.value.selectedChannelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Канал не выбран") }
            return
        }
        viewModelScope.launch {
            settingsRepository.setChannelPlayerOverride(channelId, null)
            _uiState.update { it.copy(lastInfo = "Для канала включен режим default", lastError = null) }
        }
    }

    fun setChannelOverride(playerType: PlayerType) {
        val channelId = _uiState.value.selectedChannelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Канал не выбран") }
            return
        }
        viewModelScope.launch {
            settingsRepository.setChannelPlayerOverride(channelId, playerType)
            _uiState.update { it.copy(lastInfo = "Override для канала: $playerType", lastError = null) }
        }
    }

    fun toggleSelectedFavorite() {
        val channelId = _uiState.value.selectedChannelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Канал не выбран") }
            return
        }
        viewModelScope.launch {
            val wasFavorite = _uiState.value.favoriteChannelIds.contains(channelId)
            favoritesRepository.toggleFavorite(channelId)
            _uiState.update {
                it.copy(
                    lastInfo = if (wasFavorite) "Канал удален из избранного" else "Канал добавлен в избранное",
                    lastError = null
                )
            }
        }
    }

    fun playSelected(context: Context) {
        playSelectedWith(playerType = _uiState.value.effectivePlayer, context = context)
    }

    fun playSelectedInternal() {
        playSelectedWith(playerType = PlayerType.INTERNAL, context = null)
    }

    fun playSelectedVlc(context: Context) {
        playSelectedWith(playerType = PlayerType.VLC, context = context)
    }

    fun playSelectedViaAce(context: Context) {
        playSelectedWith(
            playerType = _uiState.value.effectivePlayer,
            context = context,
            forceAceResolution = true
        )
    }

    fun checkEngineNow() {
        viewModelScope.launch {
            val endpoint = _uiState.value.engineEndpoint
            _uiState.update {
                it.copy(
                    lastInfo = "Проверка Ace Engine: $endpoint",
                    lastError = null
                )
            }
            safeLog(
                status = "engine_manual_check_start",
                message = "endpoint=$endpoint"
            )

            when (val connect = engineRepository.connect(endpoint)) {
                is AppResult.Success -> {
                    when (val status = engineRepository.refreshStatus()) {
                        is AppResult.Success -> {
                            _uiState.update {
                                it.copy(
                                    lastInfo = "Ace Engine доступен: peers=${status.data.peers}, speed=${status.data.speedKbps} kbps",
                                    lastError = null
                                )
                            }
                            safeLog(
                                status = "engine_manual_check_ok",
                                message = "endpoint=$endpoint, peers=${status.data.peers}, speed=${status.data.speedKbps}"
                            )
                        }
                        is AppResult.Error -> {
                            _uiState.update { it.copy(lastError = "Ace Engine status error: ${status.message}") }
                            safeLog(
                                status = "engine_manual_check_error",
                                message = "status request failed: ${status.message}"
                            )
                        }
                        AppResult.Loading -> Unit
                    }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(lastError = "Ace Engine connect error: ${connect.message}") }
                    safeLog(
                        status = "engine_manual_check_error",
                        message = "connect failed: ${connect.message}"
                    )
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun playSelectedWith(
        playerType: PlayerType,
        context: Context?,
        forceAceResolution: Boolean = false
    ) {
        val channel = _uiState.value.channels.firstOrNull { it.id == _uiState.value.selectedChannelId }
        if (channel == null) {
            _uiState.update { it.copy(lastError = "Выберите канал для воспроизведения") }
            logAsync(
                status = "player_play_request_error",
                message = "No selected channel"
            )
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isStartingPlayback = true, lastError = null) }
            safeLog(
                status = "player_play_request",
                message = "channelId=${channel.id}, playlistId=${channel.playlistId}, requestedPlayer=$playerType, forceAce=$forceAceResolution"
            )
            val resolvedChannel = when (val resolved = resolvePlayableChannel(channel, forceAceResolution)) {
                is AppResult.Success -> channel.copy(streamUrl = resolved.data)
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isStartingPlayback = false,
                            resolvedStreamUrl = null,
                            lastError = "Не удалось подготовить поток: ${resolved.message}"
                        )
                    }
                    safeLog(
                        status = "player_resolve_error",
                        message = "channelId=${channel.id}, reason=${resolved.message}",
                        playlistId = channel.playlistId
                    )
                    return@launch
                }
                AppResult.Loading -> return@launch
            }

            _uiState.update { it.copy(resolvedStreamUrl = resolvedChannel.streamUrl) }
            safeLog(
                status = "player_resolve_ok",
                message = "channelId=${channel.id}, streamKind=${describeStreamKind(channel.streamUrl)}",
                playlistId = channel.playlistId
            )

            when (playerType) {
                PlayerType.INTERNAL -> startInternalPlayback(resolvedChannel, infoMessage = "Запущен встроенный плеер")
                PlayerType.VLC -> {
                    if (context == null) {
                        _uiState.update {
                            it.copy(
                                isStartingPlayback = false,
                                lastError = "Для запуска VLC требуется контекст приложения"
                            )
                        }
                        safeLog(
                            status = "player_external_error",
                            message = "Context is null for VLC launch",
                            playlistId = channel.playlistId
                        )
                    } else {
                        launchExternalVlcOrFallback(context, resolvedChannel)
                    }
                }
            }
        }
    }

    fun stopInternalPlayback() {
        _uiState.update {
            it.copy(
                internalSession = null,
                isStartingPlayback = false,
                retryAttempt = 0,
                lastInfo = "Встроенное воспроизведение остановлено"
            )
        }
    }

    fun installVlc(context: Context) {
        runCatching {
            context.startActivity(vlcLauncher.createInstallIntent())
            _uiState.update { it.copy(lastInfo = "Открыт экран установки VLC", lastError = null) }
        }.onFailure {
            runCatching {
                context.startActivity(vlcLauncher.createInstallWebIntent())
                _uiState.update { it.copy(lastInfo = "Открыта страница VLC в браузере", lastError = null) }
            }.onFailure { nested ->
                _uiState.update { it.copy(lastError = "Не удалось открыть установку VLC: ${nested.message}") }
            }
        }
    }

    fun onInternalPlaybackReady() {
        val startupMs = (System.currentTimeMillis() - internalStartElapsedMs).coerceAtLeast(0L)
        viewModelScope.launch {
            diagnosticsRepository.addLog(
                status = "player_ready",
                message = "Internal ready, startupMs=$startupMs",
                playlistId = _uiState.value.selectedPlaylistId
            )
        }
        _uiState.update { it.copy(isStartingPlayback = false, lastError = null) }
    }

    fun onInternalPlaybackError(message: String, context: Context? = null) {
        val state = _uiState.value
        val session = state.internalSession ?: return
        val errorKind = classifyPlaybackError(message)
        if (state.retryAttempt >= MAX_AUTO_RETRIES) {
            viewModelScope.launch {
                diagnosticsRepository.addLog(
                    status = "player_error",
                    message = "Internal playback failed: kind=${errorKind.code}, msg=$message",
                    playlistId = state.selectedPlaylistId
                )
                diagnosticsRepository.addLog(
                    status = "player_error_detail",
                    message = "kind=${errorKind.code}, hint=${errorKind.hint}, channelId=${session.channelId}",
                    playlistId = state.selectedPlaylistId
                )
            }
            val channel = state.channels.firstOrNull { it.id == session.channelId }
            if (context != null && vlcLauncher.isVlcInstalled(context) && channel != null) {
                runCatching {
                    val vlcUrl = parseKodiStyleStream(state.resolvedStreamUrl ?: channel.streamUrl).streamUrl
                    val launchMode = openVlc(
                        context = context,
                        channel = channel,
                        streamUrl = vlcUrl,
                        launchReason = "auto_fallback"
                    )
                    viewModelScope.launch {
                        diagnosticsRepository.addLog(
                            status = "player_auto_vlc",
                            message = "Internal failed, switched to VLC($launchMode): channelId=${channel.id}",
                            playlistId = channel.playlistId
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isStartingPlayback = false,
                            internalSession = null,
                            retryAttempt = 0,
                            lastError = null,
                            lastInfo = if (launchMode == "direct") {
                                "Встроенный плеер не справился, открыто воспроизведение во VLC (fullscreen)"
                            } else {
                                "Встроенный плеер не справился, открыто воспроизведение во VLC (режим совместимости)"
                            }
                        )
                    }
                }.onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isStartingPlayback = false,
                            lastError = "Поток недоступен (${errorKind.code}): ${errorKind.hint}. Автопереход во VLC не удался: ${throwable.message}",
                            lastInfo = null
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isStartingPlayback = false,
                        lastError = "Поток недоступен (${errorKind.code}): ${errorKind.hint}",
                        lastInfo = null
                    )
                }
            }
            return
        }

        val nextAttempt = state.retryAttempt + 1
        viewModelScope.launch {
            diagnosticsRepository.addLog(
                status = "player_rebuffer",
                message = "Retry $nextAttempt/$MAX_AUTO_RETRIES due to: kind=${errorKind.code}, msg=$message",
                playlistId = state.selectedPlaylistId
            )
            _uiState.update {
                it.copy(
                    retryAttempt = nextAttempt,
                    isStartingPlayback = true,
                    lastError = "Ошибка потока (${errorKind.code}): ${errorKind.hint}. Повтор $nextAttempt/$MAX_AUTO_RETRIES"
                )
            }
            val delayMs = RETRY_DELAYS_MS.getOrElse(nextAttempt - 1) { 2_500L }
            delay(delayMs)
            val latestSession = _uiState.value.internalSession
            if (latestSession == null || latestSession.sessionId != session.sessionId) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    internalSession = latestSession.copy(sessionId = latestSession.sessionId + 1),
                    lastInfo = "Повторное подключение...",
                    lastError = null
                )
            }
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                _uiState.update { state ->
                    val preferred = state.selectedPlaylistId ?: requestedPlaylistId ?: playlists.firstOrNull()?.id
                    val selected = preferred?.takeIf { id -> playlists.any { it.id == id } }
                        ?: playlists.firstOrNull()?.id
                    state.copy(
                        playlists = playlists,
                        selectedPlaylistId = selected
                    )
                }

                val selected = _uiState.value.selectedPlaylistId
                if (selected != observedPlaylistId) {
                    observeChannels(selected)
                }
            }
        }
    }

    private fun observeChannels(playlistId: Long?) {
        channelsJob?.cancel()
        observedPlaylistId = playlistId
        if (playlistId == null) {
            _uiState.update {
                it.copy(
                    channels = emptyList(),
                    availableGroups = emptyList(),
                    selectedGroup = null,
                    selectedChannelId = null,
                    selectedStreamKind = "Канал не выбран",
                    selectedAceDescriptor = null,
                    selectedChannelAceCapable = false,
                    resolvedStreamUrl = null,
                    channelPlayerOverride = null,
                    channelEpgInfo = null,
                    epgStatus = "EPG: канал не выбран",
                    internalSession = null
                )
            }
            return
        }

        channelsJob = viewModelScope.launch {
            playlistRepository.observeChannels(playlistId).collect { allChannels ->
                val visibleChannels = allChannels.filterNot { it.isHidden }
                _uiState.update { state ->
                    val availableGroups = visibleChannels
                        .mapNotNull { channel -> channel.group?.trim()?.takeIf { it.isNotEmpty() } }
                        .distinct()
                        .sorted()
                    val selectedGroup = state.selectedGroup?.takeIf { group ->
                        availableGroups.contains(group)
                    }
                    val channelsByGroup = visibleChannels.filter { channel ->
                        selectedGroup == null || channel.group?.trim() == selectedGroup
                    }
                    val requestedSelected = requestedChannelId?.takeIf { id ->
                        channelsByGroup.any { it.id == id }
                    }
                    if (requestedSelected != null) {
                        requestedChannelId = null
                    }
                    val selected = requestedSelected
                        ?: state.selectedChannelId?.takeIf { id -> channelsByGroup.any { it.id == id } }
                        ?: channelsByGroup.firstOrNull()?.id
                    val selectedChannel = selected?.let { id -> channelsByGroup.firstOrNull { it.id == id } }
                    val aceDescriptor = selectedChannel?.let { detectAceDescriptor(it.streamUrl) }

                    val shouldStopSession = state.internalSession?.channelId != null &&
                        channelsByGroup.none { it.id == state.internalSession.channelId }

                    state.copy(
                        channels = visibleChannels,
                        availableGroups = availableGroups,
                        selectedGroup = selectedGroup,
                        selectedChannelId = selected,
                        selectedStreamKind = selectedChannel?.let { describeStreamKind(it.streamUrl) } ?: "Канал не выбран",
                        selectedAceDescriptor = aceDescriptor,
                        selectedChannelAceCapable = aceDescriptor != null,
                        resolvedStreamUrl = if (selected == state.selectedChannelId) state.resolvedStreamUrl else null,
                        channelPlayerOverride = if (selected == state.selectedChannelId) state.channelPlayerOverride else null,
                        channelEpgInfo = if (selected == state.selectedChannelId) state.channelEpgInfo else null,
                        epgStatus = if (selected == state.selectedChannelId) state.epgStatus else "EPG: загрузка...",
                        internalSession = if (shouldStopSession) null else state.internalSession
                    )
                }

                val selectedId = _uiState.value.selectedChannelId
                if (selectedId != null) {
                    observeChannelOverride(selectedId)
                    loadEpgForChannel(selectedId)
                } else {
                    overrideJob?.cancel()
                    epgJob?.cancel()
                    _uiState.update {
                        it.copy(
                            channelPlayerOverride = null,
                            effectivePlayer = it.defaultPlayer,
                            selectedAceDescriptor = null,
                            selectedChannelAceCapable = false
                        )
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.observeDefaultPlayer().collect { defaultPlayer ->
                _uiState.update { state ->
                    state.copy(
                        defaultPlayer = defaultPlayer,
                        effectivePlayer = state.channelPlayerOverride ?: defaultPlayer
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeBufferProfile().collect { profile ->
                _uiState.update { it.copy(bufferProfile = profile) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeManualBuffer().collect { manual ->
                _uiState.update { it.copy(manualBuffer = manual) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeEngineEndpoint().collect { endpoint ->
                _uiState.update { it.copy(engineEndpoint = endpoint) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeTorEnabled().collect { enabled ->
                _uiState.update { it.copy(torEnabled = enabled) }
            }
        }
    }

    private fun observeEngineStatus() {
        viewModelScope.launch {
            engineRepository.observeStatus().collect { status ->
                _uiState.update {
                    it.copy(
                        engineConnected = status.connected,
                        enginePeers = status.peers,
                        engineSpeedKbps = status.speedKbps,
                        engineMessage = status.message
                    )
                }
            }
        }
    }

    private fun observeChannelOverride(channelId: Long) {
        overrideJob?.cancel()
        overrideJob = viewModelScope.launch {
            settingsRepository.observeChannelPlayerOverride(channelId).collect { override ->
                _uiState.update { state ->
                    state.copy(
                        channelPlayerOverride = override,
                        effectivePlayer = override ?: state.defaultPlayer
                    )
                }
            }
        }
    }

    private fun loadEpgForChannel(channelId: Long) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            _uiState.update { it.copy(channelEpgInfo = null, epgStatus = "EPG: загрузка...") }
            when (val result = playlistRepository.getChannelEpgNowNext(channelId)) {
                is AppResult.Success -> {
                    val nowTitle = result.data.now?.title ?: "-"
                    val nextTitle = result.data.next?.title ?: "-"
                    _uiState.update {
                        it.copy(
                            channelEpgInfo = result.data,
                            epgStatus = "EPG: ${result.data.matchedBy} | сейчас: $nowTitle | далее: $nextTitle"
                        )
                    }
                    safeLog(
                        status = "player_epg_ok",
                        message = "channelId=$channelId, matchedBy=${result.data.matchedBy}, now=${result.data.now != null}, next=${result.data.next != null}"
                    )
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(channelEpgInfo = null, epgStatus = "EPG: ${result.message}") }
                    safeLog(
                        status = "player_epg_error",
                        message = "channelId=$channelId, reason=${result.message}"
                    )
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.observeFavorites().collect { channels ->
                _uiState.update { it.copy(favoriteChannelIds = channels.map { item -> item.id }.toSet()) }
            }
        }
    }

    private fun launchExternalVlcOrFallback(context: Context, channel: Channel) {
        if (!vlcLauncher.isVlcInstalled(context)) {
            _uiState.update {
                it.copy(
                    lastError = "VLC не установлен. Выполняется fallback на встроенный плеер.",
                    lastInfo = null
                )
            }
            logAsync(
                status = "player_vlc_missing",
                message = "VLC not installed, fallback to internal; channelId=${channel.id}",
                playlistId = channel.playlistId
            )
            startInternalPlayback(channel, infoMessage = "Запущен встроенный fallback")
            return
        }

        runCatching {
            val vlcUrl = parseKodiStyleStream(channel.streamUrl).streamUrl
            val launchMode = openVlc(
                context = context,
                channel = channel,
                streamUrl = vlcUrl,
                launchReason = "manual"
            )
            _uiState.update {
                it.copy(
                    internalSession = null,
                    isStartingPlayback = false,
                    retryAttempt = 0,
                    lastInfo = if (launchMode == "direct") {
                        "Канал открыт во внешнем VLC (fullscreen)"
                    } else {
                        "Канал открыт во внешнем VLC (режим совместимости)"
                    },
                    lastError = null
                )
            }
            addToHistory(channel)
            viewModelScope.launch {
                diagnosticsRepository.addLog(
                    status = "player_external",
                    message = "External VLC playback started($launchMode): channelId=${channel.id}",
                    playlistId = channel.playlistId
                )
            }
        }.onFailure { throwable ->
            _uiState.update {
                it.copy(
                    lastError = "Не удалось открыть VLC: ${throwable.message}. Запущен встроенный fallback."
                )
            }
            logAsync(
                status = "player_external_error",
                message = "VLC launch failed: ${throwable.message ?: throwable.javaClass.simpleName}, fallback to internal",
                playlistId = channel.playlistId
            )
            startInternalPlayback(channel, infoMessage = "Запущен встроенный fallback")
        }
    }

    private fun openVlc(
        context: Context,
        channel: Channel,
        streamUrl: String,
        launchReason: String
    ): String {
        return try {
            context.startActivity(
                vlcLauncher.createDirectPlayerIntent(
                    streamUrl = streamUrl,
                    title = channel.name
                )
            )
            logAsync(
                status = "player_external_launch_mode",
                message = "mode=direct, reason=$launchReason, channelId=${channel.id}",
                playlistId = channel.playlistId
            )
            "direct"
        } catch (directError: Throwable) {
            logAsync(
                status = "player_external_direct_error",
                message = "reason=$launchReason, channelId=${channel.id}, error=${directError.message ?: directError.javaClass.simpleName}",
                playlistId = channel.playlistId
            )
            context.startActivity(vlcLauncher.createIntent(streamUrl))
            logAsync(
                status = "player_external_launch_mode",
                message = "mode=compat, reason=$launchReason, channelId=${channel.id}",
                playlistId = channel.playlistId
            )
            "compat"
        }
    }

    private suspend fun resolvePlayableChannel(
        channel: Channel,
        forceAceResolution: Boolean = false
    ): AppResult<String> {
        val url = channel.streamUrl.trim()
        val aceDescriptor = detectAceDescriptor(url)
        if (!forceAceResolution && aceDescriptor == null) {
            return AppResult.Success(url)
        }
        if (forceAceResolution && aceDescriptor == null) {
            return AppResult.Error("Для этого канала не найден torrent/Ace descriptor")
        }

        val state = _uiState.value
        if (state.torEnabled) {
            _uiState.update {
                it.copy(
                    lastInfo = "Tor включен. Для полноценной маршрутизации убедитесь, что Tor/Orbot запущен на устройстве.",
                    lastError = null
                )
            }
        }

        when (val connectResult = engineRepository.connect(state.engineEndpoint)) {
            is AppResult.Success -> Unit
            is AppResult.Error -> return AppResult.Error("Ошибка подключения к Engine Stream: ${connectResult.message}")
            AppResult.Loading -> return AppResult.Loading
        }

        val descriptorToResolve = aceDescriptor ?: url
        return when (val resolveResult = engineRepository.resolveTorrentStream(descriptorToResolve)) {
            is AppResult.Success -> resolveResult
            is AppResult.Error -> AppResult.Error("Ошибка резолва torrent потока: ${resolveResult.message}")
            AppResult.Loading -> AppResult.Loading
        }
    }

    private fun startInternalPlayback(channel: Channel, infoMessage: String) {
        val state = _uiState.value
        val config = bufferConfigForProfile(
            profile = state.bufferProfile,
            manual = state.manualBuffer
        )
        val preparedStream = parseKodiStyleStream(channel.streamUrl)
        val nextSessionId = (state.internalSession?.sessionId ?: 0L) + 1L
        _uiState.update {
            it.copy(
                internalSession = InternalPlaybackSession(
                    sessionId = nextSessionId,
                    channelId = channel.id,
                    channelName = channel.name,
                    streamUrl = preparedStream.streamUrl,
                    requestHeaders = preparedStream.headers,
                    bufferConfig = config
                ),
                isStartingPlayback = true,
                retryAttempt = 0,
                lastInfo = if (preparedStream.headers.isEmpty()) {
                    infoMessage
                } else {
                    "$infoMessage (применены HTTP-заголовки)"
                },
                lastError = null
            )
        }
        internalStartElapsedMs = System.currentTimeMillis()
        addToHistory(channel)
        viewModelScope.launch {
            diagnosticsRepository.addLog(
                status = "player_start",
                message = "Internal playback start: channelId=${channel.id}",
                playlistId = channel.playlistId
            )
        }
    }

    private fun addToHistory(channel: Channel) {
        viewModelScope.launch {
            historyRepository.add(channelId = channel.id, channelName = channel.name)
        }
    }

    private fun describeStreamKind(raw: String): String {
        return if (detectAceDescriptor(raw) != null) {
            "Torrent/Ace поток (через Ace Engine)"
        } else {
            "IPTV поток (прямой URL)"
        }
    }

    private fun isTorrentDescriptor(raw: String): Boolean {
        val normalized = raw.trim().lowercase()
        return normalized.startsWith("magnet:") ||
            normalized.startsWith("acestream://") ||
            normalized.startsWith("ace://") ||
            normalized.startsWith("infohash:") ||
            normalized.endsWith(".torrent") ||
            HASH40_REGEX.matches(normalized)
    }

    private fun detectAceDescriptor(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        if (isTorrentDescriptor(trimmed)) {
            return normalizeAceDescriptor(trimmed)
        }
        val queryDescriptor = extractAceDescriptorFromUrl(trimmed)
        if (!queryDescriptor.isNullOrBlank()) {
            return normalizeAceDescriptor(queryDescriptor)
        }
        return null
    }

    private fun normalizeAceDescriptor(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed

        if (trimmed.startsWith("ace://", ignoreCase = true)) {
            val tail = trimmed.substringAfter("://").trimStart('/')
            return if (tail.isNotBlank()) "acestream://$tail" else trimmed
        }

        if (trimmed.startsWith("acestream://", ignoreCase = true)) {
            val tail = trimmed.substringAfter("://").trimStart('/')
            return if (tail.isNotBlank()) "acestream://$tail" else trimmed
        }

        if (trimmed.startsWith("infohash:", ignoreCase = true)) {
            val hash = trimmed.substringAfter(':').trim()
            return if (HASH40_REGEX.matches(hash)) "magnet:?xt=urn:btih:$hash" else trimmed
        }

        val lowered = trimmed.lowercase()
        if (lowered.startsWith("magnet:") || lowered.endsWith(".torrent")) {
            return trimmed
        }

        if (HASH40_REGEX.matches(trimmed)) {
            return "magnet:?xt=urn:btih:$trimmed"
        }

        return trimmed
    }

    private fun extractAceDescriptorFromUrl(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        val query = uri.rawQuery ?: return null
        val pairs = query.split('&')
        for (pair in pairs) {
            val key = pair.substringBefore('=').trim().lowercase()
            val valueEncoded = pair.substringAfter('=', "").trim()
            if (key.isBlank() || valueEncoded.isBlank()) continue
            if (key !in ACE_QUERY_KEYS) continue
            val value = runCatching {
                URLDecoder.decode(valueEncoded, StandardCharsets.UTF_8.toString()).trim()
            }.getOrDefault(valueEncoded)
            if (value.isBlank()) continue
            if (value.startsWith("acestream://", ignoreCase = true) ||
                value.startsWith("ace://", ignoreCase = true) ||
                value.startsWith("magnet:", ignoreCase = true) ||
                value.startsWith("infohash:", ignoreCase = true) ||
                value.endsWith(".torrent", ignoreCase = true)
            ) {
                return value
            }
            if (HASH40_REGEX.matches(value)) {
                return value
            }
        }
        return null
    }

    private fun parseKodiStyleStream(raw: String): PreparedStream {
        val source = raw.trim()
        val parts = source.split('|', limit = 2)
        val baseUrl = parts.firstOrNull().orEmpty().trim().ifBlank { source }
        if (parts.size < 2) {
            return PreparedStream(baseUrl, emptyMap())
        }

        val headers = parts[1]
            .split('&')
            .mapNotNull { token ->
                val kv = token.split('=', limit = 2)
                val key = kv.getOrNull(0)?.trim()?.lowercase().orEmpty()
                val value = kv.getOrNull(1)?.trim().orEmpty()
                if (key.isBlank() || value.isBlank()) return@mapNotNull null
                val normalizedKey = when (key) {
                    "ua", "user-agent", "user_agent" -> "User-Agent"
                    "referer", "referrer", "http-referrer", "http_referer" -> "Referer"
                    "origin" -> "Origin"
                    else -> key
                }
                normalizedKey to value
            }
            .toMap()

        return PreparedStream(baseUrl, headers)
    }

    private suspend fun probeStreamUrl(
        rawUrl: String,
        headers: Map<String, String>
    ): AppResult<String> = withContext(Dispatchers.IO) {
        val normalized = rawUrl.trim()
        if (normalized.isBlank()) {
            return@withContext AppResult.Error("Пустой URL для теста")
        }
        if (detectAceDescriptor(normalized) != null) {
            return@withContext AppResult.Error("Torrent/Ace URL: проверка выполняется через Engine Stream, а не прямым HTTP")
        }

        val parsed = runCatching { URL(normalized) }.getOrElse { throwable ->
            return@withContext AppResult.Error("Некорректный URL: ${throwable.message ?: throwable.javaClass.simpleName}")
        }
        val scheme = parsed.protocol?.lowercase().orEmpty()
        if (scheme != "http" && scheme != "https") {
            return@withContext AppResult.Error("Неподдерживаемая схема: $scheme (ожидается http/https)")
        }

        var connection: HttpURLConnection? = null
        try {
            connection = (parsed.openConnection() as? HttpURLConnection)
                ?: return@withContext AppResult.Error("Не удалось открыть HTTP-соединение")

            connection.instanceFollowRedirects = true
            connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            connection.readTimeout = PROBE_READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Range", "bytes=0-2048")
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) {
                    connection.setRequestProperty(name, value)
                }
            }
            connection.connect()

            val code = connection.responseCode
            val finalUrl = connection.url?.toString().orEmpty().ifBlank { normalized }
            val contentType = connection.contentType.orEmpty().ifBlank { "-" }
            val contentLength = connection.getHeaderField("Content-Length").orEmpty().ifBlank { "-" }
            val sampleBytes = readProbeSampleBytes(connection)
            val family = code / 100

            val summary = buildString {
                append("HTTP ")
                append(code)
                append(", family=")
                append(family)
                append(", type=")
                append(contentType)
                append(", len=")
                append(contentLength)
                append(", sample=")
                append(sampleBytes)
                append("b")
                append(", finalHost=")
                append(runCatching { URL(finalUrl).host }.getOrDefault("-"))
            }

            if (code in 200..299) {
                AppResult.Success(summary)
            } else {
                AppResult.Error("HTTP ошибка: $summary")
            }
        } catch (throwable: UnknownHostException) {
            AppResult.Error("DNS ошибка: ${throwable.message ?: "host not resolved"}")
        } catch (throwable: SocketTimeoutException) {
            AppResult.Error("Таймаут сети: ${throwable.message ?: "timeout"}")
        } catch (throwable: ConnectException) {
            AppResult.Error("Ошибка подключения: ${throwable.message ?: "connect failed"}")
        } catch (throwable: MalformedURLException) {
            AppResult.Error("Некорректный URL: ${throwable.message ?: "malformed url"}")
        } catch (throwable: Throwable) {
            AppResult.Error("Ошибка проверки потока: ${throwable.message ?: throwable.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun readProbeSampleBytes(connection: HttpURLConnection): Int {
        val stream = runCatching { connection.inputStream }.getOrElse { connection.errorStream } ?: return 0
        return stream.use { input ->
            val buffer = ByteArray(1024)
            runCatching { input.read(buffer) }.getOrDefault(-1).coerceAtLeast(0)
        }
    }

    private fun classifyPlaybackError(message: String): PlaybackErrorKind {
        val lowered = message.lowercase()
        return when {
            lowered.contains("unknownhostexception") || lowered.contains("unable to resolve host") ->
                PlaybackErrorKind(code = "dns", hint = "DNS не может разрешить хост. Проверьте интернет/DNS/proxy.")
            lowered.contains("timeout") || lowered.contains("timed out") ->
                PlaybackErrorKind(code = "timeout", hint = "Сеть не отвечает вовремя. Увеличьте буфер или смените источник.")
            lowered.contains("http") && lowered.contains("403") ->
                PlaybackErrorKind(code = "http_403", hint = "Доступ к потоку запрещен (403). Нужны корректные URL/заголовки.")
            lowered.contains("http") && lowered.contains("404") ->
                PlaybackErrorKind(code = "http_404", hint = "Поток не найден (404). Ссылка устарела или удалена.")
            lowered.contains("decoder") || lowered.contains("mediacodec") || lowered.contains("codec") ->
                PlaybackErrorKind(code = "codec", hint = "Проблема декодера/кодека. Попробуйте VLC или другой поток.")
            lowered.contains("behind_live_window") ->
                PlaybackErrorKind(code = "live_window", hint = "Сбой live-окна HLS. Нужна переподготовка потока.")
            lowered.contains("source error") ->
                PlaybackErrorKind(code = "source", hint = "Источник потока недоступен или отдает невалидные данные.")
            else ->
                PlaybackErrorKind(code = "unknown", hint = "Неизвестная ошибка воспроизведения. Проверьте лог и URL потока.")
        }
    }

    private data class PreparedStream(
        val streamUrl: String,
        val headers: Map<String, String>
    )

    private data class PlaybackErrorKind(
        val code: String,
        val hint: String
    )

    private companion object {
        const val MAX_AUTO_RETRIES = 3
        val RETRY_DELAYS_MS = listOf(800L, 1_600L, 2_400L)
        const val MAX_LOG_MESSAGE = 700
        const val MAX_PROBE_URL_LOG = 220
        const val PROBE_CONNECT_TIMEOUT_MS = 8_000
        const val PROBE_READ_TIMEOUT_MS = 12_000
        val HASH40_REGEX = Regex("^[a-fA-F0-9]{40}$")
        val ACE_QUERY_KEYS = setOf("id", "content_id", "infohash", "hash", "url")
    }

    private fun logAsync(status: String, message: String, playlistId: Long? = _uiState.value.selectedPlaylistId) {
        viewModelScope.launch {
            safeLog(status = status, message = message, playlistId = playlistId)
        }
    }

    private suspend fun safeLog(status: String, message: String, playlistId: Long? = _uiState.value.selectedPlaylistId) {
        runCatching {
            diagnosticsRepository.addLog(
                status = status,
                message = message.take(MAX_LOG_MESSAGE),
                playlistId = playlistId
            )
        }
    }
}

