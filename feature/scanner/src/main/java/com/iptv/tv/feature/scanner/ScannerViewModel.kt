package com.iptv.tv.feature.scanner

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.ScannerRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.PlaylistCandidate
import com.iptv.tv.core.model.ScannerLearnedQuery
import com.iptv.tv.core.model.ScannerProviderScope
import com.iptv.tv.core.model.ScannerProxySettings
import com.iptv.tv.core.model.ScannerSearchMode
import com.iptv.tv.core.model.ScannerSearchRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.TimeZone
import javax.inject.Inject

enum class ScannerStatusType {
    INFO,
    LOADING,
    SUCCESS,
    ERROR
}

data class ScannerPreset(
    val id: String,
    val title: String,
    val query: String,
    val keywords: String,
    val provider: ScannerProviderScope = ScannerProviderScope.ALL,
    val description: String
)

data class ScannerProviderHealthUi(
    val provider: ScannerProviderScope,
    val providerName: String,
    val score: Int,
    val successCount: Int,
    val timeoutCount: Int,
    val errorCount: Int,
    val timeoutStreak: Int,
    val cooldownRemainingSec: Long,
    val lastIssue: String? = null
)

data class ScannerUiState(
    val title: String = "Сканер репозиториев",
    val description: String = "Поиск публичных M3U/M3U8 с сохранением найденного в Мои плейлисты",
    val query: String = "iptv",
    val keywords: String = "",
    val presets: List<ScannerPreset> = scannerPresets(),
    val selectedPresetId: String? = null,
    val selectedProvider: ScannerProviderScope = ScannerProviderScope.ALL,
    val selectedSearchMode: ScannerSearchMode = ScannerSearchMode.AUTO,
    val showAdvancedFilters: Boolean = false,
    val repoFilter: String = "",
    val pathFilter: String = "",
    val minSizeBytes: String = "",
    val maxSizeBytes: String = "",
    val updatedDaysBack: String = "",
    val selectedPreview: PlaylistCandidate? = null,
    val aiEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val statusType: ScannerStatusType = ScannerStatusType.INFO,
    val statusTitle: String = "Готово к поиску",
    val statusDetails: String = "Введите запрос и нажмите \"Найти и сохранить найденное\".",
    val results: List<PlaylistCandidate> = emptyList(),
    val progressCurrentStep: Int = 0,
    val progressTotalSteps: Int = 0,
    val progressFoundItems: Int = 0,
    val progressStageLabel: String = "",
    val progressStageLocation: String = "",
    val progressElapsedSeconds: Long = 0,
    val progressTimeLimitSeconds: Long = 300,
    val exportedLinksPath: String? = null,
    val providerHealth: List<ScannerProviderHealthUi> = defaultProviderHealthUi()
)

