package com.iptv.tv.feature.favorites

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.FavoriteSourceVariant
import com.iptv.tv.core.model.FavoritesPortableImportResult
import com.iptv.tv.core.model.FavoritesPortableImportStatus
import com.iptv.tv.core.model.FavoritesShareableExportFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.Reader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavoritesUiState(
    val title: String = "Избранное",
    val description: String = "Глобальные избранные каналы",
    val channels: List<Channel> = emptyList(),
    val epgProgramsByChannel: Map<Long, List<EpgProgram>> = emptyMap(),
    val epgStatus: String = "EPG: нет данных",
    val selectedChannelId: Long? = null,
    val sourcePickerChannelId: Long? = null,
    val sourceVariants: List<FavoriteSourceVariant> = emptyList(),
    val isLoadingSources: Boolean = false,
    val isSelectingSource: Boolean = false,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportedFilePath: String? = null,
    val lastInfo: String? = null,
    val lastError: String? = null
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val playlistRepository: PlaylistRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            favoritesRepository.observeFavorites().collect { channels ->
                _uiState.update { state ->
                    val selectedId = state.selectedChannelId
                        ?.takeIf { id -> channels.any { it.id == id } }
                    val nextSelectedId = selectedId ?: channels.firstOrNull()?.id
                    val keepSourcePicker = nextSelectedId != null &&
                        state.sourcePickerChannelId == nextSelectedId
                    state.copy(
                        channels = channels,
                        epgProgramsByChannel = state.epgProgramsByChannel.filterKeys { id ->
                            channels.any { it.id == id }
                        },
                        selectedChannelId = nextSelectedId,
                        sourcePickerChannelId = if (keepSourcePicker) {
                            state.sourcePickerChannelId
                        } else {
                            null
                        },
                        sourceVariants = if (keepSourcePicker) state.sourceVariants else emptyList(),
                        isLoadingSources = if (keepSourcePicker) state.isLoadingSources else false,
                        isSelectingSource = if (keepSourcePicker) state.isSelectingSource else false
                    )
                }
                loadFavoritesEpg(channels)
            }
        }
    }

    fun selectChannel(channelId: Long) {
        _uiState.update { state ->
            val changed = state.selectedChannelId != channelId
            state.copy(
                selectedChannelId = channelId,
                sourcePickerChannelId = if (changed) null else state.sourcePickerChannelId,
                sourceVariants = if (changed) emptyList() else state.sourceVariants,
                isLoadingSources = if (changed) false else state.isLoadingSources,
                isSelectingSource = if (changed) false else state.isSelectingSource,
                lastError = null
            )
        }
    }

    fun openSourcePicker() {
        val state = _uiState.value
        val selected = state.selectedChannelId
        if (selected == null) {
            _uiState.update { it.copy(lastError = "Канал не выбран", lastInfo = null) }
            return
        }
        if (state.isExporting || state.isImporting || state.isSelectingSource) return

        _uiState.update {
            it.copy(
                sourcePickerChannelId = selected,
                sourceVariants = if (it.sourcePickerChannelId == selected) {
                    it.sourceVariants
                } else {
                    emptyList()
                },
                isLoadingSources = true,
                lastInfo = null,
                lastError = null
            )
        }
        viewModelScope.launch {
            runCatching {
                favoritesRepository.getSourceVariants(selected)
            }.onSuccess { variants ->
                _uiState.update { current ->
                    if (current.sourcePickerChannelId != selected) return@update current
                    current.copy(
                        sourceVariants = variants,
                        isLoadingSources = false,
                        lastError = if (variants.isEmpty()) {
                            "Для выбранного канала не найдены варианты источника"
                        } else {
                            null
                        }
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { current ->
                    if (current.sourcePickerChannelId != selected) return@update current
                    current.copy(
                        sourceVariants = emptyList(),
                        isLoadingSources = false,
                        lastError = "Не удалось загрузить источники: ${throwable.message}",
                        lastInfo = null
                    )
                }
            }
        }
    }

    fun closeSourcePicker() {
        _uiState.update {
            it.copy(
                sourcePickerChannelId = null,
                sourceVariants = emptyList(),
                isLoadingSources = false,
                isSelectingSource = false
            )
        }
    }

    fun selectPreferredSource(variantKey: String) {
        val state = _uiState.value
        val channelId = state.sourcePickerChannelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Сначала откройте список источников", lastInfo = null) }
            return
        }
        if (
            variantKey.isBlank() ||
            state.isLoadingSources ||
            state.isSelectingSource ||
            state.isExporting ||
            state.isImporting
        ) {
            return
        }
        val target = state.sourceVariants.firstOrNull { it.variantKey == variantKey }
        if (target == null) {
            _uiState.update { it.copy(lastError = "Источник больше недоступен", lastInfo = null) }
            return
        }
        if (target.isPreferred) return

        _uiState.update { it.copy(isSelectingSource = true, lastInfo = null, lastError = null) }
        viewModelScope.launch {
            runCatching {
                favoritesRepository.selectPreferredSource(channelId, variantKey)
            }.onSuccess { selected ->
                if (!selected) {
                    _uiState.update { current ->
                        if (current.sourcePickerChannelId != channelId) return@update current
                        current.copy(
                            isSelectingSource = false,
                            lastError = "Не удалось выбрать источник",
                            lastInfo = null
                        )
                    }
                    return@onSuccess
                }

                val refreshed = runCatching {
                    favoritesRepository.getSourceVariants(channelId)
                }.getOrElse {
                    state.sourceVariants.map { variant ->
                        variant.copy(isPreferred = variant.variantKey == variantKey)
                    }
                }
                _uiState.update { current ->
                    if (current.sourcePickerChannelId != channelId) return@update current
                    current.copy(
                        sourceVariants = refreshed,
                        isSelectingSource = false,
                        lastInfo = favoriteSourceSelectionMessage(target),
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update { current ->
                    if (current.sourcePickerChannelId != channelId) return@update current
                    current.copy(
                        isSelectingSource = false,
                        lastInfo = null,
                        lastError = "Не удалось выбрать источник: ${throwable.message}"
                    )
                }
            }
        }
    }

    fun removeSelectedFromFavorites() {
        val state = _uiState.value
        val selected = state.selectedChannelId
        if (selected == null) {
            _uiState.update { it.copy(lastError = "Канал не выбран") }
            return
        }
        if (state.isLoadingSources || state.isSelectingSource) return
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(selected)
            _uiState.update { it.copy(lastInfo = "Канал удален из избранного", lastError = null) }
        }
    }

    fun exportFavoritesTxt() {
        exportShareableFavorites(
            format = FavoritesShareableExportFormat.TXT,
            extension = "txt",
            mimeType = "text/plain"
        )
    }

    fun exportFavoritesM3u8() {
        exportShareableFavorites(
            format = FavoritesShareableExportFormat.M3U8,
            extension = "m3u8",
            mimeType = "application/vnd.apple.mpegurl"
        )
    }

    fun exportFavoritesRiptv() {
        launchExport(
            extension = "riptv",
            mimeType = "application/json"
        ) {
            val exported = favoritesRepository.exportPortableBackup()
                ?: error("Полный backup избранного недоступен")
            ExportPayload(
                content = exported.content,
                infoSuffix = buildString {
                    append("каналов: ${exported.favoriteCount}; вариантов: ${exported.variantCount}")
                    if (exported.redactedVariantCount > 0) {
                        append("; скрыто credential-bearing вариантов: ${exported.redactedVariantCount}")
                    }
                }
            )
        }
    }

    fun importFavoritesRiptv(uri: Uri) {
        val state = _uiState.value
        if (
            state.isExporting ||
            state.isImporting ||
            state.isLoadingSources ||
            state.isSelectingSource
        ) {
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    lastInfo = null,
                    lastError = null
                )
            }
            runCatching {
                val content = readPortableBackup(uri)
                favoritesRepository.importPortableBackup(content)
            }.onSuccess { result ->
                val feedback = favoritesImportFeedback(result)
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        lastInfo = feedback.info,
                        lastError = feedback.error
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        lastInfo = null,
                        lastError = "Не удалось импортировать RIPTV: ${throwable.message}"
                    )
                }
            }
        }
    }

    private fun exportShareableFavorites(
        format: FavoritesShareableExportFormat,
        extension: String,
        mimeType: String
    ) {
        launchExport(extension = extension, mimeType = mimeType) {
            val exported = favoritesRepository.exportShareableFavorites(format)
                ?: error("Безопасный экспорт избранного недоступен")
            if (format == FavoritesShareableExportFormat.M3U8 && exported.safeUrlCount == 0) {
                error("Нет каналов с безопасным URL для M3U8")
            }
            val omittedFavorites = exported.favoriteCount - exported.safeUrlCount
            ExportPayload(
                content = exported.content,
                infoSuffix = buildString {
                    append("безопасных URL: ${exported.safeUrlCount}")
                    if (format == FavoritesShareableExportFormat.M3U8 && omittedFavorites > 0) {
                        append("; пропущено без безопасного URL: $omittedFavorites")
                    }
                    if (exported.redactedVariantCount > 0) {
                        append("; скрыто credential-bearing вариантов: ${exported.redactedVariantCount}")
                    }
                }
            )
        }
    }

    private fun launchExport(
        extension: String,
        mimeType: String,
        buildPayload: suspend () -> ExportPayload
    ) {
        val state = _uiState.value
        if (state.channels.isEmpty()) {
            _uiState.update { it.copy(lastError = "Избранных каналов пока нет", lastInfo = null) }
            return
        }
        if (
            state.isExporting ||
            state.isImporting ||
            state.isLoadingSources ||
            state.isSelectingSource
        ) {
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, lastError = null) }
            runCatching {
                val payload = buildPayload()
                val fileName = "Favorites_${favoriteExportTimestamp()}.$extension"
                val path = saveTextToPublicDownloads(
                    fileName = fileName,
                    content = payload.content,
                    mimeType = mimeType
                )
                SavedExport(path = path, infoSuffix = payload.infoSuffix)
            }.onSuccess { saved ->
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportedFilePath = saved.path,
                        lastInfo = buildString {
                            append("Избранное сохранено: ${saved.path}")
                            saved.infoSuffix.takeIf(String::isNotBlank)?.let { suffix ->
                                append(" · ")
                                append(suffix)
                            }
                        },
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        lastError = "Не удалось сохранить избранное: ${throwable.message}",
                        lastInfo = null
                    )
                }
            }
        }
    }

    private fun readPortableBackup(uri: Uri): String {
        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("Не удалось открыть выбранный файл")
        return input.bufferedReader().use { reader ->
            reader.readBoundedText(MAX_RIPTV_IMPORT_CHARS)
        }
    }

    private fun loadFavoritesEpg(channels: List<Channel>) {
        if (channels.isEmpty()) {
            _uiState.update {
                it.copy(
                    epgProgramsByChannel = emptyMap(),
                    epgStatus = "EPG: нет избранных каналов"
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(epgStatus = "EPG: загрузка программы...") }
            val now = System.currentTimeMillis()
            val loaded = mutableMapOf<Long, List<EpgProgram>>()
            channels.groupBy { it.playlistId }.forEach { (playlistId, groupChannels) ->
                when (
                    val result = playlistRepository.getPlaylistEpgWindow(
                        playlistId = playlistId,
                        startEpochMs = now,
                        endEpochMs = now + FAVORITES_EPG_WINDOW_MS
                    )
                ) {
                    is AppResult.Success -> {
                        val favoriteIds = groupChannels.map { it.id }.toSet()
                        result.data
                            .filterKeys { it in favoriteIds }
                            .forEach { (channelId, programs) -> loaded[channelId] = programs }
                    }
                    is AppResult.Error, AppResult.Loading -> Unit
                }
            }
            _uiState.update {
                it.copy(
                    epgProgramsByChannel = loaded,
                    epgStatus = if (loaded.isEmpty()) {
                        "EPG: для избранных передач не найдено"
                    } else {
                        "EPG: найдено для каналов ${loaded.size}"
                    }
                )
            }
        }
    }

    private fun saveTextToPublicDownloads(
        fileName: String,
        content: String,
        mimeType: String
    ): String {
        val resolver = appContext.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Не удалось создать файл в Download")
            try {
                resolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(content)
                } ?: error("Не удалось открыть файл для записи")
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
}

internal data class FavoritesImportFeedback(
    val info: String? = null,
    val error: String? = null
)

internal fun favoritesImportFeedback(
    result: FavoritesPortableImportResult
): FavoritesImportFeedback = when (result.status) {
    FavoritesPortableImportStatus.SUCCESS -> FavoritesImportFeedback(
        info = buildString {
            append("RIPTV импорт завершен")
            append(" · добавлено: ${result.importedFavorites}")
            append(" · объединено: ${result.mergedFavorites}")
            append(" · вариантов: ${result.importedVariants}")
            if (result.redactedVariantsIgnored > 0) {
                append(" · скрытых вариантов пропущено: ${result.redactedVariantsIgnored}")
            }
            if (result.skippedUnrestorableFavorites > 0) {
                append(" · невосстановимых каналов пропущено: ${result.skippedUnrestorableFavorites}")
            }
        }
    )

    FavoritesPortableImportStatus.INVALID_FORMAT -> FavoritesImportFeedback(
        error = buildString {
            append("Неверный формат RIPTV backup")
            result.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
        }
    )

    FavoritesPortableImportStatus.UNSUPPORTED_VERSION -> FavoritesImportFeedback(
        error = buildString {
            append("Версия RIPTV backup не поддерживается")
            result.message?.takeIf(String::isNotBlank)?.let { append(": $it") }
        }
    )
}

internal fun Reader.readBoundedText(maxChars: Int): String {
    require(maxChars > 0) { "maxChars must be positive" }
    val output = StringBuilder(minOf(maxChars, 64 * 1024))
    val buffer = CharArray(8 * 1024)
    var total = 0
    while (true) {
        val readCount = read(buffer)
        if (readCount < 0) break
        if (readCount == 0) continue
        total += readCount
        require(total <= maxChars) { "Backup слишком большой" }
        output.append(buffer, 0, readCount)
    }
    return output.toString()
}

private data class ExportPayload(
    val content: String,
    val infoSuffix: String
)

private data class SavedExport(
    val path: String,
    val infoSuffix: String
)

private const val FAVORITES_EPG_WINDOW_MS = 3 * 60 * 60 * 1000L
private const val MAX_RIPTV_IMPORT_CHARS = 20_000_000
private val FAVORITES_EXPORT_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Oral")

private fun favoriteExportTimestamp(): String {
    return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = FAVORITES_EXPORT_TIME_ZONE
    }.format(Date())
}
