package com.iptv.tv.feature.scanner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.ScannerRepository
import com.iptv.tv.core.model.PlaylistCandidate
import com.iptv.tv.core.model.ScannerProviderScope
import com.iptv.tv.core.model.ScannerSearchRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
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
    val description: String = "Поиск публичных M3U/M3U8 с сохранением топ-10 в Мои плейлисты",
    val query: String = "iptv",
    val keywords: String = "",
    val presets: List<ScannerPreset> = scannerPresets(),
    val selectedPresetId: String? = null,
    val selectedProvider: ScannerProviderScope = ScannerProviderScope.ALL,
    val showAdvancedFilters: Boolean = false,
    val repoFilter: String = "",
    val pathFilter: String = "",
    val minSizeBytes: String = "",
    val maxSizeBytes: String = "",
    val updatedDaysBack: String = "",
    val selectedPreview: PlaylistCandidate? = null,
    val isLoading: Boolean = false,
    val hasSearched: Boolean = false,
    val statusType: ScannerStatusType = ScannerStatusType.INFO,
    val statusTitle: String = "Готово к поиску",
    val statusDetails: String = "Введите запрос и нажмите \"Найти и сохранить 10\".",
    val results: List<PlaylistCandidate> = emptyList()
)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val scannerRepository: ScannerRepository,
    private val playlistRepository: PlaylistRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()
    private var searchAttemptId: Long = 0L

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
            statusDetails = providerInputHint(value)
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
            statusType = ScannerStatusType.INFO,
            statusTitle = "Фильтры очищены",
            statusDetails = "Оставьте только запрос и запустите поиск."
        )
    }

    fun search() = scanAndSaveTop10()

    fun scanOnlyTop10() {
        logClick(saveFoundResults = false)
        runSearch(saveFoundResults = false)
    }

    fun scanAndSaveTop10() {
        logClick(saveFoundResults = true)
        runSearch(saveFoundResults = true)
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

        viewModelScope.launch {
            val attemptId = ++searchAttemptId
            val startedAt = System.currentTimeMillis()
            val actionLabel = if (saveFoundResults) "поиск и сохранение" else "поиск"
            setStatus(
                type = ScannerStatusType.LOADING,
                title = "Выполняется $actionLabel",
                details = "Источник: ${providerDisplayName(state.selectedProvider)} | цель: $SEARCH_SAVE_TARGET",
                isLoading = true,
                hasSearched = state.hasSearched
            )
            safeLog(
                status = "scanner_start",
                message = "attempt=$attemptId, query=${state.query}, provider=${state.selectedProvider}, save=$saveFoundResults, preset=${state.selectedPresetId ?: "-"}"
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

            try {
                val searchPlan = buildSearchPlan(state)
                safeLog(
                    status = "scanner_plan",
                    message = "attempt=$attemptId, steps=${searchPlan.size}"
                )

                val outcome = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    executeSearchPlan(
                        attemptId = attemptId,
                        state = state,
                        plan = searchPlan
                    )
                }

                if (outcome == null) {
                    setStatus(
                        type = ScannerStatusType.ERROR,
                        title = "Таймаут поиска",
                        details = "Поиск занял слишком много времени (${SEARCH_TIMEOUT_MS / 1000}с). Повторите попытку.",
                        isLoading = false,
                        hasSearched = true
                    )
                    safeLog(
                        status = "scanner_timeout",
                        message = "attempt=$attemptId, query=${state.query}, provider=${state.selectedProvider}"
                    )
                    return@launch
                }

                val foundAll = outcome.candidates
                val foundForDisplay = foundAll.take(SEARCH_DISPLAY_LIMIT)
                val importSummary = if (saveFoundResults) {
                    importFoundPlaylists(
                        candidates = foundAll,
                        targetSaveCount = SEARCH_SAVE_TARGET
                    )
                } else {
                    null
                }
                val hasErrors = outcome.errors.isNotEmpty()
                val firstError = outcome.errors.firstOrNull().orEmpty()
                val importFailedCompletely = saveFoundResults &&
                    importSummary != null &&
                    importSummary.imported == 0 &&
                    importSummary.failed > 0

                val statusType = when {
                    importFailedCompletely -> ScannerStatusType.ERROR
                    foundAll.isNotEmpty() -> ScannerStatusType.SUCCESS
                    hasErrors -> ScannerStatusType.ERROR
                    else -> ScannerStatusType.INFO
                }
                val statusTitle = when {
                    importFailedCompletely -> "Поиск завершен, но сохранить не удалось"
                    foundAll.isNotEmpty() -> "Поиск завершен"
                    hasErrors -> "Ошибка поиска"
                    else -> "Ничего не найдено"
                }
                val statusDetails = when {
                    foundAll.isNotEmpty() -> {
                        val summary = buildResultSummary(foundAll, importSummary)
                        if (hasErrors) "$summary | предупреждений=${outcome.errors.size}" else summary
                    }
                    hasErrors -> "${mapScannerError(firstError)}. Проверьте интернет и повторите."
                    else -> emptyResultsHint(state)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasSearched = true,
                        results = foundForDisplay,
                        selectedPreview = foundForDisplay.firstOrNull(),
                        statusType = statusType,
                        statusTitle = statusTitle,
                        statusDetails = statusDetails
                    )
                }

                safeLog(
                    status = when {
                        foundAll.isNotEmpty() && hasErrors -> "scanner_ok_partial"
                        foundAll.isNotEmpty() -> "scanner_ok"
                        hasErrors -> "scanner_error"
                        else -> "scanner_empty"
                    },
                    message = "attempt=$attemptId, $statusDetails"
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
                safeLog(
                    status = "scanner_fatal",
                    message = "attempt=$attemptId, throwable=${throwable.javaClass.simpleName}, msg=$raw"
                )
            } finally {
                watchdogJob.cancel()
                val duration = System.currentTimeMillis() - startedAt
                safeLog(
                    status = "scanner_finish",
                    message = "attempt=$attemptId, durationMs=$duration, status=${_uiState.value.statusType}, results=${_uiState.value.results.size}"
                )
            }
        }
    }

    private fun logClick(saveFoundResults: Boolean) {
        val state = _uiState.value
        viewModelScope.launch {
            safeLog(
                status = "scanner_click",
                message = "save=$saveFoundResults, query=${state.query}, provider=${state.selectedProvider}, loading=${state.isLoading}"
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

        return plan
            .distinctBy { it.request.key() }
            .take(MAX_PLAN_STEPS)
    }

    private suspend fun executeSearchPlan(
        attemptId: Long,
        state: ScannerUiState,
        plan: List<SearchPlanStep>
    ): SearchPlanOutcome {
        val merged = linkedMapOf<String, PlaylistCandidate>()
        val errors = mutableListOf<String>()

        plan.forEachIndexed { index, step ->
            if (merged.size >= SEARCH_FETCH_LIMIT) return@forEachIndexed

            setStatus(
                type = ScannerStatusType.LOADING,
                title = "Поиск: шаг ${index + 1}/${plan.size}",
                details = "${step.label} | найдено=${merged.size}",
                isLoading = true,
                hasSearched = state.hasSearched
            )

            safeLog(
                status = "scanner_step_start",
                message = "attempt=$attemptId, step=${index + 1}, label=${step.label}, query=${step.request.query}, provider=${step.request.providerScope}"
            )

            val stepResult = withTimeoutOrNull(SEARCH_STEP_TIMEOUT_MS) {
                scannerRepository.search(step.request.copy(limit = SEARCH_FETCH_LIMIT))
            }

            when (stepResult) {
                null -> {
                    val msg = "${step.label}: timeout"
                    errors += msg
                    safeLog(
                        status = "scanner_step_timeout",
                        message = "attempt=$attemptId, step=${index + 1}, label=${step.label}"
                    )
                }
                is AppResult.Success -> {
                    val before = merged.size
                    stepResult.data.forEach { candidate ->
                        merged.putIfAbsent(candidate.id, candidate)
                    }
                    val added = merged.size - before
                    safeLog(
                        status = "scanner_step_ok",
                        message = "attempt=$attemptId, step=${index + 1}, added=$added, total=${merged.size}"
                    )
                }
                is AppResult.Error -> {
                    val msg = "${step.label}: ${stepResult.message}"
                    errors += msg
                    safeLog(
                        status = "scanner_step_error",
                        message = "attempt=$attemptId, step=${index + 1}, message=${stepResult.message}"
                    )
                }
                AppResult.Loading -> {
                    val msg = "${step.label}: repository returned Loading"
                    errors += msg
                    safeLog(
                        status = "scanner_step_loading_state",
                        message = "attempt=$attemptId, step=${index + 1}"
                    )
                }
            }
        }

        return SearchPlanOutcome(
            candidates = merged.values.take(SEARCH_FETCH_LIMIT),
            errors = errors
        )
    }

    private suspend fun importFoundPlaylists(
        candidates: List<PlaylistCandidate>,
        targetSaveCount: Int
    ): ImportSummary {
        if (candidates.isEmpty()) return ImportSummary()

        val existingSources = playlistRepository.observePlaylists()
            .first()
            .map { normalizeSource(it.source) }
            .toMutableSet()

        var imported = 0
        var skipped = 0
        var failed = 0
        val failureReasons = mutableListOf<String>()

        val maxToSave = targetSaveCount.coerceAtLeast(1)
        candidates.forEach { candidate ->
            if (imported >= maxToSave) return@forEach
            val sourceUrl = candidate.downloadUrl.trim()
            if (sourceUrl.isBlank()) {
                failed += 1
                failureReasons += "${candidate.provider}/${candidate.repository}/${candidate.path}: empty download url"
                return@forEach
            }

            val normalizedSource = normalizeSource(sourceUrl)
            if (existingSources.contains(normalizedSource)) {
                skipped += 1
                return@forEach
            }

            val importResult = playlistRepository.importFromUrl(
                sourceUrl,
                candidate.name.ifBlank { "Imported Playlist" }
            )
            when (importResult) {
                is AppResult.Success -> {
                    imported += 1
                    existingSources += normalizedSource
                }
                is AppResult.Error -> {
                    failed += 1
                    failureReasons += buildFailureReason(
                        candidate = candidate,
                        reason = importResult.message
                    )
                }
                AppResult.Loading -> Unit
            }
        }

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

    private fun emptyResultsHint(state: ScannerUiState): String {
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

    private fun mapScannerError(raw: String): String {
        val message = raw.trim()
        return when {
            message.contains("Unable to create converter", ignoreCase = true) ->
                "Внутренняя ошибка сетевого слоя (Retrofit/Moshi). Обновите APK до последней сборки"
            message.contains("Network IO", ignoreCase = true) ->
                "Приложение не может получить доступ к сети"
            message.contains("HTTP 401", ignoreCase = true) ->
                "Источник ограничил доступ к API (HTTP 401)"
            message.contains("HTTP 403", ignoreCase = true) ->
                "Достигнут лимит API или доступ запрещен (HTTP 403)"
            message.contains("timeout", ignoreCase = true) ->
                "Таймаут сетевого запроса"
            else -> message
        }
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
            repoFilter = repoFilter.ifBlank { null },
            pathFilter = pathFilter.ifBlank { null },
            updatedAfterEpochMs = updatedAfter,
            minSizeBytes = minSizeBytes.toLongOrNull(),
            maxSizeBytes = maxSizeBytes.toLongOrNull(),
            limit = SEARCH_FETCH_LIMIT
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
        val lowered = "$query ${manualKeywords.joinToString(" ")}".lowercase()
        val tags = mutableListOf<String>()

        if (isRussianIntent(lowered)) {
            tags += listOf("russian", "russia", "ru", "рус", "россия")
        }
        if (isWorldIntent(lowered)) {
            tags += listOf("world", "global", "international", "countries")
        }
        if (lowered.contains("sport") || lowered.contains("спорт")) {
            tags += listOf("sport", "football", "soccer", "hockey")
        }
        if (lowered.contains("movie") || lowered.contains("film") || lowered.contains("кино") || lowered.contains("фильм")) {
            tags += listOf("movie", "film", "cinema", "vod")
        }
        return tags.distinct()
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

    private data class SearchPlanOutcome(
        val candidates: List<PlaylistCandidate>,
        val errors: List<String>
    )

    private data class ImportSummary(
        val imported: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
        val failureReasons: List<String> = emptyList()
    )

    private companion object {
        const val SEARCH_SAVE_TARGET = 10
        const val SEARCH_DISPLAY_LIMIT = 10
        const val SEARCH_FETCH_LIMIT = 40
        const val SEARCH_TIMEOUT_MS = 35_000L
        const val SEARCH_WATCHDOG_MS = 8_000L
        const val SEARCH_STEP_TIMEOUT_MS = 12_000L
        const val MAX_PLAN_STEPS = 6
        const val MAX_KEYWORDS = 10
        const val MAX_LOG_MESSAGE = 700
        const val MAX_IMPORT_FAILURE_DETAILS = 5
    }
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
            keywords = "",
            provider = ScannerProviderScope.ALL,
            description = "Поиск плейлистов с фильмами и VOD-каталогами."
        )
    )
}
