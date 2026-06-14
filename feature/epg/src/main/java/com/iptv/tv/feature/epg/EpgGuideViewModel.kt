package com.iptv.tv.feature.epg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.RecordingRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.RecordingRepeatMode
import com.iptv.tv.core.model.RecordingSchedule
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Calendar
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EpgWindowPreset {
    NOW,
    TODAY,
    TOMORROW
}

data class EpgChannelRow(
    val channel: Channel,
    val programs: List<EpgProgram>
)

data class EpgGuideUiState(
    val title: String = "Телепрограмма",
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: Long? = null,
    val selectedPreset: EpgWindowPreset = EpgWindowPreset.NOW,
    val query: String = "",
    val rows: List<EpgChannelRow> = emptyList(),
    val isLoading: Boolean = false,
    val isSchedulingRecording: Boolean = false,
    val status: String = "Выберите плейлист с EPG",
    val error: String? = null,
    val windowStartMs: Long = 0L,
    val windowEndMs: Long = 0L
)

@HiltViewModel
class EpgGuideViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val recordingRepository: RecordingRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(EpgGuideUiState())
    val uiState: StateFlow<EpgGuideUiState> = _uiState.asStateFlow()

    private var channelsJob: Job? = null
    private var loadJob: Job? = null
    private var channelsByPlaylist: List<Channel> = emptyList()

    init {
        observePlaylists()
    }

    fun selectPlaylist(playlistId: Long) {
        _uiState.update {
            it.copy(
                selectedPlaylistId = playlistId,
                rows = emptyList(),
                error = null,
                status = "Загрузка каналов..."
            )
        }
        observeChannels(playlistId)
    }

    fun selectPreset(preset: EpgWindowPreset) {
        _uiState.update { it.copy(selectedPreset = preset) }
        loadGuide()
    }

    fun updateQuery(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun applySearch() {
        loadGuide()
    }

    fun refresh() {
        loadGuide()
    }

    fun scheduleRecording(row: EpgChannelRow, program: EpgProgram) {
        val now = System.currentTimeMillis()
        if (program.endEpochMs <= now) {
            _uiState.update { it.copy(error = "Передача уже закончилась: ${program.title}") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isSchedulingRecording = true, error = null) }
            val startAt = program.startEpochMs.coerceAtLeast(now)
            when (
                val result = recordingRepository.scheduleRecording(
                    RecordingSchedule(
                        id = 0,
                        channelId = row.channel.id,
                        channelName = row.channel.name,
                        programTitle = program.title,
                        startAt = startAt,
                        endAt = program.endEpochMs,
                        repeatMode = RecordingRepeatMode.ONCE,
                        enabled = true,
                        createdAt = now
                    )
                )
            ) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSchedulingRecording = false,
                            status = "Запись запланирована: ${program.title}",
                            error = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isSchedulingRecording = false, error = result.message)
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun observePlaylists() {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                val currentId = _uiState.value.selectedPlaylistId
                    ?.takeIf { id -> playlists.any { it.id == id } }
                    ?: playlists.firstOrNull { !it.epgSourceUrl.isNullOrBlank() }?.id
                    ?: playlists.firstOrNull()?.id

                _uiState.update {
                    it.copy(
                        playlists = playlists,
                        selectedPlaylistId = currentId,
                        status = if (playlists.isEmpty()) "Плейлисты пока не импортированы" else it.status
                    )
                }

                if (currentId != null && currentId != channelsByPlaylist.firstOrNull()?.playlistId) {
                    observeChannels(currentId)
                }
            }
        }
    }

    private fun observeChannels(playlistId: Long) {
        channelsJob?.cancel()
        channelsJob = viewModelScope.launch {
            playlistRepository.observeChannels(playlistId).collect { channels ->
                channelsByPlaylist = channels.filter { !it.isHidden }
                loadGuide()
            }
        }
    }

    private fun loadGuide() {
        val playlistId = _uiState.value.selectedPlaylistId ?: return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val (startMs, endMs) = windowForPreset(_uiState.value.selectedPreset)
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    status = "Загрузка EPG...",
                    windowStartMs = startMs,
                    windowEndMs = endMs
                )
            }

            when (
                val result = playlistRepository.getPlaylistEpgWindow(
                    playlistId = playlistId,
                    startEpochMs = startMs,
                    endEpochMs = endMs,
                    query = _uiState.value.query
                )
            ) {
                is AppResult.Success -> {
                    val rows = channelsByPlaylist
                        .mapNotNull { channel ->
                            val programs = result.data[channel.id].orEmpty()
                            if (programs.isEmpty()) null else EpgChannelRow(channel, programs)
                        }
                    _uiState.update {
                        it.copy(
                            rows = rows,
                            isLoading = false,
                            status = "Найдено каналов с EPG: ${rows.size}",
                            error = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            rows = emptyList(),
                            isLoading = false,
                            status = "EPG недоступен",
                            error = result.message
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun windowForPreset(preset: EpgWindowPreset): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance()
        return when (preset) {
            EpgWindowPreset.NOW -> now to now + 3 * 60 * 60 * 1000L
            EpgWindowPreset.TODAY -> {
                calendar.timeInMillis = now
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis
                start to start + 24 * 60 * 60 * 1000L
            }
            EpgWindowPreset.TOMORROW -> {
                calendar.timeInMillis = now
                calendar.add(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis
                start to start + 24 * 60 * 60 * 1000L
            }
        }
    }
}
