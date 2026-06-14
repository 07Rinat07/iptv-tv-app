package com.iptv.tv.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DownloadRepository
import com.iptv.tv.core.domain.repository.RecordingRepository
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.DownloadTask
import com.iptv.tv.core.model.RecordingRepeatMode
import com.iptv.tv.core.model.RecordingSchedule
import com.iptv.tv.core.model.RecordingStatus
import com.iptv.tv.core.model.RecordingTask
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DownloadsUiState(
    val title: String = "Загрузки",
    val description: String = "Очередь torrent/stream задач: прогресс, пауза, возобновление, удаление",
    val sourceInput: String = "",
    val maxConcurrentInput: String = "1",
    val recordingChannelIdInput: String = "",
    val recordingTitleInput: String = "",
    val recordingDelayMinutesInput: String = "0",
    val recordingDurationMinutesInput: String = "120",
    val tasks: List<DownloadTask> = emptyList(),
    val recordings: List<RecordingTask> = emptyList(),
    val schedules: List<RecordingSchedule> = emptyList(),
    val isBusy: Boolean = false,
    val isProcessingRecordings: Boolean = false,
    val lastInfo: String? = null,
    val lastError: String? = null
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val recordingRepository: RecordingRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepository.observeDownloads(limit = 200).collect { tasks ->
                _uiState.update { it.copy(tasks = tasks) }
            }
        }
        viewModelScope.launch {
            recordingRepository.observeRecordings(limit = 100).collect { recordings ->
                _uiState.update { it.copy(recordings = recordings) }
            }
        }
        viewModelScope.launch {
            recordingRepository.observeSchedules().collect { schedules ->
                _uiState.update { it.copy(schedules = schedules) }
            }
        }
    }

    fun updateSourceInput(value: String) {
        _uiState.update { it.copy(sourceInput = value, lastError = null) }
    }

    fun updateMaxConcurrentInput(value: String) {
        _uiState.update { it.copy(maxConcurrentInput = value, lastError = null) }
    }

    fun updateRecordingChannelId(value: String) {
        _uiState.update { it.copy(recordingChannelIdInput = value.filter { ch -> ch.isDigit() }, lastError = null) }
    }

    fun updateRecordingTitle(value: String) {
        _uiState.update { it.copy(recordingTitleInput = value, lastError = null) }
    }

    fun updateRecordingDelayMinutes(value: String) {
        _uiState.update { it.copy(recordingDelayMinutesInput = value.filter { ch -> ch.isDigit() }, lastError = null) }
    }

    fun updateRecordingDurationMinutes(value: String) {
        _uiState.update { it.copy(recordingDurationMinutesInput = value.filter { ch -> ch.isDigit() }, lastError = null) }
    }

    fun enqueue() {
        val source = _uiState.value.sourceInput.trim()
        if (source.isBlank()) {
            _uiState.update { it.copy(lastError = "Введите источник torrent/stream") }
            return
        }
        viewModelScope.launch {
            when (val result = downloadRepository.enqueue(source)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            sourceInput = "",
                            lastInfo = "Задача добавлена: id=${result.data.id}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun processQueueNow() {
        val maxConcurrent = _uiState.value.maxConcurrentInput.toIntOrNull()
        if (maxConcurrent == null) {
            _uiState.update { it.copy(lastError = "Введите корректное количество параллельных задач") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, lastError = null) }
            when (val result = downloadRepository.tickQueue(maxConcurrent = maxConcurrent)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            lastInfo = "Очередь обработана: ${result.data} задач",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(isBusy = false, lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun pause(taskId: Long) {
        updateTaskState(taskId) { downloadRepository.pause(taskId) }
    }

    fun resume(taskId: Long) {
        updateTaskState(taskId) { downloadRepository.resume(taskId) }
    }

    fun cancel(taskId: Long) {
        updateTaskState(taskId) { downloadRepository.cancel(taskId) }
    }

    fun remove(taskId: Long) {
        updateTaskState(taskId) { downloadRepository.remove(taskId) }
    }

    fun recordNow() {
        val channelId = _uiState.value.recordingChannelIdInput.toLongOrNull()
        if (channelId == null || channelId <= 0) {
            _uiState.update { it.copy(lastError = "Введите Channel ID для записи") }
            return
        }
        viewModelScope.launch {
            when (val result = recordingRepository.startRecordingNow(channelId, _uiState.value.recordingTitleInput)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            lastInfo = "Запись добавлена в очередь: id=${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun scheduleRecording() {
        val state = _uiState.value
        val channelId = state.recordingChannelIdInput.toLongOrNull()
        val delayMinutes = state.recordingDelayMinutesInput.toLongOrNull()
        val durationMinutes = state.recordingDurationMinutesInput.toLongOrNull()
        if (channelId == null || channelId <= 0) {
            _uiState.update { it.copy(lastError = "Введите Channel ID для расписания записи") }
            return
        }
        if (delayMinutes == null || durationMinutes == null || durationMinutes <= 0) {
            _uiState.update { it.copy(lastError = "Введите задержку и длительность записи в минутах") }
            return
        }
        val now = System.currentTimeMillis()
        val startAt = now + delayMinutes * 60_000L
        val endAt = startAt + durationMinutes * 60_000L
        viewModelScope.launch {
            when (
                val result = recordingRepository.scheduleRecording(
                    RecordingSchedule(
                        id = 0,
                        channelId = channelId,
                        channelName = "",
                        programTitle = state.recordingTitleInput.trim().ifBlank { null },
                        startAt = startAt,
                        endAt = endAt,
                        repeatMode = RecordingRepeatMode.ONCE,
                        enabled = true,
                        createdAt = now
                    )
                )
            ) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            lastInfo = "Расписание записи создано, recordingId=${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun cancelRecording(recordingId: Long) {
        viewModelScope.launch {
            when (val result = recordingRepository.cancelRecording(recordingId)) {
                is AppResult.Success -> _uiState.update { it.copy(lastInfo = "Запись отменена", lastError = null) }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun deleteSchedule(scheduleId: Long) {
        viewModelScope.launch {
            when (val result = recordingRepository.deleteSchedule(scheduleId)) {
                is AppResult.Success -> _uiState.update { it.copy(lastInfo = "Расписание удалено", lastError = null) }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun processRecordingsNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingRecordings = true, lastError = null) }
            when (val result = recordingRepository.processDueRecordings(maxConcurrent = 1)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isProcessingRecordings = false,
                            lastInfo = "Очередь записей обработана: ${result.data} завершено",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isProcessingRecordings = false, lastError = result.message)
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun deleteRecording(recordingId: Long) {
        viewModelScope.launch {
            when (val result = recordingRepository.deleteRecording(recordingId, deleteFile = true)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            lastInfo = "Запись удалена, файлов/строк удалено: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun cleanupRecordings(days: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessingRecordings = true, lastError = null) }
            when (val result = recordingRepository.cleanupOldRecordings(days)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isProcessingRecordings = false,
                            lastInfo = "Автоочистка записей: удалено ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isProcessingRecordings = false, lastError = result.message)
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun canPause(status: DownloadStatus): Boolean {
        return status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING
    }

    fun canResume(status: DownloadStatus): Boolean {
        return status == DownloadStatus.PAUSED
    }

    fun canCancel(status: DownloadStatus): Boolean {
        return status == DownloadStatus.QUEUED ||
            status == DownloadStatus.RUNNING ||
            status == DownloadStatus.PAUSED
    }

    fun canCancelRecording(status: RecordingStatus): Boolean {
        return status == RecordingStatus.SCHEDULED || status == RecordingStatus.RECORDING
    }

    fun canDeleteRecording(status: RecordingStatus): Boolean {
        return status != RecordingStatus.RECORDING
    }

    private fun updateTaskState(
        taskId: Long,
        action: suspend () -> AppResult<Unit>
    ) {
        viewModelScope.launch {
            when (val result = action()) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(lastInfo = "Задача $taskId обновлена", lastError = null) }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }
}
