package com.iptv.tv.feature.diagnostics

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.SyncLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsUiState(
    val title: String = "Диагностика",
    val description: String = "Статус сети/движка, логи и тест torrent-resolve",
    val engineEndpoint: String = "http://127.0.0.1:6878",
    val torrentDescriptor: String = "",
    val torEnabled: Boolean = false,
    val engineConnected: Boolean = false,
    val enginePeers: Int = 0,
    val engineSpeedKbps: Int = 0,
    val engineMessage: String = "Engine not connected",
    val networkSummary: String = "Unknown",
    val runtimeSummary: String = "unknown",
    val playerStartupAvgMs: Long = 0L,
    val playerErrorCount: Int = 0,
    val playerRebufferCount: Int = 0,
    val resolvedStreamUrl: String? = null,
    val exportedLogPath: String? = null,
    val logs: List<SyncLog> = emptyList(),
    val isBusy: Boolean = false,
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val engineRepository: EngineRepository,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    init {
        observeEngineStatus()
        observeSettings()
        observeLogs()
        refreshNetworkStatus()
        refreshRuntimeSummary()
        startPeriodicRefresh()
    }

    fun updateEngineEndpoint(value: String) {
        _uiState.update { it.copy(engineEndpoint = value, lastError = null) }
    }

    fun updateTorrentDescriptor(value: String) {
        _uiState.update { it.copy(torrentDescriptor = value, lastError = null) }
    }

    fun connectEngine() {
        val endpoint = _uiState.value.engineEndpoint.trim()
        if (endpoint.isBlank()) {
            _uiState.update { it.copy(lastError = "Endpoint движка не может быть пустым") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, lastError = null, lastInfo = null) }
            settingsRepository.setEngineEndpoint(endpoint)
            when (val result = engineRepository.connect(endpoint)) {
                is AppResult.Success -> {
                    diagnosticsRepository.addLog("diagnostics_connect", "Manual engine connect: $endpoint")
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            lastInfo = "Подключение к движку выполнено"
                        )
                    }
                }
                is AppResult.Error -> {
                    diagnosticsRepository.addLog("diagnostics_connect_error", result.message)
                    _uiState.update { it.copy(isBusy = false, lastError = result.message) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun refreshEngineStatus() {
        viewModelScope.launch {
            when (val result = engineRepository.refreshStatus()) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(lastInfo = "Статус движка обновлен", lastError = null) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(lastError = result.message) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun resolveTorrentDescriptor() {
        val descriptor = _uiState.value.torrentDescriptor.trim()
        if (descriptor.isBlank()) {
            _uiState.update { it.copy(lastError = "Введите magnet/acestream descriptor") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, lastError = null, resolvedStreamUrl = null) }
            when (val result = engineRepository.resolveTorrentStream(descriptor)) {
                is AppResult.Success -> {
                    diagnosticsRepository.addLog("diagnostics_resolve", "Torrent descriptor resolved")
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            resolvedStreamUrl = result.data,
                            lastInfo = "Descriptor успешно резолвлен"
                        )
                    }
                }
                is AppResult.Error -> {
                    diagnosticsRepository.addLog("diagnostics_resolve_error", result.message)
                    _uiState.update { it.copy(isBusy = false, lastError = result.message) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshNetworkStatus() {
        _uiState.update { it.copy(networkSummary = buildNetworkSummary()) }
    }

    fun refreshRuntimeSummary() {
        _uiState.update { it.copy(runtimeSummary = buildRuntimeSummary()) }
    }

    fun exportLogsToFile() {
        viewModelScope.launch {
            val logs = _uiState.value.logs
            if (logs.isEmpty()) {
                _uiState.update { it.copy(lastError = "Нет логов для экспорта") }
                return@launch
            }

            runCatching {
                val fileName = "diagnostics-logs-${System.currentTimeMillis()}.txt"
                val content = buildLogsText(logs)
                saveTextToPublicDownloads(fileName = fileName, content = content)
            }.onSuccess { path ->
                _uiState.update {
                    it.copy(
                        exportedLogPath = path,
                        lastInfo = "Логи экспортированы: $path",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { it.copy(lastError = "Не удалось экспортировать логи: ${throwable.message}") }
            }
        }
    }

    private fun saveTextToPublicDownloads(fileName: String, content: String): String {
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в публичной папке Download")

            try {
                resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(content)
                } ?: error("Не удалось открыть файл для записи: $uri")

                val complete = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, complete, null, null)
                return "/storage/emulated/0/Download/$fileName"
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
        }

        @Suppress("DEPRECATION")
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!publicDownloads.exists()) {
            publicDownloads.mkdirs()
        }
        val file = java.io.File(publicDownloads, fileName)
        file.writeText(content)
        return file.absolutePath
    }

    fun exportLogsToUri(uriString: String) {
        viewModelScope.launch {
            val logs = _uiState.value.logs
            if (logs.isEmpty()) {
                _uiState.update { it.copy(lastError = "Нет логов для экспорта") }
                return@launch
            }

            runCatching {
                val uri = android.net.Uri.parse(uriString)
                val content = buildLogsText(logs)
                appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(content)
                } ?: error("Не удалось открыть файл для записи")
                uri.toString()
            }.onSuccess { uri ->
                _uiState.update {
                    it.copy(
                        exportedLogPath = uri,
                        lastInfo = "Логи экспортированы: $uri",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { it.copy(lastError = "Не удалось экспортировать логи: ${throwable.message}") }
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

    private fun observeSettings() {
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

    private fun observeLogs() {
        viewModelScope.launch {
            diagnosticsRepository.observeLogs(limit = 120).collect { logs ->
                val readyStartupSamples = logs
                    .filter { it.status == "player_ready" }
                    .mapNotNull { log ->
                        Regex("startupMs=(\\d+)").find(log.message)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toLongOrNull()
                    }
                val startupAvg = if (readyStartupSamples.isEmpty()) 0L else readyStartupSamples.average().toLong()
                _uiState.update {
                    it.copy(
                        logs = logs,
                        playerStartupAvgMs = startupAvg,
                        playerErrorCount = logs.count { log -> log.status == "player_error" },
                        playerRebufferCount = logs.count { log -> log.status == "player_rebuffer" }
                    )
                }
            }
        }
    }

    private fun startPeriodicRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                refreshNetworkStatus()
                refreshRuntimeSummary()
                engineRepository.refreshStatus()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildNetworkSummary(): String {
        val network = connectivityManager.activeNetwork ?: return "offline"
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return "no capabilities"

        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        val metered = connectivityManager.isActiveNetworkMetered
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val notRoaming = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        } else {
            true
        }
        return "transport=$transport, metered=$metered, validated=$validated, notRoaming=$notRoaming"
    }

    private fun buildRuntimeSummary(): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val maxMb = runtime.maxMemory() / MB
        val uptimeSec = SystemClock.elapsedRealtime() / 1_000
        return "mem=${usedMb}MB/${maxMb}MB, uptime=${uptimeSec}s"
    }

    private fun buildLogsText(logs: List<SyncLog>): String {
        return buildString {
            logs.forEach { log ->
                append(log.createdAt)
                append(" | ")
                append(log.status)
                append(" | playlist=")
                append(log.playlistId ?: "-")
                append(" | ")
                append(log.message)
                append('\n')
            }
        }
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}

