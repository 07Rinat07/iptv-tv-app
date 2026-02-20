package com.iptv.tv.feature.scanner

import android.content.Context
import android.os.Environment
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
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
    val exportedLinksPath: String? = null
)

private enum class SearchPhase {
    IDLE,
    SCANNING,
    IMPORTING
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
    private var searchJob: Job? = null
    private var searchPhase: SearchPhase = SearchPhase.IDLE
    private var currentMergedCandidates: LinkedHashMap<String, PlaylistCandidate> = linkedMapOf()
    private val localAiAssistant = LocalAiQueryAssistant()
    private val probeCache = ConcurrentHashMap<String, ProbeCacheEntry>()
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
            _uiState.update {
                it.copy(
                    statusType = ScannerStatusType.INFO,
                    statusTitle = "Сохраняем найденное",
                    statusDetails = "Сейчас идет запись найденных плейлистов. Этот этап завершается автоматически.",
                    progressStageLabel = "Сохранение найденного",
                    progressStageLocation = "Пожалуйста, дождитесь завершения..."
                )
            }
            viewModelScope.launch {
                safeLog(
                    status = "scanner_stop_deferred",
                    message = "attempt=$searchAttemptId, phase=IMPORTING, found=${currentMergedCandidates.size}"
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
            runCatching {
                withContext(Dispatchers.IO) {
                    val now = System.currentTimeMillis()
                    val content = buildString {
                        appendLine("myscanerIPTV | Экспорт найденных плейлистов")
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
                    val targetDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: appContext.filesDir
                    if (!targetDir.exists()) {
                        targetDir.mkdirs()
                    }
                    val file = File(targetDir, "scanner-found-links-$now.txt")
                    file.writeText(content)
                    file.absolutePath
                }
            }.onSuccess { path ->
                _uiState.update {
                    it.copy(
                        exportedLinksPath = path,
                        statusType = ScannerStatusType.SUCCESS,
                        statusTitle = "Ссылки сохранены",
                        statusDetails = "Экспортировано ${candidates.size} ссылок в TXT: $path"
                    )
                }
                safeLog(
                    status = "scanner_export_links_ok",
                    message = "count=${candidates.size}, path=$path"
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
                    message = reason
                )
            }
        }
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
            searchPhase = SearchPhase.SCANNING
            currentMergedCandidates = linkedMapOf()
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

                val foundAll = outcome.candidates
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
                val partial = currentMergedCandidates.values.take(targetResultCount)
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
                val partialDetails = if (partial.isEmpty()) {
                    "Поиск остановлен вручную. Совпадений пока нет."
                } else {
                    val importText = if (importSummary != null) {
                        " | сохранено=${importSummary.imported}, дубликаты=${importSummary.skipped}, ошибок=${importSummary.failed}"
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
                searchJob = null
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

        return plan
            .distinctBy { it.request.key() }
            .take(if (state.aiEnabled) MAX_PLAN_STEPS_AI else MAX_PLAN_STEPS)
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
                networkStepTimeoutMs = networkStepTimeoutMs
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
                networkStepTimeoutMs = networkStepTimeoutMs
            )
        }

        val providers = buildList {
            add(ScannerProviderScope.GITHUB)
            add(ScannerProviderScope.GITLAB)
            if (!request.repoFilter.isNullOrBlank()) {
                add(ScannerProviderScope.BITBUCKET)
            }
        }

        val merged = linkedMapOf<String, PlaylistCandidate>()
        val providerErrors = mutableListOf<String>()
        var hasSuccessfulProviderCall = false
        _uiState.update {
            it.copy(
                progressStageLocation = "Параллельно: ${providers.joinToString(", ") { providerDisplayName(it) }}",
                statusDetails = "$stepLabel | параллельный запрос провайдеров"
            )
        }

        val providerResults = coroutineScope {
            providers.map { provider ->
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
                        networkStepTimeoutMs = networkStepTimeoutMs
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
        networkStepTimeoutMs: Long
    ): AppResult<List<PlaylistCandidate>> {
        val startedAtMs = System.currentTimeMillis()
        return try {
            val result = withTimeout(networkStepTimeoutMs) {
                scannerRepository.search(request)
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
        val failureReasons = mutableListOf<String>()

        candidates.forEachIndexed { index, candidate ->
            val sourceUrl = candidate.downloadUrl.trim()
            if (sourceUrl.isBlank()) {
                failed += 1
                failureReasons += "${candidate.provider}/${candidate.repository}/${candidate.path}: empty download url"
                safeLog(
                    status = "scanner_import_error",
                    message = "provider=${candidate.provider}, repo=${candidate.repository}, path=${candidate.path}, reason=empty download url"
                )
                onProgress?.invoke(
                    ImportProgress(
                        processed = index + 1,
                        total = candidates.size,
                        imported = imported,
                        skipped = skipped,
                        failed = failed,
                        current = "${candidate.name.ifBlank { candidate.path }} | ${candidate.downloadUrl.take(140)}"
                    )
                )
                return@forEachIndexed
            }

            val normalizedSource = normalizeSource(sourceUrl)
            if (existingSources.contains(normalizedSource)) {
                skipped += 1
                safeLog(
                    status = "scanner_import_skip_duplicate",
                    message = "provider=${candidate.provider}, repo=${candidate.repository}, path=${candidate.path}"
                )
                onProgress?.invoke(
                    ImportProgress(
                        processed = index + 1,
                        total = candidates.size,
                        imported = imported,
                        skipped = skipped,
                        failed = failed,
                        current = "${candidate.name.ifBlank { candidate.path }} | duplicate"
                    )
                )
                return@forEachIndexed
            }

            val importResult = playlistRepository.importFromUrl(
                sourceUrl,
                candidate.name.ifBlank { "Imported Playlist" }
            )
            when (importResult) {
                is AppResult.Success -> {
                    imported += 1
                    existingSources += normalizedSource
                    safeLog(
                        status = "scanner_import_ok",
                        message = "provider=${candidate.provider}, repo=${candidate.repository}, path=${candidate.path}, playlistId=${importResult.data.playlistId}, imported=$imported"
                    )
                }
                is AppResult.Error -> {
                    failed += 1
                    val reason = buildFailureReason(
                        candidate = candidate,
                        reason = importResult.message
                    )
                    failureReasons += reason
                    safeLog(
                        status = "scanner_import_error",
                        message = "provider=${candidate.provider}, repo=${candidate.repository}, path=${candidate.path}, reason=$reason"
                    )
                }
                AppResult.Loading -> Unit
            }

            onProgress?.invoke(
                ImportProgress(
                    processed = index + 1,
                    total = candidates.size,
                    imported = imported,
                    skipped = skipped,
                    failed = failed,
                    current = "${candidate.name.ifBlank { candidate.path }} | ${sourceUrl.take(140)}"
                )
            )

            if ((index + 1) % IMPORT_PROGRESS_LOG_EVERY == 0 || index + 1 == candidates.size) {
                safeLog(
                    status = "scanner_import_progress",
                    message = "processed=${index + 1}/${candidates.size}, imported=$imported, skipped=$skipped, failed=$failed"
                )
            }
        }

        safeLog(
            status = "scanner_import_finish",
            message = "imported=$imported, skipped=$skipped, failed=$failed"
        )

        return ImportSummary(
            imported = imported,
            skipped = skipped,
            failed = failed,
            failureReasons = failureReasons.take(MAX_IMPORT_FAILURE_DETAILS)
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
        val foundMessage = "Найдено ${results.size} (GitHub=$github, GitLab=$gitlab, Bitbucket=$bitbucket)"
        val importMessage = importSummary?.let {
            val base = " | сохранено=${it.imported}, пропущено=${it.skipped}, ошибок=${it.failed}"
            val reason = it.failureReasons.firstOrNull()?.let { first -> " | причина: $first" }.orEmpty()
            base + reason
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

    private data class LearnedQueryCandidate(
        val query: String,
        val score: Int,
        val hits: Int,
        val lastSuccessAt: Long
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
        val failureReasons: List<String> = emptyList()
    )

    private data class ImportProgress(
        val processed: Int,
        val total: Int,
        val imported: Int,
        val skipped: Int,
        val failed: Int,
        val current: String? = null
    )

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
        const val MAX_IMPORT_FAILURE_DETAILS = 5
        const val IMPORT_PROGRESS_LOG_EVERY = 5
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

private fun scannerPresets(): List<ScannerPreset> {
    return listOf(
        ScannerPreset(
            id = "general",
            title = "Общий IPTV",
            query = "iptv",
            keywords = "",
            provider = ScannerProviderScope.ALL,
            description = "Базовый поиск публичных IPTV плейлистов без узких фильтров."
        ),
        ScannerPreset(
            id = "ru",
            title = "Русские каналы",
            query = "russian iptv",
            keywords = "",
            provider = ScannerProviderScope.ALL,
            description = "Поиск списков с русскими каналами и русскоязычными метками."
        ),
        ScannerPreset(
            id = "world",
            title = "Каналы мира",
            query = "world iptv",
            keywords = "",
            provider = ScannerProviderScope.ALL,
            description = "Поиск международных и мульти-страночных IPTV списков."
        ),
        ScannerPreset(
            id = "sport",
            title = "Спорт",
            query = "sport iptv",
            keywords = "",
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
            query = "tv channels iptv list",
            keywords = "tv channels, iptv channel list, списки тв, каналы iptv, списки каналов iptv, m3u, m3u8",
            provider = ScannerProviderScope.ALL,
            description = "Широкий поиск списков каналов в форматах M3U/M3U8."
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
            id = "ace",
            title = "Ace/Torrent каналы",
            query = "acestream iptv",
            keywords = "acestream, ace stream, torrent tv, magnet, infohash, ace, acestream channels, torrent channels",
            provider = ScannerProviderScope.ALL,
            description = "Поиск каналов со ссылками Ace Stream / torrent descriptor."
        )
    )
}
