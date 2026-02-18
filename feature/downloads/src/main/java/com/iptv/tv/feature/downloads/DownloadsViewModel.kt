package com.iptv.tv.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DownloadRepository
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.DownloadTask
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
    val tasks: List<DownloadTask> = emptyList(),
    val isBusy: Boolean = false,
    val lastInfo: String? = null,
    val lastError: String? = null
)

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DownloadsUiState())
    val uiState: StateFlow<DownloadsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            downloadRepository.observeDownloads(limit = 200).collect { tasks ->
                _uiState.update { it.copy(tasks = tasks) }
            }
        }
    }

    fun updateSourceInput(value: String) {
        _uiState.update { it.copy(sourceInput = value, lastError = null) }
    }

    fun updateMaxConcurrentInput(value: String) {
        _uiState.update { it.copy(maxConcurrentInput = value, lastError = null) }
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
