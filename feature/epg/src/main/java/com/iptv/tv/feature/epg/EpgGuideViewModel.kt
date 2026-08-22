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
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val EPG_INITIAL_VISIBLE_ROWS = 40
private const val EPG_VISIBLE_ROWS_STEP = 40
private const val EPG_NOW_WINDOW_MS = 3 * 60 * 60 * 1000L

enum class EpgWindowPreset {
    NOW,
    TODAY,
    TOMORROW
}

data class EpgChannelRow(
    val channel: Channel,
    val programs: List<EpgProgram>
)

data class SelectedEpgProgram(
    val row: EpgChannelRow,
    val program: EpgProgram
)

data class EpgGuideUiState(
    val title: String = "Телепрограмма",
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: Long? = null,
    val selectedPreset: EpgWindowPreset = EpgWindowPreset.NOW,
    val query: String = "",
    val rows: List<EpgChannelRow> = emptyList(),
    val totalRowCount: Int = 0,
    val visibleRowLimit: Int = EPG_INITIAL_VISIBLE_ROWS,
    val isLoading: Boolean = false,
    val isSchedulingRecording: Boolean = false,
    val selectedProgram: SelectedEpgProgram? = null,
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
    private var loadedRows: List<EpgChannelRow> = emptyList()

    init {
        observePlaylists()
    }

    fun selectPlaylist(playlistId: Long) {
        _uiState.update {
            it.copy(
                selectedPlaylistId = playlistId,
                rows = emptyList(),
                totalRowCount = 0,
                visibleRowLimit = EPG_INITIAL_VISIBLE_ROWS,
                error = null,
                status = "Загрузка каналов..."
            )
        }
        loadedRows = emptyList()
        observeChannels(playlistId)
    }

    fun selectPreset(preset: EpgWindowPreset) {
        _uiState.update { it.copy(selectedPreset = preset, visibleRowLimit = EPG_INITIAL_VISIBLE_ROWS) }
        loadGuide()
    }

    fun updateQuery(value: String) {
        _uiState.update { it.copy(query = value) }
    }

    fun applySearch() {
        _uiState.update { it.copy(visibleRowLimit = EPG_INITIAL_VISIBLE_ROWS) }
        loadGuide()
    }

    fun refresh() {
        _uiState.update { it.copy(visibleRowLimit = EPG_INITIAL_VISIBLE_ROWS) }
        loadGuide()
    }

    fun showMoreRows() {
        _uiState.update { state ->
            val nextLimit = nextEpgVisibleRowLimit(
                currentVisibleRows = state.visibleRowLimit,
                totalRows = loadedRows.size
            )
            state.copy(
                visibleRowLimit = nextLimit,
                rows = loadedRows.take(nextLimit),
                status = buildEpgStatus(loadedRows.size, nextLimit)
            )
        }
    }

    fun selectProgram(row: EpgChannelRow, program: EpgProgram) {
        _uiState.update { it.copy(selectedProgram = SelectedEpgProgram(row, program)) }
    }

    fun clearSelectedProgram() {
        _uiState.update { it.copy(selectedProgram = null) }
    }

    fun scheduleSelectedProgram() {
        val selected = _uiState.value.selectedProgram ?: return
        scheduleRecording(selected.row, selected.program)
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
            val (startMs, endMs) = epgWindowForPreset(_uiState.value.selectedPreset)
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
                    loadedRows = rows
                    val visibleLimit = _uiState.value.visibleRowLimit.coerceAtMost(rows.size)
                    _uiState.update {
                        it.copy(
                            rows = rows.take(visibleLimit),
                            totalRowCount = rows.size,
                            visibleRowLimit = visibleLimit,
                            isLoading = false,
                            status = buildEpgStatus(rows.size, visibleLimit),
                            error = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            rows = emptyList(),
                            totalRowCount = 0,
                            visibleRowLimit = EPG_INITIAL_VISIBLE_ROWS,
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
}

internal fun epgWindowForPreset(
    preset: EpgWindowPreset,
    nowMs: Long = System.currentTimeMillis(),
    timeZone: TimeZone = TimeZone.getDefault()
): Pair<Long, Long> {
    if (preset == EpgWindowPreset.NOW) {
        return nowMs to nowMs + EPG_NOW_WINDOW_MS
    }

    val dayOffset = if (preset == EpgWindowPreset.TOMORROW) 1 else 0
    val startMs = localCivilDayBoundary(
        nowMs = nowMs,
        dayOffset = dayOffset,
        timeZone = timeZone
    )
    val endMs = localCivilDayBoundary(
        nowMs = nowMs,
        dayOffset = dayOffset + 1,
        timeZone = timeZone
    )
    return startMs to endMs
}

private fun localCivilDayBoundary(
    nowMs: Long,
    dayOffset: Int,
    timeZone: TimeZone
): Long {
    return Calendar.getInstance(timeZone).apply {
        timeInMillis = nowMs
        if (dayOffset != 0) add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

internal fun nextEpgVisibleRowLimit(
    currentVisibleRows: Int,
    totalRows: Int
): Int {
    return (currentVisibleRows + EPG_VISIBLE_ROWS_STEP).coerceAtMost(totalRows)
}

internal fun buildEpgStatus(totalRows: Int, visibleRows: Int): String {
    return if (totalRows <= visibleRows) {
        "Найдено каналов с EPG: $totalRows"
    } else {
        "Показано каналов с EPG: $visibleRows из $totalRows"
    }
}