private enum class SearchPhase {
    IDLE,
    SCANNING,
    IMPORTING,
    EXPORTING
}

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val scannerRepository: ScannerRepository,
    private val playlistRepository: PlaylistRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()
    private var searchAttemptId: Long = 0L
    private var stopRequestedByUser: Boolean = false
    private var stopImportRequested: Boolean = false
    private var searchJob: Job? = null
    private var searchPhase: SearchPhase = SearchPhase.IDLE
    private var currentMergedCandidates: LinkedHashMap<String, PlaylistCandidate> = linkedMapOf()
    private val localAiAssistant = LocalAiQueryAssistant()
    private val probeCache = ConcurrentHashMap<String, ProbeCacheEntry>()
    private val providerTimeoutStreak = mutableMapOf<ScannerProviderScope, Int>()
    private val providerCooldownUntilMs = mutableMapOf<ScannerProviderScope, Long>()
    private val providerHealthStats = mutableMapOf<ScannerProviderScope, ProviderHealthRuntime>()
    private var learnedQueryTemplates: List<ScannerLearnedQuery> = emptyList()

    init {
        observeAiSetting()
        observeLearnedQueryTemplates()
    }

    fun updateQuery(value: String) = _uiState.update { it.copy(query = value) }
    fun updateKeywords(value: String) = _uiState.update { it.copy(keywords = value) }
    fun updateRepoFilter(value: String) = _uiState.update { it.copy(repoFilter = value) }
    fun updatePathFilter(value: String) = _uiState.update { it.copy(pathFilter = value) }
    fun updateMinSize(value: String) = _uiState.update { it.copy(minSizeBytes = value) }
    fun updateMaxSize(value: String) = _uiState.update { it.copy(maxSizeBytes = value) }
    fun updateUpdatedDaysBack(value: String) = _uiState.update { it.copy(updatedDaysBack = value) }

    fun applyPreset(presetId: String) {
        val preset = scannerPresets().firstOrNull { it.id == presetId } ?: return
        _uiState.update {
            it.copy(
                query = preset.query,
                keywords = preset.keywords,
                selectedProvider = preset.provider,
                selectedPresetId = preset.id,
                repoFilter = "",
                pathFilter = "",
                minSizeBytes = "",
                maxSizeBytes = "",
                updatedDaysBack = "",
                statusType = ScannerStatusType.INFO,
                statusTitle = "Пресет: ${preset.title}",
                statusDetails = preset.description
            )
        }
    }

    fun updateProvider(value: ScannerProviderScope) = _uiState.update {
        it.copy(
            selectedProvider = value,
            statusType = ScannerStatusType.INFO,
            statusTitle = "Источник: ${providerDisplayName(value)}",
            statusDetails = buildSearchHint(
                provider = value,
                mode = it.selectedSearchMode
            )
        )
    }

    fun updateSearchMode(value: ScannerSearchMode) = _uiState.update {
        it.copy(
            selectedSearchMode = value,
            statusType = ScannerStatusType.INFO,
            statusTitle = "Режим: ${searchModeDisplayName(value)}",
            statusDetails = buildSearchHint(
                provider = it.selectedProvider,
                mode = value
            )
        )
    }

    fun toggleAdvancedFilters() = _uiState.update { it.copy(showAdvancedFilters = !it.showAdvancedFilters) }
    fun selectPreview(item: PlaylistCandidate?) = _uiState.update { it.copy(selectedPreview = item) }

    fun resetFilters() = _uiState.update {
        it.copy(
            keywords = "",
            selectedPresetId = null,
            repoFilter = "",
            pathFilter = "",
            minSizeBytes = "",
            maxSizeBytes = "",
            updatedDaysBack = "",
            selectedProvider = ScannerProviderScope.ALL,
            selectedSearchMode = ScannerSearchMode.AUTO,
            statusType = ScannerStatusType.INFO,
            statusTitle = "Фильтры очищены",
            statusDetails = "Оставьте только запрос и запустите поиск."
        )
    }

    fun search() = scanAndSaveFound()

    fun scanOnlyTop10() {
        logClick(saveFoundResults = false)
        runSearch(saveFoundResults = false)
    }

    fun scanAndSaveTop10() {
        scanAndSaveFound()
    }

    fun scanAndSaveFound() {
        logClick(saveFoundResults = true)
        runSearch(saveFoundResults = true)
    }

    fun stopSearch() {
        if (!_uiState.value.isLoading) return
        if (searchPhase == SearchPhase.IMPORTING) {
            stopImportRequested = true
            _uiState.update {
                it.copy(
                    statusType = ScannerStatusType.INFO,
                    statusTitle = "Остановка сохранения принята",
                    statusDetails = "Завершаем текущий импорт и останавливаем сохранение остальных списков.",
                    progressStageLabel = "Сохранение найденного",
                    progressStageLocation = "Остановка после текущего файла..."
                )
            }
            viewModelScope.launch {
                safeLog(
                    status = "scanner_stop_import_requested",
                    message = "attempt=$searchAttemptId, phase=IMPORTING, found=${currentMergedCandidates.size}"
                )
            }
            return
        }
        if (searchPhase == SearchPhase.EXPORTING) {
            _uiState.update {
                it.copy(
                    statusType = ScannerStatusType.INFO,
                    statusTitle = "Экспорт завершается",
                    statusDetails = "Остановка принята. Дождитесь завершения записи TXT.",
                    progressStageLabel = "Экспорт ссылок",
                    progressStageLocation = "Завершение записи файла..."
                )
            }
            viewModelScope.launch {
                safeLog(
                    status = "scanner_stop_export_deferred",
                    message = "attempt=$searchAttemptId, phase=EXPORTING, found=${currentMergedCandidates.size}"
                )
            }
            return
        }
        if (stopRequestedByUser) {
            viewModelScope.launch {
                safeLog(
                    status = "scanner_stop_ignored",
                    message = "attempt=$searchAttemptId, stop already requested"
                )
            }
            return
        }
        val wasActive = searchJob?.isActive == true
        stopRequestedByUser = true
        _uiState.update {
            it.copy(
                statusType = ScannerStatusType.INFO,
                statusTitle = "Остановка поиска",
                statusDetails = "Прерываем сетевые запросы, сохраняем уже найденное.",
                progressStageLabel = "Остановка по запросу пользователя",
                progressStageLocation = "Отмена активного запроса..."
            )
        }
        viewModelScope.launch {
            safeLog(
                status = "scanner_stop_requested",
                message = "attempt=$searchAttemptId, step=${_uiState.value.progressCurrentStep}/${_uiState.value.progressTotalSteps}, activeBeforeCancel=$wasActive"
            )
        }
        searchJob?.cancel(CancellationException("Scanner stop requested by user"))
    }

    fun exportFoundLinksToTxt() {
        if (_uiState.value.isLoading) {
            _uiState.update {
                it.copy(
                    statusType = ScannerStatusType.INFO,
                    statusTitle = "Экспорт недоступен",
                    statusDetails = "Дождитесь завершения текущего сканирования/сохранения."
                )
            }
            return
        }

        val candidates = currentMergedCandidates.values.toList().ifEmpty { _uiState.value.results }
        val sourceQuery = _uiState.value.query
        if (candidates.isEmpty()) {
            _uiState.update {
                it.copy(
                    statusType = ScannerStatusType.INFO,
                    statusTitle = "Нечего экспортировать",
                    statusDetails = "Сначала выполните поиск: найденные ссылки появятся здесь."
                )
            }
            return
        }

        viewModelScope.launch {
            val refined = applyResultPostFilter(
                attemptId = searchAttemptId,
                state = _uiState.value,
                candidates = candidates,
                stage = "manual_export"
            ).candidates
            if (refined.isEmpty()) {
                _uiState.update {
                    it.copy(
                        statusType = ScannerStatusType.INFO,
                        statusTitle = "Нечего экспортировать",
                        statusDetails = "После фильтра по свежести список пуст. Ослабьте фильтр \"Дней назад\"."
                    )
                }
                safeLog(
                    status = "scanner_export_links_skip",
                    message = "mode=manual, query=${sourceQuery.take(80)}, reason=empty_after_refine"
                )
                return@launch
            }
            exportCandidatesToTxt(candidates = refined, sourceQuery = sourceQuery)
                .onSuccess { path ->
                _uiState.update {
                    it.copy(
                        exportedLinksPath = path,
                        statusType = ScannerStatusType.SUCCESS,
                        statusTitle = "Ссылки сохранены",
                        statusDetails = "Экспортировано ${refined.size} ссылок в TXT: $path"
                    )
                }
                safeLog(
                    status = "scanner_export_links_ok",
                    message = "mode=manual, query=${sourceQuery.take(80)}, count=${refined.size}, path=$path"
                )
            }.onFailure { throwable ->
                val reason = throwable.message ?: throwable.javaClass.simpleName
                _uiState.update {
                    it.copy(
                        statusType = ScannerStatusType.ERROR,
                        statusTitle = "Ошибка экспорта",
                        statusDetails = "Не удалось сохранить TXT: $reason"
                    )
                }
                safeLog(
                    status = "scanner_export_links_error",
                    message = "mode=manual, query=${sourceQuery.take(80)}, reason=$reason"
                )
            }
        }
    }

    private suspend fun exportCandidatesToTxt(
        candidates: List<PlaylistCandidate>,
        sourceQuery: String
    ): Result<String> {
        return runCatching {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val content = buildString {
                    appendLine("myscanerIPTV | Экспорт найденных плейлистов")
                    appendLine("Поиск: $sourceQuery")
                    appendLine("Найдено: ${candidates.size}")
                    appendLine("Сформировано: $now")
                    appendLine()
                    candidates.forEachIndexed { index, candidate ->
                        appendLine("${index + 1}. ${candidate.name.ifBlank { "Без названия" }}")
                        appendLine("URL: ${candidate.downloadUrl.ifBlank { "-" }}")
                        appendLine("Provider: ${candidate.provider} | Repo: ${candidate.repository} | Path: ${candidate.path}")
                        appendLine()
                    }
                }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
                val queryPart = sourceQuery.toSafeFilePart()
                val fileName = "Tv_list_${queryPart}_$stamp.txt"
                saveTextToPublicDownloads(fileName = fileName, content = content)
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
        val file = File(publicDownloads, fileName)
        file.writeText(content)
        return file.absolutePath
    }

    private fun runSearch(saveFoundResults: Boolean) {
        if (_uiState.value.isLoading) {
            viewModelScope.launch {
                safeLog(
                    status = "scanner_click_ignored",
                    message = "Search already running; save=$saveFoundResults"
                )
            }
            return
        }

        val state = _uiState.value
        validateInput(state)?.let { message ->
            setStatus(
                type = ScannerStatusType.ERROR,
                title = "Ошибка ввода",
                details = message,
                isLoading = false,
                hasSearched = false
            )
            viewModelScope.launch {
                safeLog(
                    status = "scanner_input_error",
                    message = "message=$message | query=${state.query} | provider=${state.selectedProvider}"
                )
            }
            return
        }

        searchJob = viewModelScope.launch {
            val attemptId = ++searchAttemptId
            val startedAt = System.currentTimeMillis()
            val searchRuntimeLimitMs = SEARCH_MAX_RUNTIME_MS
            val actionLabel = if (saveFoundResults) "поиск и сохранение" else "поиск"
            val targetResultCount = if (saveFoundResults) SEARCH_SAVE_FETCH_TARGET else SEARCH_DISPLAY_LIMIT
            var runState = state
            val preflight = runNetworkPreflight(runState.selectedSearchMode)
            safeLog(
                status = "scanner_preflight",
                message = "attempt=$attemptId, mode=${runState.selectedSearchMode}, apiReachable=${preflight.apiReachable}, webReachable=${preflight.webReachable}, details=${preflight.details}"
            )
            var preflightWarning: String? = null
            var preflightDegraded = shouldAbortByPreflight(runState.selectedSearchMode, preflight)
            if (preflightDegraded) {
                preflightWarning = preflightAbortMessage(runState.selectedSearchMode, preflight)
                safeLog(
                    status = "scanner_preflight_warn",
                    message = "attempt=$attemptId, reason=$preflightWarning"
                )
            }
            if (runState.selectedSearchMode == ScannerSearchMode.AUTO &&
                !preflight.apiReachable &&
                preflight.webReachable
            ) {
                runState = runState.copy(selectedSearchMode = ScannerSearchMode.SEARCH_ENGINE)
                safeLog(
                    status = "scanner_mode_auto_fallback",
                    message = "attempt=$attemptId, switch=AUTO->SEARCH_ENGINE, reason=api_dns_unreachable"
                )
                preflightDegraded = false
                preflightWarning = null
            } else if (runState.selectedSearchMode == ScannerSearchMode.AUTO &&
                preflight.apiReachable &&
                !preflight.webReachable
            ) {
                runState = runState.copy(selectedSearchMode = ScannerSearchMode.DIRECT_API)
                safeLog(
                    status = "scanner_mode_auto_fallback",
                    message = "attempt=$attemptId, switch=AUTO->DIRECT_API, reason=web_dns_unreachable"
                )
                preflightDegraded = false
                preflightWarning = null
            }
            val proxySettings = settingsRepository.observeScannerProxySettings().first()
            val proxySummary = applyScannerProxySettings(proxySettings)
            stopRequestedByUser = false
            stopImportRequested = false
            searchPhase = SearchPhase.SCANNING
            currentMergedCandidates = linkedMapOf()
            resetProviderHealthTracking()
            val timeoutMs = if (preflightDegraded) NETWORK_STEP_TIMEOUT_DEGRADED_MS else NETWORK_STEP_TIMEOUT_MS
            val hardTimeoutMs = if (preflightDegraded) STEP_HARD_TIMEOUT_DEGRADED_MS else STEP_HARD_TIMEOUT_MS
            setStatus(
                type = ScannerStatusType.LOADING,
                title = "Выполняется $actionLabel",
                details = buildString {
                    append("Источник: ${providerDisplayName(runState.selectedProvider)}")
                    append(" | режим: ${searchModeDisplayName(runState.selectedSearchMode)}")
                    append(" | цель: ${if (saveFoundResults) "сохранить найденное" else "найти совпадения"}")
                    append(" | лимит: ${searchRuntimeLimitMs / 60_000} мин")
                    append(" | proxy: $proxySummary")
                    if (preflightWarning != null) {
                        append(" | preflight: сеть ограничена, запуск в деградированном режиме")
                    }
                },
                isLoading = true,
                hasSearched = runState.hasSearched
            )
            _uiState.update {
                it.copy(
                    progressCurrentStep = 0,
                    progressTotalSteps = 0,
                    progressFoundItems = 0,
                    progressStageLabel = "Подготовка поиска",
                    progressStageLocation = providerDisplayName(state.selectedProvider),
                    progressElapsedSeconds = 0,
                    progressTimeLimitSeconds = searchRuntimeLimitMs / 1_000L,
                    exportedLinksPath = null
                )
            }
            safeLog(
                status = "scanner_start",
                message = "attempt=$attemptId, query=${runState.query}, provider=${runState.selectedProvider}, save=$saveFoundResults, preset=${runState.selectedPresetId ?: "-"}, ai=${runState.aiEnabled}, proxy=$proxySummary, ${buildRequestSnapshot(runState)}"
            )

            val watchdogJob = launch {
                delay(SEARCH_WATCHDOG_MS)
                if (_uiState.value.isLoading && searchAttemptId == attemptId) {
                    safeLog(
                        status = "scanner_watchdog",
                        message = "attempt=$attemptId still running after ${SEARCH_WATCHDOG_MS / 1000}s"
                    )
                }
            }
            val elapsedTickerJob = launch {
                while (_uiState.value.isLoading && searchAttemptId == attemptId) {
                    val elapsedSec = ((System.currentTimeMillis() - startedAt) / 1000L)
                        .coerceAtLeast(0L)
                    _uiState.update { current ->
                        if (!current.isLoading) {
                            current
                        } else {
                            current.copy(progressElapsedSeconds = elapsedSec)
                        }
                    }
                    delay(1_000L)
                }
            }

            try {
                val searchPlan = buildSearchPlan(runState)
                val effectivePlan = if (preflightDegraded) {
                    val trimmed = searchPlan.take(PREFLIGHT_DEGRADED_PLAN_STEPS)
                    safeLog(
                        status = "scanner_plan_degraded",
                        message = "attempt=$attemptId, reason=preflight_unreachable, originalSteps=${searchPlan.size}, effectiveSteps=${trimmed.size}, timeoutMs=$timeoutMs"
                    )
                    trimmed
                } else {
                    searchPlan
                }
                _uiState.update {
                    it.copy(
                        progressCurrentStep = 0,
                        progressTotalSteps = effectivePlan.size,
                        progressStageLabel = "План построен",
                        progressStageLocation = "Шагов: ${effectivePlan.size}",
                        progressFoundItems = 0
                    )
                }
                safeLog(
                    status = "scanner_plan",
                    message = "attempt=$attemptId, steps=${effectivePlan.size}"
                )
                if (state.aiEnabled) {
                    val learnedTop = learnedQueryTemplates
                        .take(3)
                        .joinToString(" | ") { it.query }
                        .ifBlank { "-" }
                    safeLog(
                        status = "scanner_ai_memory",
                        message = "attempt=$attemptId, templates=${learnedQueryTemplates.size}, top=$learnedTop"
                    )
                    safeLog(
                        status = "scanner_ai_plan",
                        message = "attempt=$attemptId, ai=enabled(local), steps=${effectivePlan.size}, preset=${state.selectedPresetId ?: "-"}"
                    )
                }
                effectivePlan.forEachIndexed { index, step ->
                    safeLog(
                        status = "scanner_plan_step",
                        message = "attempt=$attemptId, step=${index + 1}/${effectivePlan.size}, label=${step.label}, ${buildRequestSnapshot(step.request)}"
                    )
                }

                val outcome = withTimeoutOrNull(searchRuntimeLimitMs) {
                    executeSearchPlan(
                        attemptId = attemptId,
                        state = runState,
                        plan = effectivePlan,
                        targetResultCount = targetResultCount,
                        networkStepTimeoutMs = timeoutMs,
                        stepHardTimeoutMs = hardTimeoutMs
                    )
                } ?: run {
                    val partial = currentMergedCandidates.values.take(targetResultCount)
                    safeLog(
                        status = "scanner_time_limit_reached",
                        message = "attempt=$attemptId, limitMs=$searchRuntimeLimitMs, partial=${partial.size}"
                    )
                    SearchPlanOutcome(
                        candidates = partial,
                        errors = listOf("Ограничение времени поиска достигнуто (${searchRuntimeLimitMs / 60_000} мин)"),
                        successfulStepQueries = emptyList(),
                        timedOut = true
                    )
                }

                val foundAll = applyResultPostFilter(
                    attemptId = attemptId,
                    state = runState,
                    candidates = outcome.candidates,
                    stage = "search_complete"
                ).candidates
                currentMergedCandidates = LinkedHashMap(foundAll.associateBy { it.id })
                val foundForDisplay = foundAll.take(SEARCH_DISPLAY_LIMIT)
                val stoppedManually = stopRequestedByUser
                val timedOut = outcome.timedOut
                val importSummary = if (saveFoundResults) {
                    searchPhase = SearchPhase.IMPORTING
                    _uiState.update {
                        it.copy(
                            progressCurrentStep = searchPlan.size.coerceAtLeast(1),
                            progressTotalSteps = searchPlan.size.coerceAtLeast(1),
                            progressFoundItems = foundAll.size,
                            progressStageLabel = "Сохранение найденного",
                            progressStageLocation = "Подготовка сохранения 0/${foundAll.size}",
                            statusType = ScannerStatusType.LOADING,
                            statusTitle = if (timedOut) {
                                "Лимит 5 минут достигнут, сохраняем найденное"
                            } else if (stoppedManually) {
                                "Остановка принята, сохраняем найденное"
                            } else {
                                "Сохраняем найденные плейлисты"
                            },
                            statusDetails = "Сохранение: 0/${foundAll.size}"
                        )
                    }
                    withContext(NonCancellable) {
                        importFoundPlaylists(candidates = foundAll) { progress ->
                            val baseText =
                                "Сохранение: ${progress.processed}/${progress.total} | " +
                                    "сохранено=${progress.imported}, дубликаты=${progress.skipped}, ошибок=${progress.failed}"
                            val progressText = progress.current?.let { "$baseText | $it" } ?: baseText
                            _uiState.update {
                                it.copy(
                                    progressStageLabel = "Сохранение найденного",
                                    progressStageLocation = progressText,
                                    statusDetails = progressText
                                )
                            }
                        }
                    }
                } else {
                    null
                }
                val autoExportPath = if (saveFoundResults && foundAll.isNotEmpty()) {
                    searchPhase = SearchPhase.EXPORTING
                    withContext(NonCancellable) {
                        val exportResult = exportCandidatesToTxt(
                            candidates = foundAll,
                            sourceQuery = runState.query
                        )
                        val path = exportResult.getOrNull()
                        if (path != null) {
                            safeLog(
                                status = "scanner_export_links_ok",
                                message = "mode=auto, attempt=$attemptId, query=${runState.query.take(80)}, count=${foundAll.size}, path=$path"
                            )
                        } else {
                            val throwable = exportResult.exceptionOrNull()
                            safeLog(
                                status = "scanner_export_links_error",
                                message = "mode=auto, attempt=$attemptId, query=${runState.query.take(80)}, reason=${throwable?.message ?: throwable?.javaClass?.simpleName ?: "unknown"}"
                            )
                        }
                        path
                    }
                } else {
                    null
                }
                if (foundAll.isNotEmpty()) {
                    val relatedQueries = outcome.successfulStepQueries
                        .filterNot { it.equals(runState.query, ignoreCase = true) }
                        .distinct()
                    runCatching {
                        settingsRepository.recordScannerLearning(
                            query = runState.query,
                            relatedQueries = relatedQueries,
                            presetId = runState.selectedPresetId
                        )
                    }.onSuccess {
                        safeLog(
                            status = "scanner_ai_learn_saved",
                            message = "attempt=$attemptId, primary=${runState.query}, related=${relatedQueries.size}, templatesCached=${learnedQueryTemplates.size}"
                        )
                    }.onFailure { throwable ->
                        safeLog(
                            status = "scanner_ai_learn_error",
                            message = "attempt=$attemptId, ${throwable.toLogSummary(maxDepth = 3)}"
                        )
                    }
                }
                val hasErrors = outcome.errors.isNotEmpty()
                val primaryError = selectPrimaryError(outcome.errors)
                val importFailedCompletely = saveFoundResults &&
                    importSummary != null &&
                    !importSummary.stoppedByUser &&
                    importSummary.imported == 0 &&
                    importSummary.failed > 0

                val statusType = when {
                    timedOut && foundAll.isNotEmpty() -> ScannerStatusType.SUCCESS
                    timedOut -> ScannerStatusType.INFO
                    stoppedManually && foundAll.isNotEmpty() -> ScannerStatusType.SUCCESS
                    stoppedManually -> ScannerStatusType.INFO
                    importFailedCompletely -> ScannerStatusType.ERROR
                    foundAll.isNotEmpty() -> ScannerStatusType.SUCCESS
                    hasErrors -> ScannerStatusType.ERROR
                    else -> ScannerStatusType.INFO
                }
                val statusTitle = when {
                    timedOut -> "Достигнут лимит времени поиска (5 минут)"
                    stoppedManually -> "Поиск остановлен пользователем"
                    importFailedCompletely -> "Поиск завершен, но сохранить не удалось"
                    foundAll.isNotEmpty() -> "Поиск завершен"
                    hasErrors -> "Ошибка поиска"
                    else -> "Ничего не найдено"
                }
                val statusDetails = when {
                    timedOut && foundAll.isNotEmpty() -> {
                        val summary = buildResultSummary(foundAll, importSummary)
                        "$summary | поиск остановлен по лимиту 5 минут"
                    }
                    timedOut -> "За 5 минут совпадения не найдены. Уточните запрос или смените источник."
                    stoppedManually && foundAll.isNotEmpty() -> {
                        val summary = buildResultSummary(foundAll, importSummary)
                        "$summary | остановлено вручную"
                    }
                    stoppedManually -> "Поиск остановлен вручную. Совпадения не найдены."
                    foundAll.isNotEmpty() -> {
                        val summary = buildResultSummary(foundAll, importSummary)
                        if (hasErrors) "$summary | предупреждений=${outcome.errors.size}" else summary
                    }
                    hasErrors -> "Найдено 0. ${mapScannerError(primaryError)}. Проверьте интернет и повторите."
                    else -> emptyResultsHint(runState)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasSearched = true,
                        results = foundForDisplay,
                        selectedPreview = foundForDisplay.firstOrNull(),
                        exportedLinksPath = autoExportPath,
                        statusType = statusType,
                        statusTitle = statusTitle,
                        statusDetails = statusDetails,
                        progressCurrentStep = searchPlan.size,
                        progressTotalSteps = searchPlan.size,
                        progressFoundItems = foundAll.size,
                        progressStageLabel = "Поиск завершен",
                        progressStageLocation = if (foundAll.isNotEmpty()) {
                            "Подготовка результатов"
                        } else {
                            "Совпадения не найдены"
                        }
                    )
                }

                safeLog(
                    status = when {
                        timedOut && foundAll.isNotEmpty() -> "scanner_timeout_partial"
                        timedOut -> "scanner_timeout_empty"
                        stoppedManually && foundAll.isNotEmpty() -> "scanner_stopped_partial"
                        stoppedManually -> "scanner_stopped_empty"
                        foundAll.isNotEmpty() && hasErrors -> "scanner_ok_partial"
                        foundAll.isNotEmpty() -> "scanner_ok"
                        hasErrors -> "scanner_error"
                        else -> "scanner_empty"
                    },
                    message = "attempt=$attemptId, $statusDetails"
                )

                if (foundAll.isEmpty()) {
                    val noResultReason = detectNoResultsReason(outcome.errors)
                    val noResultProbe = providerNetworkProbe(
                        provider = ScannerProviderScope.ALL,
                        mode = runState.selectedSearchMode
                    )
                    val topErrors = outcome.errors.take(3).joinToString(" || ").take(600)
                    safeLog(
                        status = "scanner_no_results_diagnostic",
                        message = "attempt=$attemptId, reason=$noResultReason, mode=${runState.selectedSearchMode}, provider=${runState.selectedProvider}, query=${runState.query}, errors=$topErrors, network=$noResultProbe"
                    )
                }
            } catch (cancellation: CancellationException) {
                val partial = applyResultPostFilter(
                    attemptId = attemptId,
                    state = runState,
                    candidates = currentMergedCandidates.values.take(targetResultCount),
                    stage = "cancelled"
                ).candidates
                currentMergedCandidates = LinkedHashMap(partial.associateBy { it.id })
                val partialForDisplay = partial.take(SEARCH_DISPLAY_LIMIT)
                val importSummary = if (saveFoundResults && partial.isNotEmpty()) {
                    searchPhase = SearchPhase.IMPORTING
                    _uiState.update {
                        it.copy(
                            statusType = ScannerStatusType.LOADING,
                            statusTitle = "Остановка принята, сохраняем найденное",
                            statusDetails = "Сохранение: 0/${partial.size}",
                            progressStageLabel = "Сохранение найденного",
                            progressStageLocation = "Подготовка сохранения 0/${partial.size}"
                        )
                    }
                    withContext(NonCancellable) {
                        importFoundPlaylists(candidates = partial) { progress ->
                            val baseText =
                                "Сохранение: ${progress.processed}/${progress.total} | " +
                                    "сохранено=${progress.imported}, дубликаты=${progress.skipped}, ошибок=${progress.failed}"
                            val progressText = progress.current?.let { "$baseText | $it" } ?: baseText
                            _uiState.update {
                                it.copy(
                                    progressStageLabel = "Сохранение найденного",
                                    progressStageLocation = progressText,
                                    statusDetails = progressText
                                )
                            }
                        }
                    }
                } else {
                    null
                }
                val autoExportPath = if (saveFoundResults && partial.isNotEmpty()) {
                    searchPhase = SearchPhase.EXPORTING
                    withContext(NonCancellable) {
                        val exportResult = exportCandidatesToTxt(
                            candidates = partial,
                            sourceQuery = runState.query
                        )
                        val path = exportResult.getOrNull()
                        if (path != null) {
                            safeLog(
                                status = "scanner_export_links_ok",
                                message = "mode=auto_cancel, attempt=$attemptId, query=${runState.query.take(80)}, count=${partial.size}, path=$path"
                            )
                        } else {
                            val throwable = exportResult.exceptionOrNull()
                            safeLog(
                                status = "scanner_export_links_error",
                                message = "mode=auto_cancel, attempt=$attemptId, query=${runState.query.take(80)}, reason=${throwable?.message ?: throwable?.javaClass?.simpleName ?: "unknown"}"
                            )
                        }
                        path
                    }
                } else {
                    null
                }
                val partialDetails = if (partial.isEmpty()) {
                    "Поиск остановлен вручную. Совпадений пока нет."
                } else {
                    val importText = if (importSummary != null) {
                        val stopTail = if (importSummary.stoppedByUser) ", остановлено пользователем" else ""
                        " | сохранено=${importSummary.imported}, дубликаты=${importSummary.skipped}, ошибок=${importSummary.failed}$stopTail"
                    } else {
                        ""
                    }
                    "Поиск остановлен вручную. Найдено ${partial.size}$importText."
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasSearched = true,
                        results = partialForDisplay,
                        selectedPreview = partialForDisplay.firstOrNull(),
                        exportedLinksPath = autoExportPath,
                        statusType = if (partial.isEmpty()) ScannerStatusType.INFO else ScannerStatusType.SUCCESS,
                        statusTitle = "Поиск остановлен пользователем",
                        statusDetails = partialDetails,
                        progressFoundItems = partial.size,
                        progressStageLabel = "Остановлено",
                        progressStageLocation = "Поиск отменен пользователем"
                    )
                }
                safeLogNonCancellable(
                    status = "scanner_cancelled",
                    message = "attempt=$attemptId, reason=${cancellation.message ?: "cancelled"}, partial=${partial.size}"
                )
            } catch (throwable: Throwable) {
                val raw = throwable.message ?: throwable.javaClass.simpleName
                val friendly = mapScannerError(raw)
                setStatus(
                    type = ScannerStatusType.ERROR,
                    title = "Ошибка поиска",
                    details = "$friendly. Проверьте интернет и повторите.",
                    isLoading = false,
                    hasSearched = true
                )
                _uiState.update {
                    it.copy(
                        progressStageLabel = "Ошибка выполнения",
                        progressStageLocation = throwable.javaClass.simpleName
                    )
                }
                safeLogNonCancellable(
                    status = "scanner_fatal",
                    message = "attempt=$attemptId, throwable=${throwable.toLogSummary(maxDepth = 5)}, msg=$raw"
                )
            } finally {
                watchdogJob.cancel()
                elapsedTickerJob.cancel()
                val duration = System.currentTimeMillis() - startedAt
                _uiState.update { it.copy(progressElapsedSeconds = (duration / 1000L).coerceAtLeast(0L)) }
                searchPhase = SearchPhase.IDLE
                stopRequestedByUser = false
                stopImportRequested = false
                searchJob = null
                refreshProviderHealthSnapshot()
                safeLogNonCancellable(
                    status = "scanner_finish",
                    message = "attempt=$attemptId, durationMs=$duration, status=${_uiState.value.statusType}, results=${_uiState.value.results.size}, storedPartial=${currentMergedCandidates.size}"
                )
            }
        }
    }

    private fun logClick(saveFoundResults: Boolean) {
        val state = _uiState.value
        viewModelScope.launch {
            safeLog(
                status = "scanner_click",
                message = "save=$saveFoundResults, loading=${state.isLoading}, ${buildRequestSnapshot(state)}"
            )
        }
    }

    private suspend fun safeLog(status: String, message: String, playlistId: Long? = null) {
        runCatching {
            diagnosticsRepository.addLog(
                status = status,
                message = message.take(MAX_LOG_MESSAGE),
                playlistId = playlistId
            )
        }
    }

    private suspend fun safeLogNonCancellable(status: String, message: String, playlistId: Long? = null) {
        withContext(NonCancellable) {
            safeLog(status = status, message = message, playlistId = playlistId)
        }
    }

    private fun observeAiSetting() {
        viewModelScope.launch {
            settingsRepository.observeScannerAiEnabled().collect { enabled ->
                _uiState.update { state ->
                    state.copy(aiEnabled = enabled)
                }
            }
        }
    }

    private fun observeLearnedQueryTemplates() {
        viewModelScope.launch {
            settingsRepository.observeScannerLearnedQueries().collect { learned ->
                learnedQueryTemplates = learned
            }
        }
    }

    private fun buildAppErrorDetails(error: AppResult.Error): String {
        val message = error.message.trim()
        val cause = error.cause?.toLogSummary(maxDepth = 4).orEmpty()
        return when {
            cause.isBlank() -> message
            message.isBlank() -> cause
            else -> "$message | cause=$cause"
        }
    }

    private fun buildRequestSnapshot(state: ScannerUiState): String {
        return buildString {
            append("query=")
            append(state.query)
            append(", provider=")
            append(state.selectedProvider)
            append(", mode=")
            append(state.selectedSearchMode)
            append(", keywords=")
            append(state.keywords.ifBlank { "-" })
            append(", repoFilter=")
            append(state.repoFilter.ifBlank { "-" })
            append(", pathFilter=")
            append(state.pathFilter.ifBlank { "-" })
            append(", minSize=")
            append(state.minSizeBytes.ifBlank { "-" })
            append(", maxSize=")
            append(state.maxSizeBytes.ifBlank { "-" })
            append(", daysBack=")
            append(state.updatedDaysBack.ifBlank { "-" })
        }
    }

    private fun buildRequestSnapshot(request: ScannerSearchRequest): String {
        return buildString {
            append("query=")
            append(request.query)
            append(", provider=")
            append(request.providerScope)
            append(", mode=")
            append(request.searchMode)
            append(", keywords=")
            append(request.keywords.joinToString(",").ifBlank { "-" })
            append(", repoFilter=")
            append(request.repoFilter ?: "-")
            append(", pathFilter=")
            append(request.pathFilter ?: "-")
            append(", minSize=")
            append(request.minSizeBytes?.toString() ?: "-")
            append(", maxSize=")
            append(request.maxSizeBytes?.toString() ?: "-")
            append(", updatedAfter=")
            append(request.updatedAfterEpochMs?.toString() ?: "-")
            append(", limit=")
            append(request.limit)
        }
    }

    private fun buildSearchPlan(state: ScannerUiState): List<SearchPlanStep> {
        val base = state.toRequest()
        val plan = mutableListOf<SearchPlanStep>()
        val intentKeywords = inferIntentKeywords(base.query, base.keywords)
        var aiFocusVariant: String? = null

        plan += SearchPlanStep(
            label = "Точный поиск",
            request = base
        )

        val relaxed = base.copy(
            repoFilter = if (base.providerScope == ScannerProviderScope.BITBUCKET) base.repoFilter else null,
            pathFilter = null,
            minSizeBytes = null,
            maxSizeBytes = null,
            updatedAfterEpochMs = null
        )
        if (relaxed != base) {
            plan += SearchPlanStep(
                label = "Поиск без жестких фильтров",
                request = relaxed
            )
        }

        if (intentKeywords.isNotEmpty()) {
            plan += SearchPlanStep(
                label = "Поиск по тематике запроса",
                request = relaxed.copy(
                    query = boostQueryWithKeywords(relaxed.query, intentKeywords),
                    keywords = mergeKeywords(relaxed.keywords, intentKeywords)
                )
            )
        }

        if (base.providerScope == ScannerProviderScope.ALL) {
            plan += SearchPlanStep(
                label = "Только GitHub",
                request = relaxed.copy(providerScope = ScannerProviderScope.GITHUB)
            )
            plan += SearchPlanStep(
                label = "Только GitLab",
                request = relaxed.copy(providerScope = ScannerProviderScope.GITLAB)
            )
            if (!state.repoFilter.isBlank()) {
                plan += SearchPlanStep(
                    label = "Только Bitbucket",
                    request = relaxed.copy(
                        providerScope = ScannerProviderScope.BITBUCKET,
                        repoFilter = state.repoFilter.trim()
                    )
                )
            }
        }

        val broadQuery = broadFallbackQuery(base.query)
        if (broadQuery != base.query) {
            val broadProvider = if (base.providerScope == ScannerProviderScope.BITBUCKET) {
                ScannerProviderScope.BITBUCKET
            } else {
                ScannerProviderScope.ALL
            }
            plan += SearchPlanStep(
                label = "Широкий fallback-поиск",
                request = relaxed.copy(
                    query = broadQuery,
                    keywords = mergeKeywords(relaxed.keywords, intentKeywords.take(2)),
                    providerScope = broadProvider
                )
            )
        }

        if (state.aiEnabled) {
            val learnedVariants = buildLearnedQueryVariants(
                baseQuery = base.query,
                presetId = state.selectedPresetId,
                intentKeywords = intentKeywords
            )
            learnedVariants.forEachIndexed { idx, variant ->
                plan += SearchPlanStep(
                    label = "AI-обученный ${idx + 1}",
                    request = relaxed.copy(
                        query = variant,
                        keywords = mergeKeywords(relaxed.keywords, inferIntentKeywords(variant, relaxed.keywords))
                    )
                )
            }

            val aiVariants = buildAiQueryVariants(
                query = base.query,
                keywords = base.keywords,
                intentKeywords = intentKeywords
            )
            aiFocusVariant = aiVariants.firstOrNull()

            aiVariants.take(AI_MAX_QUERY_VARIANTS).forEachIndexed { idx, variant ->
                plan += SearchPlanStep(
                    label = "AI-вариант ${idx + 1}",
                    request = relaxed.copy(
                        query = variant,
                        keywords = mergeKeywords(relaxed.keywords, inferIntentKeywords(variant, relaxed.keywords))
                    )
                )
            }

            if (base.providerScope == ScannerProviderScope.ALL && aiVariants.isNotEmpty()) {
                val focusVariant = aiVariants.first()
                plan += SearchPlanStep(
                    label = "AI-fallback GitHub",
                    request = relaxed.copy(
                        providerScope = ScannerProviderScope.GITHUB,
                        query = focusVariant
                    )
                )
                plan += SearchPlanStep(
                    label = "AI-fallback GitLab",
                    request = relaxed.copy(
                        providerScope = ScannerProviderScope.GITLAB,
                        query = focusVariant
                    )
                )
            }
        }

        val maxPlanSteps = if (state.aiEnabled) MAX_PLAN_STEPS_AI else MAX_PLAN_STEPS
        val deduplicated = plan.distinctBy { it.request.key() }
        val focusVariant = aiFocusVariant?.takeIf { it.isNotBlank() }
        if (!state.aiEnabled || base.providerScope != ScannerProviderScope.ALL || focusVariant == null) {
            return deduplicated.take(maxPlanSteps)
        }

        // Ensure both forced fallback probes survive plan truncation.
        val mandatoryTail = listOf(
            SearchPlanStep(
                label = "AI-fallback GitHub",
                request = relaxed.copy(
                    providerScope = ScannerProviderScope.GITHUB,
                    query = focusVariant
                )
            ),
            SearchPlanStep(
                label = "AI-fallback GitLab",
                request = relaxed.copy(
                    providerScope = ScannerProviderScope.GITLAB,
                    query = focusVariant
                )
            )
        ).distinctBy { it.request.key() }

        val mandatoryKeys = mandatoryTail.mapTo(hashSetOf()) { it.request.key() }
        val headLimit = (maxPlanSteps - mandatoryTail.size).coerceAtLeast(0)
        val head = deduplicated
            .filterNot { mandatoryKeys.contains(it.request.key()) }
            .take(headLimit)

        return (head + mandatoryTail)
            .distinctBy { it.request.key() }
            .take(maxPlanSteps)
    }

    private suspend fun executeSearchPlan(
        attemptId: Long,
        state: ScannerUiState,
        plan: List<SearchPlanStep>,
        targetResultCount: Int,
        networkStepTimeoutMs: Long,
        stepHardTimeoutMs: Long
    ): SearchPlanOutcome {
        val merged = linkedMapOf<String, PlaylistCandidate>()
        val errors = mutableListOf<String>()
        val successfulStepQueries = linkedSetOf<String>()
        currentMergedCandidates = linkedMapOf()

        plan.forEachIndexed { index, step ->
            if (merged.size >= targetResultCount) return@forEachIndexed
            if (stopRequestedByUser) {
                errors += "Остановлено пользователем"
                safeLog(
                    status = "scanner_stopped",
                    message = "attempt=$attemptId, beforeStep=${index + 1}, totalFound=${merged.size}"
                )
                currentMergedCandidates = LinkedHashMap(merged)
                return SearchPlanOutcome(
                    candidates = merged.values.take(targetResultCount),
                    errors = errors,
                    successfulStepQueries = successfulStepQueries.toList()
                )
            }

            val stepNumber = index + 1
            val providerName = providerDisplayName(step.request.providerScope)
            setStatus(
                type = ScannerStatusType.LOADING,
                title = "Поиск: шаг $stepNumber/${plan.size}",
                details = "${step.label} | найдено=${merged.size}",
                isLoading = true,
                hasSearched = state.hasSearched
            )
            _uiState.update {
                it.copy(
                    progressCurrentStep = stepNumber,
                    progressTotalSteps = plan.size,
                    progressFoundItems = merged.size,
                    progressStageLabel = step.label,
                    progressStageLocation = "$providerName | ${step.request.query}"
                )
            }

            safeLog(
                status = "scanner_step_start",
                message = "attempt=$attemptId, step=$stepNumber, label=${step.label}, ${buildRequestSnapshot(step.request)}"
            )

            val stepStartedAt = System.currentTimeMillis()
            val stepResult = coroutineScope {
                val stepPulseJob = launch {
                    while (true) {
                        delay(STEP_PULSE_MS)
                        if (!_uiState.value.isLoading) break
                        val elapsed = (System.currentTimeMillis() - stepStartedAt).coerceAtLeast(0L)
                        val elapsedSec = elapsed / 1000L
                        _uiState.update {
                            it.copy(
                                progressStageLocation = "$providerName | идет запрос ${elapsedSec}с | найдено=${merged.size}",
                                statusDetails = "${step.label} | найдено=${merged.size} | в работе ${elapsedSec}с"
                            )
                        }
                        safeLog(
                            status = "scanner_step_pulse",
                            message = "attempt=$attemptId, step=$stepNumber, runningMs=$elapsed, found=${merged.size}, label=${step.label}"
                        )
                    }
                }
                try {
                    val timed = withTimeoutOrNull(stepHardTimeoutMs) {
                        executeStepSearch(
                            attemptId = attemptId,
                            stepNumber = stepNumber,
                            stepLabel = step.label,
                            request = step.request.copy(limit = targetResultCount.coerceAtLeast(SEARCH_DISPLAY_LIMIT)),
                            targetResultCount = targetResultCount,
                            networkStepTimeoutMs = networkStepTimeoutMs
                        )
                    }
                    timed ?: AppResult.Error(
                        message = "Step timeout ${stepHardTimeoutMs / 1000}s (${step.label})"
                    )
                } finally {
                    stepPulseJob.cancel()
                }
            }
            val stepDurationMs = System.currentTimeMillis() - stepStartedAt

            when (stepResult) {
                is AppResult.Success -> {
                    val before = merged.size
                    stepResult.data.forEach { candidate ->
                        merged.putIfAbsent(candidate.id, candidate)
                    }
                    val added = merged.size - before
                    currentMergedCandidates = LinkedHashMap(merged)
                    _uiState.update {
                        it.copy(
                            progressFoundItems = merged.size,
                            progressStageLocation = "$providerName | +$added | всего=${merged.size}"
                        )
                    }
                    safeLog(
                        status = "scanner_step_ok",
                        message = "attempt=$attemptId, step=$stepNumber, added=$added, total=${merged.size}, durationMs=$stepDurationMs"
                    )
                    if (added > 0) {
                        successfulStepQueries += step.request.query
                    }
                }
                is AppResult.Error -> {
                    val details = buildAppErrorDetails(stepResult)
                    val msg = "${step.label}: $details"
                    errors += msg
                    _uiState.update {
                        it.copy(progressStageLocation = "$providerName | ошибка: ${details.take(120)}")
                    }
                    safeLog(
                        status = "scanner_step_error",
                        message = "attempt=$attemptId, step=$stepNumber, durationMs=$stepDurationMs, reason=$details"
                    )
                    if (isFatalNetworkError(details) && merged.isEmpty() && stepNumber <= FAIL_FAST_MAX_STEP) {
                        safeLog(
                            status = "scanner_fail_fast",
                            message = "attempt=$attemptId, step=$stepNumber, reason=network dead-end, details=${details.take(500)}"
                        )
                        currentMergedCandidates = LinkedHashMap(merged)
                        return SearchPlanOutcome(
                            candidates = merged.values.take(targetResultCount),
                            errors = errors,
                            successfulStepQueries = successfulStepQueries.toList()
                        )
                    }
                }
                AppResult.Loading -> {
                    val msg = "${step.label}: repository returned Loading"
                    errors += msg
                    _uiState.update {
                        it.copy(progressStageLocation = "$providerName | статус Loading")
                    }
                    safeLog(
                        status = "scanner_step_loading_state",
                        message = "attempt=$attemptId, step=$stepNumber, durationMs=$stepDurationMs"
                    )
                }
            }

            if (stopRequestedByUser) {
                errors += "Остановлено пользователем"
                safeLog(
                    status = "scanner_stopped",
                    message = "attempt=$attemptId, afterStep=$stepNumber, totalFound=${merged.size}"
                )
                currentMergedCandidates = LinkedHashMap(merged)
                return SearchPlanOutcome(
                    candidates = merged.values.take(targetResultCount),
                    errors = errors,
                    successfulStepQueries = successfulStepQueries.toList()
                )
            }
        }

        currentMergedCandidates = LinkedHashMap(merged)
        return SearchPlanOutcome(
            candidates = merged.values.take(targetResultCount),
            errors = errors,
            successfulStepQueries = successfulStepQueries.toList()
        )
    }

    private suspend fun executeStepSearch(
        attemptId: Long,
        stepNumber: Int,
        stepLabel: String,
        request: ScannerSearchRequest,
        targetResultCount: Int,
        networkStepTimeoutMs: Long
    ): AppResult<List<PlaylistCandidate>> {
        return if (request.providerScope == ScannerProviderScope.ALL) {
            executeAllProvidersStep(
                attemptId = attemptId,
                stepNumber = stepNumber,
                stepLabel = stepLabel,
                request = request,
                targetResultCount = targetResultCount,
                networkStepTimeoutMs = networkStepTimeoutMs
            )
        } else {
            executeSingleProviderStep(
                attemptId = attemptId,
                stepNumber = stepNumber,
                stepLabel = stepLabel,
                request = request,
                networkStepTimeoutMs = networkStepTimeoutMs,
                respectCooldown = false
            )
        }
    }

    private suspend fun executeAllProvidersStep(
        attemptId: Long,
        stepNumber: Int,
        stepLabel: String,
        request: ScannerSearchRequest,
        targetResultCount: Int,
        networkStepTimeoutMs: Long
    ): AppResult<List<PlaylistCandidate>> {
        if (request.searchMode == ScannerSearchMode.SEARCH_ENGINE) {
            safeLog(
                status = "scanner_search_engine_single",
                message = "attempt=$attemptId, step=$stepNumber, label=$stepLabel, provider=ALL"
            )
            return executeSingleProviderStep(
                attemptId = attemptId,
                stepNumber = stepNumber,
                stepLabel = "$stepLabel/SEARCH_ENGINE",
                request = request.copy(providerScope = ScannerProviderScope.ALL),
                networkStepTimeoutMs = networkStepTimeoutMs,
                respectCooldown = false
            )
        }

        val providers = buildList {
            add(ScannerProviderScope.GITHUB)
            add(ScannerProviderScope.GITLAB)
            if (!request.repoFilter.isNullOrBlank()) {
                add(ScannerProviderScope.BITBUCKET)
            }
        }
        val now = System.currentTimeMillis()
        val activeProviders = providers.filterNot { provider ->
            val blockedUntil = providerCooldownUntilMs[provider] ?: 0L
            blockedUntil > now
        }
        var sortedActiveProviders = activeProviders.sortedByDescending { provider ->
            providerHealthScore(provider = provider, nowMs = now)
        }
        val skippedProviders = providers - activeProviders.toSet()

        skippedProviders.forEach { skipped ->
            val remainMs = (providerCooldownUntilMs[skipped] ?: now) - now
            safeLog(
                status = "scanner_provider_skipped",
                message = "attempt=$attemptId, step=$stepNumber, provider=$skipped, reason=temporary_backoff, remainingMs=${remainMs.coerceAtLeast(0L)}"
            )
        }

        refreshProviderHealthSnapshot(nowMs = now)

        if (sortedActiveProviders.isEmpty()) {
            val forcedProvider = providers
                .sortedByDescending { provider -> providerHealthScore(provider = provider, nowMs = now) }
                .firstOrNull()
            if (forcedProvider != null) {
                sortedActiveProviders = listOf(forcedProvider)
                safeLog(
                    status = "scanner_provider_forced_probe",
                    message = "attempt=$attemptId, step=$stepNumber, provider=$forcedProvider, reason=all_in_backoff"
                )
            } else {
                val fallbackInSec = ((providerCooldownUntilMs.values.minOrNull() ?: now) - now).coerceAtLeast(0L) / 1000L
                return AppResult.Error(
                    message = "All providers temporarily paused due repeated timeouts (retry in ~${fallbackInSec}s)"
                )
            }
        }

        val merged = linkedMapOf<String, PlaylistCandidate>()
        val providerErrors = mutableListOf<String>()
        var hasSuccessfulProviderCall = false
        _uiState.update {
            it.copy(
                progressStageLocation = "Параллельно: ${sortedActiveProviders.joinToString(", ") { providerDisplayName(it) }}",
                statusDetails = "$stepLabel | параллельный запрос провайдеров"
            )
        }
        safeLog(
            status = "scanner_provider_order",
            message = "attempt=$attemptId, step=$stepNumber, order=${sortedActiveProviders.joinToString(">") { it.name }}"
        )

        val providerResults = coroutineScope {
            sortedActiveProviders.map { provider ->
                async {
                    val providerName = providerDisplayName(provider)
                    if (stopRequestedByUser) {
                        return@async ProviderExecutionResult(
                            provider = provider,
                            result = AppResult.Error("Stopped by user"),
                            providerName = providerName
                        )
                    }
                    val networkProbe = providerNetworkProbe(provider, request.searchMode)
                    safeLog(
                        status = "scanner_provider_start",
                        message = "attempt=$attemptId, step=$stepNumber, provider=$provider, query=${request.query}, network=$networkProbe"
                    )
                    val result = executeSingleProviderStep(
                        attemptId = attemptId,
                        stepNumber = stepNumber,
                        stepLabel = "$stepLabel/$providerName",
                        request = request.copy(providerScope = provider),
                        networkStepTimeoutMs = networkStepTimeoutMs,
                        respectCooldown = true
                    )
                    ProviderExecutionResult(
                        provider = provider,
                        result = result,
                        providerName = providerName
                    )
                }
            }.awaitAll()
        }

        providerResults.forEach { providerResult ->
            when (val result = providerResult.result) {
                is AppResult.Success -> {
                    hasSuccessfulProviderCall = true
                    val before = merged.size
                    result.data.forEach { merged.putIfAbsent(it.id, it) }
                    val added = merged.size - before
                    safeLog(
                        status = "scanner_provider_ok",
                        message = "attempt=$attemptId, step=$stepNumber, provider=${providerResult.provider}, added=$added, total=${merged.size}"
                    )
                }
                is AppResult.Error -> {
                    providerErrors += "${providerResult.provider.name}: ${result.message}"
                    safeLog(
                        status = "scanner_provider_error",
                        message = "attempt=$attemptId, step=$stepNumber, provider=${providerResult.provider}, reason=${result.message.take(900)}"
                    )
                }
                AppResult.Loading -> {
                    providerErrors += "${providerResult.provider.name}: Loading state"
                    safeLog(
                        status = "scanner_provider_loading_state",
                        message = "attempt=$attemptId, step=$stepNumber, provider=${providerResult.provider}"
                    )
                }
            }
        }

        return if (merged.isNotEmpty()) {
            AppResult.Success(merged.values.take(targetResultCount))
        } else if (hasSuccessfulProviderCall) {
            AppResult.Success(emptyList())
        } else {
            AppResult.Error(
                message = providerErrors.joinToString("; ").ifBlank { "Providers returned no results" }
            )
        }
    }

    private suspend fun executeSingleProviderStep(
        attemptId: Long,
        stepNumber: Int,
        stepLabel: String,
        request: ScannerSearchRequest,
        networkStepTimeoutMs: Long,
        respectCooldown: Boolean
    ): AppResult<List<PlaylistCandidate>> {
        val provider = request.providerScope
        if (respectCooldown && provider != ScannerProviderScope.ALL && request.searchMode != ScannerSearchMode.SEARCH_ENGINE) {
            val now = System.currentTimeMillis()
            val blockedUntil = providerCooldownUntilMs[provider] ?: 0L
            if (blockedUntil > now) {
                val remainMs = blockedUntil - now
                safeLog(
                    status = "scanner_provider_skipped",
                    message = "attempt=$attemptId, step=$stepNumber, provider=$provider, reason=temporary_backoff, remainingMs=${remainMs.coerceAtLeast(0L)}, label=$stepLabel"
                )
                return AppResult.Error(
                    message = "Provider $provider temporarily skipped due repeated timeouts (${(remainMs / 1000L).coerceAtLeast(1L)}s)"
                )
            }
        }
        val startedAtMs = System.currentTimeMillis()
        return try {
            val result = withTimeout(networkStepTimeoutMs) {
                scannerRepository.search(request)
            }
            when (result) {
                is AppResult.Success -> markProviderSuccess(provider = request.providerScope)
                is AppResult.Error -> {
                    val isTimeoutError = result.message.contains("timeout", ignoreCase = true)
                    if (isTimeoutError) {
                        markProviderTimeout(provider = request.providerScope)
                    } else {
                        markProviderError(provider = request.providerScope, reason = result.message)
                    }
                }
                AppResult.Loading -> markProviderError(provider = request.providerScope, reason = "Loading state")
            }
            val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            safeLog(
                status = "scanner_provider_complete",
                message = "attempt=$attemptId, step=$stepNumber, provider=${request.providerScope}, durationMs=$elapsedMs, label=$stepLabel"
            )
            result
        } catch (timeout: TimeoutCancellationException) {
            val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            val networkProbe = providerNetworkProbe(request.providerScope, request.searchMode)
            safeLog(
                status = "scanner_provider_timeout",
                message = "attempt=$attemptId, step=$stepNumber, provider=${request.providerScope}, timeoutMs=$networkStepTimeoutMs, elapsedMs=$elapsedMs, label=$stepLabel, network=$networkProbe"
            )
            val backoffMs = markProviderTimeout(provider = request.providerScope)
            if (backoffMs != null) {
                safeLog(
                    status = "scanner_provider_backoff",
                    message = "attempt=$attemptId, step=$stepNumber, provider=${request.providerScope}, backoffMs=$backoffMs, streak=${providerTimeoutStreak[request.providerScope] ?: 0}"
                )
            }
            AppResult.Error(
                message = "Timeout ${networkStepTimeoutMs / 1000}s for ${request.providerScope}",
                cause = timeout
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
            safeLog(
                status = "scanner_provider_exception",
                message = "attempt=$attemptId, step=$stepNumber, provider=${request.providerScope}, elapsedMs=$elapsedMs, label=$stepLabel, cause=${throwable.toLogSummary(maxDepth = 5)}"
            )
            AppResult.Error(
                message = throwable.message ?: throwable.javaClass.simpleName,
                cause = throwable
            )
        }
    }

    private fun markProviderSuccess(provider: ScannerProviderScope) {
        if (provider == ScannerProviderScope.ALL) return
        val now = System.currentTimeMillis()
        providerTimeoutStreak.remove(provider)
        providerCooldownUntilMs.remove(provider)
        val prev = providerHealthStats[provider] ?: ProviderHealthRuntime()
        providerHealthStats[provider] = prev.copy(
            successCount = prev.successCount + 1,
            timeoutStreak = 0,
            lastIssue = null,
            lastUpdatedAt = now
        )
        refreshProviderHealthSnapshot(nowMs = now)
    }

    private fun markProviderTimeout(provider: ScannerProviderScope): Long? {
        if (provider == ScannerProviderScope.ALL) return null
        val now = System.currentTimeMillis()
        val streak = (providerTimeoutStreak[provider] ?: 0) + 1
        providerTimeoutStreak[provider] = streak
        val prev = providerHealthStats[provider] ?: ProviderHealthRuntime()
        providerHealthStats[provider] = prev.copy(
            timeoutCount = prev.timeoutCount + 1,
            timeoutStreak = streak,
            lastIssue = "timeout",
            lastUpdatedAt = now
        )
        if (streak < PROVIDER_TIMEOUT_STREAK_TO_BACKOFF) return null

        val multiplier = (streak - PROVIDER_TIMEOUT_STREAK_TO_BACKOFF + 1).coerceAtLeast(1)
        val cooldownMs = (PROVIDER_TIMEOUT_BACKOFF_MS * multiplier)
            .coerceAtMost(PROVIDER_TIMEOUT_BACKOFF_MAX_MS)
        providerCooldownUntilMs[provider] = now + cooldownMs
        refreshProviderHealthSnapshot(nowMs = now)
        return cooldownMs
    }

    private fun markProviderError(
        provider: ScannerProviderScope,
        reason: String
    ) {
        if (provider == ScannerProviderScope.ALL) return
        val now = System.currentTimeMillis()
        val streak = providerTimeoutStreak[provider] ?: 0
        val prev = providerHealthStats[provider] ?: ProviderHealthRuntime()
        providerHealthStats[provider] = prev.copy(
            errorCount = prev.errorCount + 1,
            timeoutStreak = streak,
            lastIssue = reason.take(180),
            lastUpdatedAt = now
        )
        refreshProviderHealthSnapshot(nowMs = now)
    }

    private fun resetProviderHealthTracking() {
        providerTimeoutStreak.clear()
        providerCooldownUntilMs.clear()
        providerHealthStats.clear()
        refreshProviderHealthSnapshot()
    }

    private fun refreshProviderHealthSnapshot(nowMs: Long = System.currentTimeMillis()) {
        val items = listOf(
            ScannerProviderScope.GITHUB,
            ScannerProviderScope.GITLAB,
            ScannerProviderScope.BITBUCKET
        ).map { provider ->
            val runtime = providerHealthStats[provider] ?: ProviderHealthRuntime()
            ScannerProviderHealthUi(
                provider = provider,
                providerName = providerDisplayName(provider),
                score = providerHealthScore(provider = provider, nowMs = nowMs),
                successCount = runtime.successCount,
                timeoutCount = runtime.timeoutCount,
                errorCount = runtime.errorCount,
                timeoutStreak = runtime.timeoutStreak,
                cooldownRemainingSec = providerCooldownRemainingSec(provider = provider, nowMs = nowMs),
                lastIssue = runtime.lastIssue
            )
        }
        _uiState.update { it.copy(providerHealth = items) }
    }

    private fun providerHealthScore(
        provider: ScannerProviderScope,
        nowMs: Long
    ): Int {
        val runtime = providerHealthStats[provider] ?: ProviderHealthRuntime()
        val cooldownPenalty = if ((providerCooldownUntilMs[provider] ?: 0L) > nowMs) 25 else 0
        val raw = 70 +
            (runtime.successCount * 6) -
            (runtime.timeoutCount * 18) -
            (runtime.errorCount * 8) -
            (runtime.timeoutStreak * 10) -
            cooldownPenalty
        return raw.coerceIn(0, 100)
    }

    private fun providerCooldownRemainingSec(
        provider: ScannerProviderScope,
        nowMs: Long
    ): Long {
        val until = providerCooldownUntilMs[provider] ?: 0L
        return ((until - nowMs).coerceAtLeast(0L) / 1000L)
    }

    private suspend fun importFoundPlaylists(
        candidates: List<PlaylistCandidate>,
        onProgress: ((ImportProgress) -> Unit)? = null
    ): ImportSummary {
        if (candidates.isEmpty()) return ImportSummary()

        safeLog(
            status = "scanner_import_start",
            message = "candidates=${candidates.size}, targetSave=all"
        )
        onProgress?.invoke(
            ImportProgress(
                processed = 0,
                total = candidates.size,
                imported = 0,
                skipped = 0,
                failed = 0
            )
        )

        val existingSources = playlistRepository.observePlaylists()
            .first()
            .map { normalizeSource(it.source) }
            .toMutableSet()

        var imported = 0
        var skipped = 0
        var failed = 0
        var processed = 0
        var stoppedByUser = false
        val failureReasons = mutableListOf<String>()
        var cursor = 0
        while (cursor < candidates.size) {
            if (stopImportRequested) {
                stoppedByUser = true
                safeLog(
                    status = "scanner_import_stopped",
                    message = "processed=$processed/${candidates.size}, imported=$imported, skipped=$skipped, failed=$failed"
                )
                break
            }

            val batch = candidates.subList(cursor, minOf(cursor + IMPORT_BATCH_SIZE, candidates.size))
            cursor += batch.size

            var batchOffset = 0
            while (batchOffset < batch.size) {
                if (stopImportRequested) {
                    stoppedByUser = true
                    safeLog(
                        status = "scanner_import_stopped",
                        message = "processed=$processed/${candidates.size}, imported=$imported, skipped=$skipped, failed=$failed"
                    )
                    break
                }

                val group = batch.subList(batchOffset, minOf(batchOffset + IMPORT_PARALLELISM, batch.size))
                batchOffset += group.size

                val groupResults = MutableList<ImportItemResult?>(group.size) { null }
                coroutineScope {
                    val pending = mutableListOf<Pair<Int, kotlinx.coroutines.Deferred<ImportItemResult>>>()
                    group.forEachIndexed { idx, candidate ->
                        val sourceUrl = candidate.downloadUrl.trim()
                        if (sourceUrl.isBlank()) {
                            groupResults[idx] = ImportItemResult(
                                candidate = candidate,
                                sourceUrl = sourceUrl,
                                status = ImportItemStatus.FAILED,
                                reason = "${candidate.provider}/${candidate.repository}/${candidate.path}: empty download url"
                            )
                            return@forEachIndexed
                        }

                        val normalizedSource = normalizeSource(sourceUrl)
                        if (existingSources.contains(normalizedSource)) {
                            groupResults[idx] = ImportItemResult(
                                candidate = candidate,
                                sourceUrl = sourceUrl,
                                status = ImportItemStatus.SKIPPED,
                                reason = "duplicate"
                            )
                            return@forEachIndexed
                        }

                        existingSources += normalizedSource
                        val deferred = async {
                            val importResult = withTimeoutOrNull(IMPORT_ITEM_TIMEOUT_MS) {
                                playlistRepository.importFromUrl(
                                    sourceUrl,
                                    candidate.name.ifBlank { "Imported Playlist" }
                                )
                            }

                            when (importResult) {
                                null -> ImportItemResult(
                                    candidate = candidate,
                                    sourceUrl = sourceUrl,
                                    status = ImportItemStatus.FAILED,
                                    reason = buildFailureReason(
                                        candidate = candidate,
                                        reason = "Import timeout ${IMPORT_ITEM_TIMEOUT_MS / 1000}s"
                                    )
                                )
                                is AppResult.Success -> ImportItemResult(
                                    candidate = candidate,
                                    sourceUrl = sourceUrl,
                                    status = ImportItemStatus.IMPORTED,
                                    playlistId = importResult.data.playlistId
                                )
                                is AppResult.Error -> ImportItemResult(
                                    candidate = candidate,
                                    sourceUrl = sourceUrl,
                                    status = ImportItemStatus.FAILED,
                                    reason = buildFailureReason(
                                        candidate = candidate,
                                        reason = importResult.message
                                    )
                                )
                                AppResult.Loading -> ImportItemResult(
                                    candidate = candidate,
                                    sourceUrl = sourceUrl,
                                    status = ImportItemStatus.FAILED,
                                    reason = buildFailureReason(
                                        candidate = candidate,
                                        reason = "Loading state"
                                    )
                                )
                            }
                        }
                        pending += (idx to deferred)
                    }

                    pending.forEach { (idx, deferred) ->
                        groupResults[idx] = deferred.await()
                    }
                }

                groupResults.filterNotNull().forEach { item ->
                    processed += 1
                    when (item.status) {
                        ImportItemStatus.IMPORTED -> {
                            imported += 1
                            safeLog(
                                status = "scanner_import_ok",
                                message = "provider=${item.candidate.provider}, repo=${item.candidate.repository}, path=${item.candidate.path}, playlistId=${item.playlistId ?: "-"}, imported=$imported"
                            )
                        }
                        ImportItemStatus.SKIPPED -> {
                            skipped += 1
                            safeLog(
                                status = "scanner_import_skip_duplicate",
                                message = "provider=${item.candidate.provider}, repo=${item.candidate.repository}, path=${item.candidate.path}"
                            )
                        }
                        ImportItemStatus.FAILED -> {
                            failed += 1
                            val reason = item.reason ?: buildFailureReason(
                                candidate = item.candidate,
                                reason = "unknown"
                            )
                            failureReasons += reason
                            safeLog(
                                status = "scanner_import_error",
                                message = "provider=${item.candidate.provider}, repo=${item.candidate.repository}, path=${item.candidate.path}, reason=$reason"
                            )
                        }
                    }

                    val currentText = when (item.status) {
                        ImportItemStatus.SKIPPED -> "${item.candidate.name.ifBlank { item.candidate.path }} | duplicate"
                        else -> "${item.candidate.name.ifBlank { item.candidate.path }} | ${item.sourceUrl.take(140)}"
                    }
                    onProgress?.invoke(
                        ImportProgress(
                            processed = processed,
                            total = candidates.size,
                            imported = imported,
                            skipped = skipped,
                            failed = failed,
                            current = currentText
                        )
                    )

                    if (processed % IMPORT_PROGRESS_LOG_EVERY == 0 || processed == candidates.size) {
                        safeLog(
                            status = "scanner_import_progress",
                            message = "processed=$processed/${candidates.size}, imported=$imported, skipped=$skipped, failed=$failed"
                        )
                    }
                }
            }
            if (stoppedByUser) {
                break
            }
        }

        safeLog(
            status = "scanner_import_finish",
            message = "processed=$processed/${candidates.size}, imported=$imported, skipped=$skipped, failed=$failed, stopped=$stoppedByUser"
        )

        return ImportSummary(
            imported = imported,
            skipped = skipped,
            failed = failed,
            failureReasons = failureReasons.take(MAX_IMPORT_FAILURE_DETAILS),
            stoppedByUser = stoppedByUser
        )
    }

    private fun setStatus(
        type: ScannerStatusType,
        title: String,
        details: String,
        isLoading: Boolean,
        hasSearched: Boolean
    ) {
        _uiState.update {
            it.copy(
                isLoading = isLoading,
                hasSearched = hasSearched,
                statusType = type,
                statusTitle = title,
                statusDetails = details
            )
        }
    }

    private fun validateInput(state: ScannerUiState): String? {
        if (state.query.isBlank()) {
            return "Поле \"Поисковый запрос\" обязательно."
        }

        if (state.updatedDaysBack.isNotBlank() && state.updatedDaysBack.toLongOrNull() == null) {
            return "Поле \"Дней назад\" должно быть числом."
        }

        if (state.minSizeBytes.isNotBlank() && state.minSizeBytes.toLongOrNull() == null) {
            return "Поле \"Min size bytes\" должно быть числом."
        }

        if (state.maxSizeBytes.isNotBlank() && state.maxSizeBytes.toLongOrNull() == null) {
            return "Поле \"Max size bytes\" должно быть числом."
        }

        val minSize = state.minSizeBytes.toLongOrNull()
        val maxSize = state.maxSizeBytes.toLongOrNull()
        if (minSize != null && maxSize != null && minSize > maxSize) {
            return "Min size должен быть меньше или равен Max size."
        }

        if (state.selectedProvider == ScannerProviderScope.BITBUCKET && state.repoFilter.isBlank()) {
            return "Для Bitbucket заполните \"Фильтр repo\": workspace или workspace/repo."
        }

        return null
    }

    private suspend fun runNetworkPreflight(mode: ScannerSearchMode): NetworkPreflight {
        val apiTargets = listOf("api.github.com", "gitlab.com")
        val webTargets = listOf("duckduckgo.com", "www.bing.com")

        val apiDetails = if (mode == ScannerSearchMode.SEARCH_ENGINE) {
            emptyList()
        } else {
            probeHosts(apiTargets)
        }
        val webDetails = if (mode == ScannerSearchMode.DIRECT_API) {
            emptyList()
        } else {
            probeHosts(webTargets)
        }

        val apiReachable = if (apiDetails.isEmpty()) {
            false
        } else {
            apiDetails.any { it.contains("=ok(", ignoreCase = true) }
        }
        val webReachable = if (webDetails.isEmpty()) {
            false
        } else {
            webDetails.any { it.contains("=ok(", ignoreCase = true) }
        }

        val details = buildString {
            if (apiDetails.isNotEmpty()) {
                append("api=[")
                append(apiDetails.joinToString(" ; "))
                append("]")
            } else {
                append("api=[skipped]")
            }
            append(" | ")
            if (webDetails.isNotEmpty()) {
                append("web=[")
                append(webDetails.joinToString(" ; "))
                append("]")
            } else {
                append("web=[skipped]")
            }
        }.take(1500)

        return NetworkPreflight(
            apiReachable = apiReachable,
            webReachable = webReachable,
            details = details
        )
    }

    private fun shouldAbortByPreflight(
        mode: ScannerSearchMode,
        preflight: NetworkPreflight
    ): Boolean {
        return when (mode) {
            ScannerSearchMode.DIRECT_API -> !preflight.apiReachable
            ScannerSearchMode.SEARCH_ENGINE -> !preflight.webReachable
            ScannerSearchMode.AUTO -> !preflight.apiReachable && !preflight.webReachable
        }
    }

    private fun preflightAbortMessage(
        mode: ScannerSearchMode,
        preflight: NetworkPreflight
    ): String {
        val modeText = when (mode) {
            ScannerSearchMode.DIRECT_API -> "Direct API"
            ScannerSearchMode.SEARCH_ENGINE -> "Search Engine"
            ScannerSearchMode.AUTO -> "Auto"
        }
        return buildString {
            append("Режим: ")
            append(modeText)
            append(". DNS/API недоступны. ")
            append("Проверьте интернет/DNS/прокси и повторите. ")
            append("Детали: ")
            append(preflight.details)
        }.take(1800)
    }

    private fun emptyResultsHint(state: ScannerUiState): String {
        if (state.selectedSearchMode == ScannerSearchMode.DIRECT_API) {
            return "Прямой API-режим не дал результатов. Попробуйте режим Search Engine или Auto."
        }
        if (state.selectedSearchMode == ScannerSearchMode.SEARCH_ENGINE) {
            return "Поисковики не нашли валидные M3U. Попробуйте упростить запрос или переключиться на Auto."
        }
        val queryLower = state.query.lowercase()
        if (isRussianIntent(queryLower)) {
            return "Для русских каналов: используйте пресет \"Русские каналы\" и оставьте только источник GitHub или ALL."
        }
        if (isWorldIntent(queryLower)) {
            return "Для каналов мира: используйте пресет \"Каналы мира\" и отключите дополнительные фильтры."
        }
        return if (state.selectedProvider == ScannerProviderScope.BITBUCKET) {
            "Для Bitbucket проверьте поле \"Фильтр repo\" (workspace или workspace/repo)."
        } else {
            "Упростите запрос: оставьте только \"iptv\" и отключите дополнительные фильтры."
        }
    }

    private fun normalizeSource(source: String): String = source.trim().lowercase()

    private fun buildResultSummary(
        results: List<PlaylistCandidate>,
        importSummary: ImportSummary?
    ): String {
        val byProvider = results.groupingBy { it.provider }.eachCount()
        val github = byProvider["github"] ?: 0
        val gitlab = byProvider["gitlab"] ?: 0
        val bitbucket = byProvider["bitbucket"] ?: 0
        val web = byProvider["web"] ?: 0
        val foundMessage = "Найдено ${results.size} (GitHub=$github, GitLab=$gitlab, Bitbucket=$bitbucket, Web=$web)"
        val importMessage = importSummary?.let {
            val base = " | сохранено=${it.imported}, пропущено=${it.skipped}, ошибок=${it.failed}"
            val reason = it.failureReasons.firstOrNull()?.let { first -> " | причина: $first" }.orEmpty()
            val stopTail = if (it.stoppedByUser) " | сохранение остановлено пользователем" else ""
            base + reason + stopTail
        }.orEmpty()
        return foundMessage + importMessage
    }

    private fun providerDisplayName(provider: ScannerProviderScope): String {
        return when (provider) {
            ScannerProviderScope.ALL -> "Все источники"
            ScannerProviderScope.GITHUB -> "GitHub"
            ScannerProviderScope.GITLAB -> "GitLab"
            ScannerProviderScope.BITBUCKET -> "Bitbucket"
        }
    }

    private fun providerInputHint(provider: ScannerProviderScope): String {
        return when (provider) {
            ScannerProviderScope.BITBUCKET -> "Для Bitbucket заполните \"Фильтр repo\": workspace или workspace/repo."
            ScannerProviderScope.GITHUB -> "Для GitHub поле \"Фильтр repo\" опционально: owner/repo."
            ScannerProviderScope.GITLAB -> "Для GitLab поле \"Фильтр repo\" опционально: group/project."
            ScannerProviderScope.ALL -> "Можно оставить фильтры пустыми и искать по всем источникам."
        }
    }

    private fun searchModeDisplayName(mode: ScannerSearchMode): String {
        return when (mode) {
            ScannerSearchMode.AUTO -> "Auto"
            ScannerSearchMode.DIRECT_API -> "Direct API"
            ScannerSearchMode.SEARCH_ENGINE -> "Search Engine"
        }
    }

    private fun searchModeInputHint(mode: ScannerSearchMode): String {
        return when (mode) {
            ScannerSearchMode.AUTO -> "Auto: сначала API, при пустом результате fallback через поисковики."
            ScannerSearchMode.DIRECT_API -> "Direct API: только прямой доступ к GitHub/GitLab/Bitbucket API."
            ScannerSearchMode.SEARCH_ENGINE -> "Search Engine: поиск через DuckDuckGo/Bing с извлечением ссылок."
        }
    }

    private fun buildSearchHint(provider: ScannerProviderScope, mode: ScannerSearchMode): String {
        return "${providerInputHint(provider)} | ${searchModeInputHint(mode)}"
    }

    private fun mapScannerError(raw: String): String {
        val message = raw.trim()
        return when {
            message.contains("Unable to create converter", ignoreCase = true) ->
                "Внутренняя ошибка сетевого слоя (Retrofit/Moshi). Обновите APK до последней сборки"
            message.contains("UnknownHostException", ignoreCase = true) ||
                message.contains("Unable to resolve host", ignoreCase = true) ->
                "Не удалось определить DNS-адрес сервера (проверьте интернет/DNS)"
            message.contains("SSLHandshakeException", ignoreCase = true) ||
                message.contains("SSLPeerUnverifiedException", ignoreCase = true) ->
                "Ошибка TLS/SSL при подключении к API"
            message.contains("Network IO", ignoreCase = true) ->
                "Приложение не может получить доступ к сети"
            message.contains("SocketTimeoutException", ignoreCase = true) ->
                "Сетевой таймаут (сервер не ответил вовремя)"
            message.contains("HTTP 401", ignoreCase = true) ->
                "Источник ограничил доступ к API (HTTP 401)"
            message.contains("HTTP 403", ignoreCase = true) ->
                "Достигнут лимит API или доступ запрещен (HTTP 403)"
            message.contains("HTTP 429", ignoreCase = true) ->
                "Превышен лимит запросов API (HTTP 429)"
            message.contains("timeout", ignoreCase = true) ->
                "Таймаут сетевого запроса"
            else -> message
        }
    }

    private fun selectPrimaryError(errors: List<String>): String {
        if (errors.isEmpty()) return ""
        val priority = listOf(
            "UnknownHostException",
            "Unable to resolve host",
            "Network IO",
            "timeout",
            "SocketTimeoutException",
            "HTTP 429",
            "HTTP 403",
            "HTTP 401"
        )
        priority.forEach { marker ->
            errors.firstOrNull { it.contains(marker, ignoreCase = true) }?.let { return it }
        }
        return errors.first()
    }

    private fun isFatalNetworkError(message: String): Boolean {
        val lowered = message.lowercase()
        return lowered.contains("unknownhostexception") ||
            lowered.contains("unable to resolve host") ||
            lowered.contains("network io") ||
            lowered.contains("timeout")
    }

    private fun isDnsError(message: String): Boolean {
        val lowered = message.lowercase()
        return lowered.contains("unknownhostexception") ||
            lowered.contains("unable to resolve host")
    }

    private fun detectNoResultsReason(errors: List<String>): String {
        if (errors.isEmpty()) return "empty_response_without_errors"
        val joined = errors.joinToString(" | ").lowercase()
        return when {
            joined.contains("unknownhostexception") || joined.contains("unable to resolve host") ->
                "dns_unavailable"
            joined.contains("timeout") || joined.contains("timed out") ->
                "network_timeout"
            joined.contains("http 403") || joined.contains("forbidden") ->
                "http_forbidden"
            joined.contains("http 429") || joined.contains("rate") ->
                "rate_limit"
            joined.contains("stopped") ->
                "stopped_by_user"
            else -> "provider_error_or_no_matches"
        }
    }

    private suspend fun providerNetworkProbe(
        provider: ScannerProviderScope,
        mode: ScannerSearchMode
    ): String {
        val hosts = when (provider) {
            ScannerProviderScope.GITHUB -> {
                if (mode == ScannerSearchMode.SEARCH_ENGINE) listOf("duckduckgo.com", "www.bing.com")
                else listOf("api.github.com")
            }
            ScannerProviderScope.GITLAB -> {
                if (mode == ScannerSearchMode.SEARCH_ENGINE) listOf("duckduckgo.com", "www.bing.com")
                else listOf("gitlab.com")
            }
            ScannerProviderScope.BITBUCKET -> {
                if (mode == ScannerSearchMode.SEARCH_ENGINE) listOf("duckduckgo.com", "www.bing.com")
                else listOf("api.bitbucket.org")
            }
            ScannerProviderScope.ALL -> {
                if (mode == ScannerSearchMode.SEARCH_ENGINE) {
                    listOf("duckduckgo.com", "www.bing.com")
                } else {
                    listOf("api.github.com", "gitlab.com", "api.bitbucket.org")
                }
            }
        }
        return probeHosts(hosts).joinToString(" ; ")
    }

    private suspend fun probeHosts(hosts: List<String>): List<String> {
        val results = mutableListOf<String>()
        hosts.forEach { host ->
            results += probeHost(host)
        }
        return results
    }

    private suspend fun probeHost(host: String): String {
        val now = System.currentTimeMillis()
        probeCache[host]?.let { cached ->
            if (now - cached.ts <= NETWORK_PROBE_CACHE_MS) {
                return cached.value
            }
        }

        val value = withContext(Dispatchers.IO) {
            runCatching {
                val resolved = withTimeoutOrNull(NETWORK_PROBE_TIMEOUT_MS) {
                    runInterruptible {
                        InetAddress.getAllByName(host)
                            .mapNotNull { it.hostAddress }
                            .distinct()
                            .take(2)
                    }
                }

                when {
                    resolved == null -> "$host=probe_timeout(${NETWORK_PROBE_TIMEOUT_MS}ms)"
                    resolved.isEmpty() -> "$host=resolved(empty)"
                    else -> "$host=ok(${resolved.joinToString(",")})"
                }
            }.getOrElse { throwable ->
                val reason = "${throwable.javaClass.simpleName}:${throwable.message.orEmpty()}"
                    .replace('\n', ' ')
                    .take(180)
                "$host=err($reason)"
            }
        }

        probeCache[host] = ProbeCacheEntry(ts = now, value = value)
        return value
    }

    private fun applyScannerProxySettings(settings: ScannerProxySettings): String {
        if (!settings.enabled) {
            clearProxyProperties()
            return "off"
        }

        val host = settings.host.trim()
        val port = settings.port ?: 0
        if (host.isBlank() || port !in 1..65535) {
            clearProxyProperties()
            return "invalid(off)"
        }

        System.setProperty(PROXY_HTTP_HOST, host)
        System.setProperty(PROXY_HTTPS_HOST, host)
        System.setProperty(PROXY_HTTP_PORT, port.toString())
        System.setProperty(PROXY_HTTPS_PORT, port.toString())

        val user = settings.username.trim()
        if (user.isNotBlank()) {
            System.setProperty(PROXY_SCANNER_USER, user)
            System.setProperty(PROXY_SCANNER_PASS, settings.password)
        } else {
            System.clearProperty(PROXY_SCANNER_USER)
            System.clearProperty(PROXY_SCANNER_PASS)
        }

        val auth = if (user.isBlank()) "no-auth" else "auth:${user.take(1)}***"
        return "$host:$port,$auth"
    }

    private fun clearProxyProperties() {
        System.clearProperty(PROXY_HTTP_HOST)
        System.clearProperty(PROXY_HTTPS_HOST)
        System.clearProperty(PROXY_HTTP_PORT)
        System.clearProperty(PROXY_HTTPS_PORT)
        System.clearProperty(PROXY_SCANNER_USER)
        System.clearProperty(PROXY_SCANNER_PASS)
    }

    private fun ScannerUiState.toRequest(): ScannerSearchRequest {
        val daysBack = updatedDaysBack.toLongOrNull()
        val updatedAfter = daysBack?.let { System.currentTimeMillis() - TimeUnit.DAYS.toMillis(it) }
        val normalizedQuery = buildNormalizedQuery(query)
        val manualKeywords = splitKeywords(keywords)
        val boostedQuery = applyIntentBoost(normalizedQuery)

        return ScannerSearchRequest(
            query = boostedQuery,
            keywords = manualKeywords,
            providerScope = selectedProvider,
            searchMode = selectedSearchMode,
            repoFilter = repoFilter.ifBlank { null },
            pathFilter = pathFilter.ifBlank { null },
            updatedAfterEpochMs = updatedAfter,
            minSizeBytes = minSizeBytes.toLongOrNull(),
            maxSizeBytes = maxSizeBytes.toLongOrNull(),
            limit = SEARCH_SAVE_FETCH_TARGET
        )
    }

    private suspend fun applyResultPostFilter(
        attemptId: Long,
        state: ScannerUiState,
        candidates: List<PlaylistCandidate>,
        stage: String
    ): CandidateRefineOutcome {
        if (candidates.isEmpty()) return CandidateRefineOutcome(emptyList(), 0, 0, 0)

        val now = System.currentTimeMillis()
        val localDays = state.updatedDaysBack.toLongOrNull()?.coerceAtLeast(0L)
        val localUpdatedAfter = localDays?.let { now - TimeUnit.DAYS.toMillis(it) }

        val withMeta = candidates.map { candidate ->
            CandidateWithEpoch(candidate = candidate, updatedEpochMs = parseEpochOrNull(candidate.updatedAt))
        }

        var invalidUrlDropped = 0
        val validUrlOnly = withMeta.filter { item ->
            val url = item.candidate.downloadUrl.trim()
            val keep = url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true)
            if (!keep) invalidUrlDropped += 1
            keep
        }

        var staleDropped = 0
        val filtered = validUrlOnly.filter { item ->
            val keep = localUpdatedAfter == null || item.updatedEpochMs == null || item.updatedEpochMs >= localUpdatedAfter
            if (!keep) staleDropped += 1
            keep
        }
        val unknownUpdated = filtered.count { it.updatedEpochMs == null }

        val sorted = filtered.sortedWith(
            compareByDescending<CandidateWithEpoch> { it.updatedEpochMs != null }
                .thenByDescending { it.updatedEpochMs ?: 0L }
                .thenByDescending { it.candidate.sizeBytes ?: -1L }
                .thenBy { it.candidate.provider.lowercase() }
                .thenBy { it.candidate.repository.lowercase() }
                .thenBy { it.candidate.path.lowercase() }
        )

        safeLog(
            status = "scanner_result_refined",
            message = "attempt=$attemptId, stage=$stage, in=${candidates.size}, out=${sorted.size}, invalidUrlDropped=$invalidUrlDropped, staleDropped=$staleDropped, unknownUpdated=$unknownUpdated, daysBack=${state.updatedDaysBack.ifBlank { "-" }}"
        )
        return CandidateRefineOutcome(
            candidates = sorted.map { it.candidate },
            invalidUrlDropped = invalidUrlDropped,
            staleDropped = staleDropped,
            unknownUpdated = unknownUpdated
        )
    }

    private fun buildNormalizedQuery(raw: String): String {
        val compact = raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .trim(',', '.', ';')
        if (compact.isBlank()) return compact

        val lowered = compact.lowercase()
        val hasCoreTerm = lowered.contains("iptv") || lowered.contains("m3u")
        return if (hasCoreTerm) compact else "iptv $compact"
    }

    private fun splitKeywords(raw: String): List<String> {
        return raw
            .split(',', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseEpochOrNull(raw: String): Long? {
        if (raw.isBlank()) return null
        parseIsoEpoch(raw)?.let { return it }
        return raw.toLongOrNull()?.let { epoch ->
            if (epoch < 10_000_000_000L) epoch * 1000 else epoch
        }
    }

    private fun parseIsoEpoch(raw: String): Long? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val parsed = runCatching { parser.parse(raw)?.time }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun inferIntentKeywords(query: String, manualKeywords: List<String>): List<String> {
        return localAiAssistant.inferIntentKeywords(
            query = query,
            manualKeywords = manualKeywords
        )
    }

    private fun boostQueryWithKeywords(query: String, keywords: List<String>): String {
        if (keywords.isEmpty()) return query
        val lowered = query.lowercase()
        val additions = keywords
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { lowered.contains(it.lowercase()) }
            .take(3)
        if (additions.isEmpty()) return query
        return (listOf(query) + additions).joinToString(" ").trim()
    }

    private fun mergeKeywords(primary: List<String>, secondary: List<String>): List<String> {
        return (primary + secondary)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    private fun buildAiQueryVariants(
        query: String,
        keywords: List<String>,
        intentKeywords: List<String>
    ): List<String> {
        val expandedKeywords = mergeKeywords(keywords, intentKeywords)
        return localAiAssistant.buildAiVariants(
            query = query,
            manualKeywords = expandedKeywords,
            inferredKeywords = intentKeywords
        ).take(AI_MAX_QUERY_VARIANTS)
    }

    private fun buildLearnedQueryVariants(
        baseQuery: String,
        presetId: String?,
        intentKeywords: List<String>
    ): List<String> {
        val normalizedBase = buildNormalizedQuery(baseQuery).lowercase()
        val baseTokens = normalizedBase
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
        val intentSet = intentKeywords.map { it.lowercase() }.toSet()
        val normalizedPreset = presetId?.trim()?.ifBlank { null }

        return learnedQueryTemplates
            .asSequence()
            .mapNotNull { entry ->
                val normalizedCandidate = buildNormalizedQuery(entry.query)
                if (normalizedCandidate.isBlank()) return@mapNotNull null
                if (normalizedCandidate.lowercase() == normalizedBase) return@mapNotNull null

                val candidateTokens = normalizedCandidate.lowercase()
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .map { it.trim() }
                    .filter { it.length >= 2 }
                    .toSet()

                val overlap = candidateTokens.intersect(baseTokens).size
                val intentOverlap = candidateTokens.intersect(intentSet).size
                val presetBoost = if (normalizedPreset != null && entry.presetId == normalizedPreset) 4 else 0
                val recencyBoost = (entry.lastSuccessAt / 86_400_000L).coerceAtLeast(0L).toInt() % 3
                val score = (entry.hits * 3) + (overlap * 4) + (intentOverlap * 3) + presetBoost + recencyBoost
                if (score <= 0) return@mapNotNull null
                LearnedQueryCandidate(query = normalizedCandidate, score = score, hits = entry.hits, lastSuccessAt = entry.lastSuccessAt)
            }
            .sortedWith(
                compareByDescending<LearnedQueryCandidate> { it.score }
                    .thenByDescending { it.hits }
                    .thenByDescending { it.lastSuccessAt }
            )
            .map { it.query }
            .distinctBy { it.lowercase() }
            .take(MAX_LEARNED_QUERY_VARIANTS)
            .toList()
    }

    private fun broadFallbackQuery(query: String): String {
        val lowered = query.lowercase()
        return when {
            isRussianIntent(lowered) -> "russian iptv m3u m3u8"
            isWorldIntent(lowered) -> "world iptv m3u m3u8"
            lowered.contains("sport") || lowered.contains("спорт") -> "sport iptv m3u m3u8"
            lowered.contains("movie") || lowered.contains("film") || lowered.contains("кино") || lowered.contains("фильм") ->
                "movie iptv m3u m3u8"
            else -> "iptv m3u m3u8"
        }
    }

    private fun applyIntentBoost(query: String): String {
        val lowered = query.lowercase()
        val boosts = mutableListOf<String>()

        if (isRussianIntent(lowered)) {
            boosts += "russian"
        }
        if (isWorldIntent(lowered)) {
            boosts += "world"
        }
        if (lowered.contains("sport") || lowered.contains("спорт")) {
            boosts += "sport"
        }
        if (lowered.contains("movie") || lowered.contains("film") || lowered.contains("кино") || lowered.contains("фильм")) {
            boosts += "movie"
        }

        if (boosts.isEmpty()) return query
        val missingBoosts = boosts.distinct().filterNot { lowered.contains(it) }
        if (missingBoosts.isEmpty()) return query
        return (listOf(query) + missingBoosts).joinToString(" ").trim()
    }

    private fun isRussianIntent(lowered: String): Boolean {
        return lowered.contains("рус") || lowered.contains("росс") ||
            lowered.contains("russian") || lowered.contains("russia")
    }

    private fun isWorldIntent(lowered: String): Boolean {
        return lowered.contains("мир") || lowered.contains("world") ||
            lowered.contains("global") || lowered.contains("international")
    }

    private fun ScannerSearchRequest.key(): String {
        return listOf(
            query.lowercase(),
            providerScope.name,
            searchMode.name,
            repoFilter.orEmpty().lowercase(),
            pathFilter.orEmpty().lowercase(),
            updatedAfterEpochMs?.toString().orEmpty(),
            minSizeBytes?.toString().orEmpty(),
            maxSizeBytes?.toString().orEmpty(),
            keywords.joinToString(",") { it.lowercase() }
        ).joinToString("|")
    }

    private data class SearchPlanStep(
        val label: String,
        val request: ScannerSearchRequest
    )

    private data class ProviderExecutionResult(
        val provider: ScannerProviderScope,
        val result: AppResult<List<PlaylistCandidate>>,
        val providerName: String
    )

    private data class CandidateWithEpoch(
        val candidate: PlaylistCandidate,
        val updatedEpochMs: Long?
    )

    private data class LearnedQueryCandidate(
        val query: String,
        val score: Int,
        val hits: Int,
        val lastSuccessAt: Long
    )

    private data class ProviderHealthRuntime(
        val successCount: Int = 0,
        val timeoutCount: Int = 0,
        val errorCount: Int = 0,
        val timeoutStreak: Int = 0,
        val lastIssue: String? = null,
        val lastUpdatedAt: Long = 0L
    )

    private data class SearchPlanOutcome(
        val candidates: List<PlaylistCandidate>,
        val errors: List<String>,
        val successfulStepQueries: List<String>,
        val timedOut: Boolean = false
    )

    private data class ImportSummary(
        val imported: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val failureReasons: List<String> = emptyList(),
        val stoppedByUser: Boolean = false
    )

    private data class CandidateRefineOutcome(
        val candidates: List<PlaylistCandidate>,
        val invalidUrlDropped: Int,
        val staleDropped: Int,
        val unknownUpdated: Int
    )

    private data class ImportProgress(
        val processed: Int,
        val total: Int,
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val current: String? = null
    )

    private data class ImportItemResult(
        val candidate: PlaylistCandidate,
        val sourceUrl: String,
        val status: ImportItemStatus,
        val reason: String? = null,
        val playlistId: Long? = null
    )

    private enum class ImportItemStatus {
        IMPORTED,
        SKIPPED,
        FAILED
    }

    private data class NetworkPreflight(
        val apiReachable: Boolean,
        val webReachable: Boolean,
        val details: String
    )

    private companion object {
        const val SEARCH_DISPLAY_LIMIT = 120
        const val SEARCH_SAVE_FETCH_TARGET = Int.MAX_VALUE
        const val SEARCH_MAX_RUNTIME_MS = 5 * 60 * 1000L
        const val SEARCH_WATCHDOG_MS = 8_000L
        const val STEP_PULSE_MS = 3_000L
        const val NETWORK_STEP_TIMEOUT_MS = 25_000L
        const val NETWORK_STEP_TIMEOUT_DEGRADED_MS = 12_000L
        const val STEP_HARD_TIMEOUT_MS = 30_000L
        const val STEP_HARD_TIMEOUT_DEGRADED_MS = 16_000L
        const val FAIL_FAST_MAX_STEP = 2
        const val MAX_PLAN_STEPS = 6
        const val MAX_PLAN_STEPS_AI = 14
        const val PREFLIGHT_DEGRADED_PLAN_STEPS = 2
        const val AI_MAX_QUERY_VARIANTS = 8
        const val MAX_LEARNED_QUERY_VARIANTS = 4
        const val MAX_KEYWORDS = 10
        const val MAX_LOG_MESSAGE = 1200
        const val NETWORK_PROBE_TIMEOUT_MS = 1_500L
        const val NETWORK_PROBE_CACHE_MS = 30_000L
        const val PROVIDER_TIMEOUT_STREAK_TO_BACKOFF = 2
        const val PROVIDER_TIMEOUT_BACKOFF_MS = 25_000L
        const val PROVIDER_TIMEOUT_BACKOFF_MAX_MS = 90_000L
        const val MAX_IMPORT_FAILURE_DETAILS = 5
        const val IMPORT_PROGRESS_LOG_EVERY = 5
        const val IMPORT_PARALLELISM = 4
        const val IMPORT_BATCH_SIZE = 12
        const val IMPORT_ITEM_TIMEOUT_MS = 25_000L
        const val PROXY_HTTP_HOST = "http.proxyHost"
        const val PROXY_HTTP_PORT = "http.proxyPort"
        const val PROXY_HTTPS_HOST = "https.proxyHost"
        const val PROXY_HTTPS_PORT = "https.proxyPort"
        const val PROXY_SCANNER_USER = "myscaner.proxy.user"
        const val PROXY_SCANNER_PASS = "myscaner.proxy.pass"
    }
}

private data class ProbeCacheEntry(
    val ts: Long,
    val value: String
)

private fun defaultProviderHealthUi(): List<ScannerProviderHealthUi> {
    return listOf(
        ScannerProviderHealthUi(
            provider = ScannerProviderScope.GITHUB,
            providerName = "GitHub",
            score = 70,
            successCount = 0,
            timeoutCount = 0,
            errorCount = 0,
            timeoutStreak = 0,
            cooldownRemainingSec = 0
        ),
        ScannerProviderHealthUi(
            provider = ScannerProviderScope.GITLAB,
            providerName = "GitLab",
            score = 70,
            successCount = 0,
            timeoutCount = 0,
            errorCount = 0,
            timeoutStreak = 0,
            cooldownRemainingSec = 0
        ),
        ScannerProviderHealthUi(
            provider = ScannerProviderScope.BITBUCKET,
            providerName = "Bitbucket",
            score = 70,
            successCount = 0,
            timeoutCount = 0,
            errorCount = 0,
            timeoutStreak = 0,
            cooldownRemainingSec = 0
        )
    )
}

private fun buildFailureReason(
    candidate: PlaylistCandidate,
    reason: String
): String {
    val rawReason = reason.trim()
    val mappedReason = when {
        rawReason.contains("Missing #EXTM3U header", ignoreCase = true) ->
            "файл не похож на M3U (#EXTM3U отсутствует)"
        rawReason.contains("No valid channels found", ignoreCase = true) ->
            "файл не содержит валидных каналов"
        rawReason.contains("HTTP ", ignoreCase = true) ->
            rawReason
        else -> rawReason
    }
    return "${candidate.provider}/${candidate.repository}/${candidate.path}: $mappedReason"
}

private fun String.toSafeFilePart(): String {
    return trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(60)
        .ifBlank { "search" }
}

private fun scannerPresets(): List<ScannerPreset> {
    return listOf(
        ScannerPreset(
            id = "general",
            title = "Общий IPTV",
            query = "iptv playlist m3u",
            keywords = "iptv, playlist, tv channels, m3u, m3u8, channel list, список каналов",
            provider = ScannerProviderScope.ALL,
            description = "Базовый поиск RU/EN IPTV-плейлистов (широкий старт)."
        ),
        ScannerPreset(
            id = "ru",
            title = "Русские каналы",
            query = "russian iptv playlist",
            keywords = "russian, russia, ru, русские каналы, россия, список каналов, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Поиск RU-каналов и русскоязычных IPTV-списков."
        ),
        ScannerPreset(
            id = "world",
            title = "Каналы мира",
            query = "world international iptv",
            keywords = "world, global, international, countries, tv channels, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Поиск международных и мульти-страночных IPTV списков."
        ),
        ScannerPreset(
            id = "sport",
            title = "Спорт",
            query = "sport iptv channels",
            keywords = "sport, sports, football, soccer, hockey, tennis, basketball, спорт, футбол, хоккей, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Поиск спортивных плейлистов (футбол, хоккей и др.)."
        ),
        ScannerPreset(
            id = "movies",
            title = "Фильмы/Сериалы",
            query = "movie iptv",
            keywords = "movie, series, serial, cinema, vod, action, thriller, horror, кино, сериалы, боевик, триллер, ужасы",
            provider = ScannerProviderScope.ALL,
            description = "Поиск плейлистов с фильмами, сериалами и VOD-каталогами."
        ),
        ScannerPreset(
            id = "series",
            title = "Сериалы/TV Shows",
            query = "series iptv",
            keywords = "series, serial, tv show, drama, seasons, episodes, сериалы, сериал, шоу, drama channels, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Отдельный пресет для сериалов и TV-шоу (RU/EN)."
        ),
        ScannerPreset(
            id = "genres_action",
            title = "Экшен/Триллер/Ужасы",
            query = "action thriller horror iptv",
            keywords = "action, thriller, horror, movie channels, боевик, триллер, ужасы, фильмы, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Жанровый поиск кино-каналов: action, thriller, horror."
        ),
        ScannerPreset(
            id = "news",
            title = "Новости",
            query = "news iptv",
            keywords = "news, live, breaking, headlines, новости, новостные",
            provider = ScannerProviderScope.ALL,
            description = "Поиск новостных каналов и live-news плейлистов."
        ),
        ScannerPreset(
            id = "music",
            title = "Музыка/Радио",
            query = "music iptv",
            keywords = "music, radio, audio, hits, музыка, радио",
            provider = ScannerProviderScope.ALL,
            description = "Поиск музыкальных и радио IPTV списков."
        ),
        ScannerPreset(
            id = "kids",
            title = "Мультфильмы/Детские",
            query = "kids iptv",
            keywords = "kids, cartoon, animation, family, мультфильмы, детские, kids channels",
            provider = ScannerProviderScope.ALL,
            description = "Поиск детских и анимационных каналов."
        ),
        ScannerPreset(
            id = "tvlists",
            title = "Списки ТВ каналов",
            query = "iptv channel list m3u m3u8",
            keywords = "tv channels, iptv channel list, списки тв, каналы iptv, списки каналов iptv, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Широкий поиск списков каналов в форматах M3U/M3U8."
        ),
        ScannerPreset(
            id = "mixed_ru_en",
            title = "RU/EN Готовые запросы",
            query = "iptv channels list",
            keywords = "iptv channel list, tv list, live channels, список каналов, списки тв каналов, m3u, m3u8, playlist",
            provider = ScannerProviderScope.ALL,
            description = "Универсальный двуязычный пресет для поиска максимума списков."
        ),
        ScannerPreset(
            id = "free_public",
            title = "Бесплатные/Публичные",
            query = "free public iptv",
            keywords = "free, public, open, free tv channels, бесплатные каналы, открытые каналы, public playlist, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Поиск открытых и бесплатных IPTV-плейлистов."
        ),
        ScannerPreset(
            id = "documentary",
            title = "Документальные",
            query = "documentary iptv",
            keywords = "documentary, history, discovery, nature, science, документальные, познавательные, наука, история, природа",
            provider = ScannerProviderScope.ALL,
            description = "Поиск познавательных и документальных каналов."
        ),
        ScannerPreset(
            id = "regional",
            title = "Региональные",
            query = "regional local iptv",
            keywords = "regional, local tv, city channels, country channels, региональные, местные каналы, city tv, local channels",
            provider = ScannerProviderScope.ALL,
            description = "Поиск региональных и локальных плейлистов."
        ),
        ScannerPreset(
            id = "religion",
            title = "Религия/Духовные",
            query = "religious iptv",
            keywords = "religion, islamic channels, christian channels, religious, религиозные, исламские каналы, духовные",
            provider = ScannerProviderScope.ALL,
            description = "Поиск религиозных и духовных каналов."
        ),
        ScannerPreset(
            id = "uhd",
            title = "HD/4K",
            query = "iptv hd 4k",
            keywords = "hd, full hd, 4k, uhd, fhd, high quality, высокое качество, 4k каналы",
            provider = ScannerProviderScope.ALL,
            description = "Поиск HD/FHD/UHD каналов и качественных потоков."
        ),
        ScannerPreset(
            id = "voxlist",
            title = "Voxlist/Repo",
            query = "voxlist iptv",
            keywords = "voxlist, iptv, channel list, m3u, m3u8, github, gitlab, список каналов",
            provider = ScannerProviderScope.ALL,
            description = "Целевой пресет для известных репозиториев/брендовых названий списков."
        ),
        ScannerPreset(
            id = "ace",
            title = "Ace/Torrent каналы",
            query = "acestream iptv",
            keywords = "acestream, ace stream, torrent tv, magnet, infohash, ace, acestream channels, torrent channels",
            provider = ScannerProviderScope.ALL,
            description = "Поиск каналов со ссылками Ace Stream / torrent descriptor."
        )
    )
}
