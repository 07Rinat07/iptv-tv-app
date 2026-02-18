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
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.player.BufferConfig
import com.iptv.tv.core.player.ExternalVlcLauncher
import com.iptv.tv.core.player.bufferConfigForProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

const val PLAYER_PLAYLIST_ID_ARG = "playlistId"

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
    val channelQuery: String = "",
    val selectedChannelId: Long? = null,
    val favoriteChannelIds: Set<Long> = emptySet(),
    val defaultPlayer: PlayerType = PlayerType.INTERNAL,
    val channelPlayerOverride: PlayerType? = null,
    val effectivePlayer: PlayerType = PlayerType.INTERNAL,
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
    val internalSession: InternalPlaybackSession? = null,
    val playerVideoScale: PlayerVideoScale = PlayerVideoScale.FIT,
    val internalPlayerExpanded: Boolean = false,
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

    private var observedPlaylistId: Long? = null
    private var channelsJob: Job? = null
    private var overrideJob: Job? = null
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
                selectedChannelId = null,
                channelPlayerOverride = null,
                selectedStreamKind = "Канал не выбран",
                resolvedStreamUrl = null,
                internalSession = null,
                lastError = null,
                lastInfo = null
            )
        }
        observeChannels(playlistId)
    }

    fun selectChannel(channelId: Long) {
        val channel = _uiState.value.channels.firstOrNull { it.id == channelId }
        _uiState.update { state ->
            state.copy(
                selectedChannelId = channelId,
                selectedStreamKind = channel?.let { describeStreamKind(it.streamUrl) } ?: "Канал не выбран",
                resolvedStreamUrl = null,
                lastError = null,
                lastInfo = null
            )
        }
        observeChannelOverride(channelId)
    }

    fun updateChannelQuery(value: String) {
        _uiState.update { it.copy(channelQuery = value) }
    }

    fun toggleInternalPlayerSize() {
        _uiState.update { it.copy(internalPlayerExpanded = !it.internalPlayerExpanded) }
    }

    fun cycleVideoScale() {
        _uiState.update { state ->
            val next = when (state.playerVideoScale) {
                PlayerVideoScale.FIT -> PlayerVideoScale.FILL
                PlayerVideoScale.FILL -> PlayerVideoScale.ZOOM
                PlayerVideoScale.ZOOM -> PlayerVideoScale.FIT
            }
            state.copy(playerVideoScale = next)
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

    fun checkEngineNow() {
        viewModelScope.launch {
            val endpoint = _uiState.value.engineEndpoint
            _uiState.update {
                it.copy(
                    lastInfo = "Проверка Ace Engine: $endpoint",
                    lastError = null
                )
            }

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
                        }
                        is AppResult.Error -> {
                            _uiState.update { it.copy(lastError = "Ace Engine status error: ${status.message}") }
                        }
                        AppResult.Loading -> Unit
                    }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(lastError = "Ace Engine connect error: ${connect.message}") }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun playSelectedWith(playerType: PlayerType, context: Context?) {
        val channel = _uiState.value.channels.firstOrNull { it.id == _uiState.value.selectedChannelId }
        if (channel == null) {
            _uiState.update { it.copy(lastError = "Выберите канал для воспроизведения") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isStartingPlayback = true, lastError = null) }
            val resolvedChannel = when (val resolved = resolvePlayableChannel(channel)) {
                is AppResult.Success -> channel.copy(streamUrl = resolved.data)
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isStartingPlayback = false,
                            resolvedStreamUrl = null,
                            lastError = "Не удалось подготовить поток: ${resolved.message}"
                        )
                    }
                    return@launch
                }
                AppResult.Loading -> return@launch
            }

            _uiState.update { it.copy(resolvedStreamUrl = resolvedChannel.streamUrl) }

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
        if (state.retryAttempt >= MAX_AUTO_RETRIES) {
            viewModelScope.launch {
                diagnosticsRepository.addLog(
                    status = "player_error",
                    message = "Internal playback failed: $message",
                    playlistId = state.selectedPlaylistId
                )
            }
            val channel = state.channels.firstOrNull { it.id == session.channelId }
            if (context != null && vlcLauncher.isVlcInstalled(context) && channel != null) {
                runCatching {
                    val vlcUrl = parseKodiStyleStream(state.resolvedStreamUrl ?: channel.streamUrl).streamUrl
                    context.startActivity(vlcLauncher.createIntent(vlcUrl))
                    viewModelScope.launch {
                        diagnosticsRepository.addLog(
                            status = "player_auto_vlc",
                            message = "Internal failed, switched to VLC: channelId=${channel.id}",
                            playlistId = channel.playlistId
                        )
                    }
                    _uiState.update {
                        it.copy(
                            isStartingPlayback = false,
                            internalSession = null,
                            retryAttempt = 0,
                            lastError = null,
                            lastInfo = "Встроенный плеер не справился, открыто воспроизведение во VLC"
                        )
                    }
                }.onFailure { throwable ->
                    _uiState.update {
                        it.copy(
                            isStartingPlayback = false,
                            lastError = "Поток недоступен: $message. Автопереход во VLC не удался: ${throwable.message}",
                            lastInfo = null
                        )
                    }
                }
            } else {
                _uiState.update {
                    it.copy(
                        isStartingPlayback = false,
                        lastError = "Поток недоступен: $message",
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
                message = "Retry $nextAttempt/$MAX_AUTO_RETRIES due to: $message",
                playlistId = state.selectedPlaylistId
            )
            _uiState.update {
                it.copy(
                    retryAttempt = nextAttempt,
                    isStartingPlayback = true,
                    lastError = "Ошибка потока: $message. Повтор $nextAttempt/$MAX_AUTO_RETRIES"
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
                    selectedChannelId = null,
                    selectedStreamKind = "Канал не выбран",
                    resolvedStreamUrl = null,
                    channelPlayerOverride = null,
                    internalSession = null
                )
            }
            return
        }

        channelsJob = viewModelScope.launch {
            playlistRepository.observeChannels(playlistId).collect { allChannels ->
                val visibleChannels = allChannels.filterNot { it.isHidden }
                _uiState.update { state ->
                    val selected = state.selectedChannelId?.takeIf { id -> visibleChannels.any { it.id == id } }
                        ?: visibleChannels.firstOrNull()?.id
                    val selectedChannel = selected?.let { id -> visibleChannels.firstOrNull { it.id == id } }

                    val shouldStopSession = state.internalSession?.channelId != null &&
                        visibleChannels.none { it.id == state.internalSession.channelId }

                    state.copy(
                        channels = visibleChannels,
                        selectedChannelId = selected,
                        selectedStreamKind = selectedChannel?.let { describeStreamKind(it.streamUrl) } ?: "Канал не выбран",
                        resolvedStreamUrl = if (selected == state.selectedChannelId) state.resolvedStreamUrl else null,
                        channelPlayerOverride = if (selected == state.selectedChannelId) state.channelPlayerOverride else null,
                        internalSession = if (shouldStopSession) null else state.internalSession
                    )
                }

                _uiState.value.selectedChannelId?.let { observeChannelOverride(it) }
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
            startInternalPlayback(channel, infoMessage = "Запущен встроенный fallback")
            return
        }

        runCatching {
            val vlcUrl = parseKodiStyleStream(channel.streamUrl).streamUrl
            context.startActivity(vlcLauncher.createIntent(vlcUrl))
            _uiState.update {
                it.copy(
                    internalSession = null,
                    isStartingPlayback = false,
                    retryAttempt = 0,
                    lastInfo = "Канал открыт во внешнем VLC",
                    lastError = null
                )
            }
            addToHistory(channel)
            viewModelScope.launch {
                diagnosticsRepository.addLog(
                    status = "player_external",
                    message = "External VLC playback started: channelId=${channel.id}",
                    playlistId = channel.playlistId
                )
            }
        }.onFailure { throwable ->
            _uiState.update {
                it.copy(
                    lastError = "Не удалось открыть VLC: ${throwable.message}. Запущен встроенный fallback."
                )
            }
            startInternalPlayback(channel, infoMessage = "Запущен встроенный fallback")
        }
    }

    private suspend fun resolvePlayableChannel(channel: Channel): AppResult<String> {
        val url = channel.streamUrl.trim()
        if (!isTorrentDescriptor(url)) {
            return AppResult.Success(url)
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

        return when (val resolveResult = engineRepository.resolveTorrentStream(url)) {
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
        return if (isTorrentDescriptor(raw)) {
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
            normalized.endsWith(".torrent")
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

    private data class PreparedStream(
        val streamUrl: String,
        val headers: Map<String, String>
    )

    private companion object {
        const val MAX_AUTO_RETRIES = 3
        val RETRY_DELAYS_MS = listOf(800L, 1_600L, 2_400L)
    }
}

