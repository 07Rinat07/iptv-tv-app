package com.iptv.tv.feature.editor

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.ChannelMetadataRepository
import com.iptv.tv.core.domain.repository.PlaylistEditorRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelMetadata
import com.iptv.tv.core.model.EditorActionResult
import com.iptv.tv.core.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

const val EDITOR_PLAYLIST_ID_ARG = "playlistId"

data class ChannelEditDraft(
    val channelId: Long? = null,
    val name: String = "",
    val group: String = "",
    val logo: String = "",
    val streamUrl: String = ""
)

data class EditorUiState(
    val title: String = "Редактор",
    val description: String = "Массовые действия, сортировка и безопасное copy-on-write",
    val playlists: List<Playlist> = emptyList(),
    val effectivePlaylistId: Long? = null,
    val channels: List<Channel> = emptyList(),
    val channelQuery: String = "",
    val favoriteChannelIds: Set<Long> = emptySet(),
    val selectedChannelIds: Set<Long> = emptySet(),
    val customPlaylistName: String = "Мой плейлист",
    val editDraft: ChannelEditDraft = ChannelEditDraft(),
    val selectedMetadata: ChannelMetadata? = null,
    val manualCountryInput: String = "",
    val manualLanguageInput: String = "",
    val manualCategoryInput: String = "",
    val externalLogoPackJson: String = "",
    val externalLogoPackUrl: String = "",
    val metadataRulesInput: String = "",
    val metadataRuleMatcherType: String = METADATA_RULE_MATCH_ANY,
    val metadataRuleMatcherInput: String = "",
    val metadataRuleCountryInput: String = "",
    val metadataRuleLanguageInput: String = "",
    val metadataRuleCategoryInput: String = "",
    val externalMetadataRulesCatalogInput: String = "",
    val externalMetadataRulesCatalogUrl: String = "",
    val externalMetadataRulesCatalogInfo: SharedMetadataRulesCatalogInfo? = null,
    val externalSharedMetadataRulePacks: List<SharedMetadataRulePack> = emptyList(),
    val exportPreview: String? = null,
    val exportFileExtension: String = "m3u",
    val exportedFilePath: String? = null,
    val isLoading: Boolean = false,
    val isRefreshingMetadata: Boolean = false,
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playlistRepository: PlaylistRepository,
    private val editorRepository: PlaylistEditorRepository,
    private val favoritesRepository: FavoritesRepository,
    private val channelMetadataRepository: ChannelMetadataRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val requestedPlaylistId: Long? = savedStateHandle.get<Long>(EDITOR_PLAYLIST_ID_ARG)
        ?: savedStateHandle.get<String>(EDITOR_PLAYLIST_ID_ARG)?.toLongOrNull()

    private var observedPlaylistId: Long? = null
    private var channelsJob: Job? = null
    private var lastExportContent: String? = null
    private var lastExportPlaylistId: Long? = null
    private var lastExportExtension: String = "m3u"

    init {
        observePlaylists()
        observeFavorites()
    }

    fun selectPlaylist(playlistId: Long) {
        _uiState.update {
            it.copy(
                effectivePlaylistId = playlistId,
                selectedChannelIds = emptySet(),
                editDraft = ChannelEditDraft(),
                selectedMetadata = null,
                manualCountryInput = "",
                manualLanguageInput = "",
                manualCategoryInput = "",
                exportPreview = null,
                exportedFilePath = null,
                lastError = null,
                lastInfo = null
            )
        }
        observeChannels(playlistId)
    }

    fun toggleChannelSelection(channelId: Long) {
        _uiState.update { state ->
            val selected = state.selectedChannelIds.toMutableSet()
            if (!selected.add(channelId)) {
                selected.remove(channelId)
            }

            val selectedChannel = state.channels.firstOrNull { it.id == selected.firstOrNull() }
            state.copy(
                selectedChannelIds = selected,
                editDraft = selectedChannel?.toDraft() ?: state.editDraft.takeIf { it.channelId in selected } ?: ChannelEditDraft(),
                selectedMetadata = state.selectedMetadata.takeIf { it?.channelId == selectedChannel?.id },
                manualCountryInput = state.manualCountryInput.takeIf { state.selectedMetadata?.channelId == selectedChannel?.id }.orEmpty(),
                manualLanguageInput = state.manualLanguageInput.takeIf { state.selectedMetadata?.channelId == selectedChannel?.id }.orEmpty(),
                manualCategoryInput = state.manualCategoryInput.takeIf { state.selectedMetadata?.channelId == selectedChannel?.id }.orEmpty(),
                exportedFilePath = null,
                lastError = null,
                lastInfo = null
            )
        }
        _uiState.value.editDraft.channelId?.let(::loadSelectedMetadata)
    }

    fun selectAllChannels() {
        _uiState.update { state ->
            state.copy(
                selectedChannelIds = state.channels.map { it.id }.toSet(),
                lastError = null,
                lastInfo = null
            )
        }
    }

    fun selectVisibleChannels() {
        _uiState.update { state ->
            val selectedIds = filterEditorChannels(state.channels, state.channelQuery).map { it.id }.toSet()
            state.copy(
                selectedChannelIds = selectedIds,
                lastError = if (selectedIds.isEmpty()) "Нет каналов по текущему фильтру" else null,
                lastInfo = if (selectedIds.isEmpty()) null else "Выбраны видимые каналы: ${selectedIds.size}"
            )
        }
    }

    fun selectVisibleChannelsWithoutLogo() {
        _uiState.update { state ->
            val selectedIds = filterEditorChannelsWithoutLogo(state.channels, state.channelQuery).map { it.id }.toSet()
            state.copy(
                selectedChannelIds = selectedIds,
                lastError = if (selectedIds.isEmpty()) "По текущему фильтру нет каналов без логотипа" else null,
                lastInfo = if (selectedIds.isEmpty()) null else "Выбраны каналы без логотипа: ${selectedIds.size}"
            )
        }
    }

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedChannelIds = emptySet(),
                editDraft = ChannelEditDraft(),
                selectedMetadata = null,
                manualCountryInput = "",
                manualLanguageInput = "",
                manualCategoryInput = "",
                exportPreview = null,
                exportedFilePath = null
            )
        }
    }

    fun ensureEditablePlaylist() {
        val playlistId = currentPlaylistIdOrError() ?: return
        executeEditorAction { editorRepository.ensureEditablePlaylist(playlistId) }
    }

    fun hideSelected(hidden: Boolean) {
        val playlistId = currentPlaylistIdOrError() ?: return
        val selected = selectedIdsOrError() ?: return
        executeEditorAction { editorRepository.bulkHide(playlistId, selected, hidden) }
    }

    fun deleteSelected() {
        val playlistId = currentPlaylistIdOrError() ?: return
        val selected = selectedIdsOrError() ?: return
        executeEditorAction { editorRepository.bulkDelete(playlistId, selected) }
    }

    fun deleteSingleChannel(channelId: Long) {
        val playlistId = currentPlaylistIdOrError() ?: return
        executeEditorAction { editorRepository.bulkDelete(playlistId, listOf(channelId)) }
    }

    fun deleteUnavailable() {
        val playlistId = currentPlaylistIdOrError() ?: return
        executeEditorAction { editorRepository.deleteUnavailableChannels(playlistId) }
    }

    fun moveSelectedToTop() {
        val playlistId = currentPlaylistIdOrError() ?: return
        val selected = selectedIdsOrError() ?: return
        executeEditorAction { editorRepository.moveChannelsToTop(playlistId, selected) }
    }

    fun moveSelectedToBottom() {
        val playlistId = currentPlaylistIdOrError() ?: return
        val selected = selectedIdsOrError() ?: return
        executeEditorAction { editorRepository.moveChannelsToBottom(playlistId, selected) }
    }

    fun setSingleChannelHidden(channelId: Long, hidden: Boolean) {
        val playlistId = currentPlaylistIdOrError() ?: return
        executeEditorAction { editorRepository.bulkHide(playlistId, listOf(channelId), hidden) }
    }

    fun updateCustomPlaylistName(value: String) {
        _uiState.update { it.copy(customPlaylistName = value, lastError = null, lastInfo = null) }
    }

    fun updateChannelQuery(value: String) {
        _uiState.update { it.copy(channelQuery = value) }
    }

    fun createCustomPlaylistFromSelection() {
        val selected = selectedIdsOrError() ?: return
        val name = _uiState.value.customPlaylistName
        executeEditorAction { editorRepository.createCustomPlaylistFromChannels(name, selected) }
    }

    fun exportSelectedOrVisibleM3u() {
        exportSelectedOrVisible(extension = "m3u")
    }

    fun exportSelectedOrVisibleM3u8() {
        exportSelectedOrVisible(extension = "m3u8")
    }

    fun exportAllPlaylistsToTxt() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastInfo = null) }
            runCatching {
                val playlists = playlistRepository.observePlaylists().first()
                if (playlists.isEmpty()) {
                    error("Плейлистов пока нет")
                }

                val contentBuilder = StringBuilder()
                var totalChannels = 0

                contentBuilder.appendLine("myscanerIPTV | Экспорт плейлистов")
                contentBuilder.appendLine("Плейлистов: ${playlists.size}")
                contentBuilder.appendLine("Сформировано: ${System.currentTimeMillis()}")
                contentBuilder.appendLine()

                playlists.forEachIndexed { playlistIndex, playlist ->
                    val channels = playlistRepository.observeChannels(playlist.id).first()
                    totalChannels += channels.size

                    contentBuilder.appendLine("${playlistIndex + 1}. ${playlist.name}")
                    contentBuilder.appendLine("ID=${playlist.id} | type=${playlist.sourceType} | channels=${channels.size}")
                    contentBuilder.appendLine("Source: ${playlist.source}")

                    if (channels.isEmpty()) {
                        contentBuilder.appendLine("  (каналов нет)")
                    } else {
                        channels.forEachIndexed { channelIndex, channel ->
                            val groupSuffix = channel.group?.takeIf { it.isNotBlank() }?.let { " | group=$it" }.orEmpty()
                            contentBuilder.appendLine("  ${channelIndex + 1}) ${channel.name}$groupSuffix")
                            contentBuilder.appendLine("     URL: ${channel.streamUrl}")
                        }
                    }
                    contentBuilder.appendLine()
                }

                TextExportResult(
                    content = contentBuilder.toString(),
                    playlistsCount = playlists.size,
                    channelsCount = totalChannels
                )
            }.onSuccess { export ->
                lastExportContent = export.content
                lastExportPlaylistId = null
                lastExportExtension = "txt"
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        exportPreview = export.content.take(EXPORT_PREVIEW_MAX_LEN),
                        exportFileExtension = "txt",
                        exportedFilePath = null,
                        lastInfo = "TXT подготовлен: плейлистов=${export.playlistsCount}, каналов=${export.channelsCount}",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastError = "Не удалось подготовить TXT: ${throwable.message}"
                    )
                }
            }
        }
    }

    private fun exportSelectedOrVisible(extension: String) {
        val playlistId = currentPlaylistIdOrError() ?: return
        val selected = _uiState.value.selectedChannelIds.toList()
        val normalizedExtension = if (extension.equals("m3u8", ignoreCase = true)) "m3u8" else "m3u"
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastInfo = null) }
            when (val result = editorRepository.exportToM3u(playlistId, selected)) {
                is AppResult.Success -> {
                    val preview = result.data.m3uContent.take(EXPORT_PREVIEW_MAX_LEN)
                    lastExportContent = result.data.m3uContent
                    lastExportPlaylistId = result.data.playlistId
                    lastExportExtension = normalizedExtension
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            exportPreview = preview,
                            exportFileExtension = normalizedExtension,
                            exportedFilePath = null,
                            lastInfo = "Экспортировано каналов: ${result.data.channelCount}. Формат: .$normalizedExtension."
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, lastError = result.message) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun saveExportToStorage() {
        val content = lastExportContent
        if (content.isNullOrBlank()) {
            _uiState.update { it.copy(lastError = "Сначала выполните экспорт (M3U/M3U8/TXT)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
            runCatching {
                val playlistId = lastExportPlaylistId ?: _uiState.value.effectivePlaylistId
                val playlistName = if (playlistId == null) {
                    "playlists-export"
                } else {
                    _uiState.value.playlists
                        .firstOrNull { it.id == playlistId }
                        ?.name
                        ?.sanitizeFileName()
                        .orEmpty()
                        .ifBlank { "playlist-$playlistId" }
                }
                val ext = _uiState.value.exportFileExtension.ifBlank { lastExportExtension }
                val fileName = if (ext.equals("txt", ignoreCase = true)) {
                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    "Tv_list_$stamp.txt"
                } else {
                    "$playlistName-${System.currentTimeMillis()}.$ext"
                }
                saveTextToPublicDownloads(fileName = fileName, content = content)
            }.onSuccess { path ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        exportedFilePath = path,
                        lastInfo = "Файл сохранен: $path",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastError = "Не удалось сохранить файл: ${throwable.message}"
                    )
                }
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

    fun saveExportToUri(uriString: String) {
        val content = lastExportContent
        if (content.isNullOrBlank()) {
            _uiState.update { it.copy(lastError = "Сначала выполните экспорт (M3U/M3U8/TXT)") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
            runCatching {
                val uri = android.net.Uri.parse(uriString)
                appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(content)
                } ?: error("Не удалось открыть файл для записи")
                uri.toString()
            }.onSuccess { uri ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        exportedFilePath = uri,
                        lastInfo = "Файл сохранен: $uri",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastError = "Не удалось сохранить файл: ${throwable.message}"
                    )
                }
            }
        }
    }

    fun pickChannelForEdit(channelId: Long) {
        _uiState.update { state ->
            val channel = state.channels.firstOrNull { it.id == channelId } ?: return@update state
            state.copy(
                selectedChannelIds = setOf(channelId),
                editDraft = channel.toDraft(),
                selectedMetadata = null,
                manualCountryInput = "",
                manualLanguageInput = "",
                manualCategoryInput = "",
                exportedFilePath = null,
                lastError = null,
                lastInfo = null
            )
        }
        loadSelectedMetadata(channelId)
    }

    fun updateDraftName(value: String) {
        _uiState.update { it.copy(editDraft = it.editDraft.copy(name = value), lastError = null) }
    }

    fun updateDraftGroup(value: String) {
        _uiState.update { it.copy(editDraft = it.editDraft.copy(group = value), lastError = null) }
    }

    fun updateDraftLogo(value: String) {
        _uiState.update { it.copy(editDraft = it.editDraft.copy(logo = value), lastError = null) }
    }

    fun updateExternalLogoPackJson(value: String) {
        _uiState.update { it.copy(externalLogoPackJson = value, lastError = null) }
    }

    fun updateMetadataRulesInput(value: String) {
        _uiState.update { it.copy(metadataRulesInput = value, lastError = null, lastInfo = null) }
    }

    fun updateExternalMetadataRulesCatalogInput(value: String) {
        _uiState.update { it.copy(externalMetadataRulesCatalogInput = value, lastError = null, lastInfo = null) }
    }

    fun updateExternalMetadataRulesCatalogUrl(value: String) {
        _uiState.update { it.copy(externalMetadataRulesCatalogUrl = value, lastError = null, lastInfo = null) }
    }

    fun loadExternalMetadataRulesCatalog() {
        val catalog = _uiState.value.externalMetadataRulesCatalogInput
        applyExternalMetadataRulesCatalog(catalog)
    }

    fun loadExternalMetadataRulesCatalogUrl() {
        val url = normalizeSharedRulesCatalogUrl(_uiState.value.externalMetadataRulesCatalogUrl)
        if (url == null) {
            _uiState.update { it.copy(lastError = "Введите HTTP/HTTPS URL shared rules catalog") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastInfo = null) }
            runCatching {
                fetchExternalMetadataRulesCatalog(url)
            }.onSuccess { catalog ->
                applyExternalMetadataRulesCatalog(
                    catalog = catalog,
                    catalogInput = catalog,
                    infoPrefix = "Shared rules catalog загружен по URL"
                )
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastError = "Не удалось загрузить shared rules catalog: ${throwable.message}"
                    )
                }
            }
        }
    }

    private fun applyExternalMetadataRulesCatalog(
        catalog: String,
        catalogInput: String? = null,
        infoPrefix: String = "Shared rules catalog загружен"
    ) {
        val packs = parseSharedMetadataRulePacksCatalog(catalog)
        val catalogInfo = parseSharedMetadataRulesCatalogInfo(catalog)
        if (packs.isEmpty()) {
            _uiState.update { it.copy(isLoading = false, lastError = "Нет корректных shared rules packs") }
            return
        }
        if (catalogInfo.checksumStatus == SharedRulesCatalogChecksumStatus.INVALID) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    externalMetadataRulesCatalogInfo = catalogInfo,
                    lastError = "Checksum shared rules catalog не совпадает"
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                externalMetadataRulesCatalogInput = catalogInput ?: it.externalMetadataRulesCatalogInput,
                externalMetadataRulesCatalogInfo = catalogInfo.takeIf { info -> info.hasAnyValue },
                externalSharedMetadataRulePacks = packs,
                lastError = null,
                lastInfo = buildSharedRulesCatalogLoadedMessage(infoPrefix, packs.size, catalogInfo)
            )
        }
    }

    private suspend fun fetchExternalMetadataRulesCatalog(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "text/plain,*/*")
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                error("HTTP $responseCode")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun appendSharedMetadataRulesPack(packId: String) {
        val pack = sharedMetadataRulePacks.firstOrNull { it.id == packId }
        if (pack == null) {
            _uiState.update { it.copy(lastError = "Metadata rules pack не найден: $packId") }
            return
        }
        _uiState.update {
            it.copy(
                metadataRulesInput = appendMetadataRulesText(it.metadataRulesInput, pack.rules),
                lastError = null,
                lastInfo = "Shared rules pack добавлен: ${pack.title}"
            )
        }
    }

    fun appendExternalMetadataRulesPack(packId: String) {
        val pack = _uiState.value.externalSharedMetadataRulePacks.firstOrNull { it.id == packId }
        if (pack == null) {
            _uiState.update { it.copy(lastError = "External metadata rules pack не найден: $packId") }
            return
        }
        _uiState.update {
            it.copy(
                metadataRulesInput = appendMetadataRulesText(it.metadataRulesInput, pack.rules),
                lastError = null,
                lastInfo = "External rules pack добавлен: ${pack.title}"
            )
        }
    }

    fun saveMetadataRulesToStorage() {
        val rules = _uiState.value.metadataRulesInput.trim()
        if (rules.isBlank()) {
            _uiState.update { it.copy(lastError = "Сначала добавьте metadata rules") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastInfo = null) }
            runCatching {
                val playlistName = _uiState.value.playlists
                    .firstOrNull { it.id == _uiState.value.effectivePlaylistId }
                    ?.name
                    ?.sanitizeFileName()
                    .orEmpty()
                    .ifBlank { "metadata-rules" }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                saveTextToPublicDownloads(
                    fileName = "$playlistName-metadata-rules-$stamp.txt",
                    content = rules
                )
            }.onSuccess { path ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        exportedFilePath = path,
                        lastInfo = "Metadata rules сохранены: $path",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastError = "Не удалось сохранить metadata rules: ${throwable.message}"
                    )
                }
            }
        }
    }

    fun saveMetadataRulesToUri(uriString: String) {
        val rules = _uiState.value.metadataRulesInput.trim()
        if (rules.isBlank()) {
            _uiState.update { it.copy(lastError = "Сначала добавьте metadata rules") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastInfo = null) }
            runCatching {
                val uri = android.net.Uri.parse(uriString)
                appContext.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { writer ->
                    writer.write(rules)
                } ?: error("Не удалось открыть файл для записи")
                uri.toString()
            }.onSuccess { uri ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        exportedFilePath = uri,
                        lastInfo = "Metadata rules сохранены: $uri",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastError = "Не удалось сохранить metadata rules: ${throwable.message}"
                    )
                }
            }
        }
    }

    fun importMetadataRulesFromUri(uriString: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastInfo = null) }
            runCatching {
                val uri = android.net.Uri.parse(uriString)
                appContext.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    reader.readText()
                } ?: error("Не удалось открыть файл для чтения")
            }.onSuccess { rules ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        metadataRulesInput = rules.trim(),
                        lastInfo = "Metadata rules импортированы",
                        lastError = null
                    )
                }
            }.onFailure { throwable ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        lastError = "Не удалось импортировать metadata rules: ${throwable.message}"
                    )
                }
            }
        }
    }

    fun updateMetadataRuleMatcherType(value: String) {
        _uiState.update {
            it.copy(
                metadataRuleMatcherType = normalizeMetadataRuleMatcherType(value),
                lastError = null,
                lastInfo = null
            )
        }
    }

    fun updateMetadataRuleMatcherInput(value: String) {
        _uiState.update { it.copy(metadataRuleMatcherInput = value, lastError = null, lastInfo = null) }
    }

    fun updateMetadataRuleCountryInput(value: String) {
        _uiState.update { it.copy(metadataRuleCountryInput = value, lastError = null, lastInfo = null) }
    }

    fun updateMetadataRuleLanguageInput(value: String) {
        _uiState.update { it.copy(metadataRuleLanguageInput = value, lastError = null, lastInfo = null) }
    }

    fun updateMetadataRuleCategoryInput(value: String) {
        _uiState.update { it.copy(metadataRuleCategoryInput = value, lastError = null, lastInfo = null) }
    }

    fun appendMetadataRuleFromBuilder() {
        val state = _uiState.value
        val rule = buildMetadataRuleLine(
            matcherType = state.metadataRuleMatcherType,
            matcherValue = state.metadataRuleMatcherInput,
            country = state.metadataRuleCountryInput,
            language = state.metadataRuleLanguageInput,
            category = state.metadataRuleCategoryInput
        )
        if (rule == null) {
            _uiState.update {
                it.copy(lastError = "Заполните условие и хотя бы одно поле metadata")
            }
            return
        }
        _uiState.update {
            val currentRules = it.metadataRulesInput.trim()
            it.copy(
                metadataRulesInput = if (currentRules.isBlank()) rule else "$currentRules\n$rule",
                metadataRuleMatcherInput = "",
                metadataRuleCountryInput = "",
                metadataRuleLanguageInput = "",
                metadataRuleCategoryInput = "",
                lastError = null,
                lastInfo = "Metadata rule добавлен"
            )
        }
    }

    fun updateManualCountry(value: String) {
        _uiState.update { it.copy(manualCountryInput = value, lastError = null) }
    }

    fun updateManualLanguage(value: String) {
        _uiState.update { it.copy(manualLanguageInput = value, lastError = null) }
    }

    fun updateManualCategory(value: String) {
        _uiState.update { it.copy(manualCategoryInput = value, lastError = null) }
    }

    fun updateDraftStreamUrl(value: String) {
        _uiState.update { it.copy(editDraft = it.editDraft.copy(streamUrl = value), lastError = null) }
    }

    fun saveDraft() {
        val playlistId = currentPlaylistIdOrError() ?: return
        val draft = _uiState.value.editDraft
        val channelId = draft.channelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Выберите канал для редактирования") }
            return
        }

        executeEditorAction {
            editorRepository.updateChannel(
                playlistId = playlistId,
                channelId = channelId,
                name = draft.name,
                group = draft.group,
                logo = draft.logo,
                streamUrl = draft.streamUrl
            )
        }
    }

    fun saveDraftLogoAsManual() {
        val draft = _uiState.value.editDraft
        val channelId = draft.channelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Выберите канал для ручного логотипа") }
            return
        }
        viewModelScope.launch {
            when (val result = channelMetadataRepository.setManualLogo(channelId, draft.logo)) {
                is AppResult.Success -> {
                    loadSelectedMetadata(channelId)
                    _uiState.update {
                        it.copy(
                            lastInfo = "Ручной логотип сохранён",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun clearManualLogo() {
        val channelId = _uiState.value.editDraft.channelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Выберите канал для очистки ручного логотипа") }
            return
        }
        viewModelScope.launch {
            when (val result = channelMetadataRepository.setManualLogo(channelId, null)) {
                is AppResult.Success -> {
                    loadSelectedMetadata(channelId)
                    _uiState.update {
                        it.copy(
                            lastInfo = "Ручной логотип очищен, обновлено: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun saveManualMetadata() {
        val state = _uiState.value
        val channelId = state.editDraft.channelId
        if (channelId == null) {
            _uiState.update { it.copy(lastError = "Выберите канал для ручных метаданных") }
            return
        }
        viewModelScope.launch {
            when (
                val result = channelMetadataRepository.setManualMetadata(
                    channelId = channelId,
                    country = state.manualCountryInput,
                    language = state.manualLanguageInput,
                    category = state.manualCategoryInput
                )
            ) {
                is AppResult.Success -> {
                    loadSelectedMetadata(channelId)
                    _uiState.update {
                        it.copy(
                            lastInfo = "Ручные метаданные сохранены",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun saveManualMetadataForSelected() {
        val state = _uiState.value
        val selected = state.selectedChannelIds.toList()
        if (selected.isEmpty()) {
            _uiState.update { it.copy(lastError = "Выберите каналы для массовых метаданных") }
            return
        }
        viewModelScope.launch {
            when (
                val result = channelMetadataRepository.setManualMetadataBulk(
                    channelIds = selected,
                    country = state.manualCountryInput,
                    language = state.manualLanguageInput,
                    category = state.manualCategoryInput
                )
            ) {
                is AppResult.Success -> {
                    _uiState.value.editDraft.channelId?.let(::loadSelectedMetadata)
                    _uiState.update {
                        it.copy(
                            lastInfo = "Ручные метаданные применены к каналам: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update { it.copy(lastError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun applyMetadataRulesToSelectedOrVisible() {
        val playlistId = currentPlaylistIdOrError() ?: return
        val state = _uiState.value
        val rules = state.metadataRulesInput.trim()
        if (rules.isBlank()) {
            _uiState.update { it.copy(lastError = "Введите metadata rules") }
            return
        }
        val targetIds = state.selectedChannelIds
            .ifEmpty { filterEditorChannels(state.channels, state.channelQuery).map { it.id }.toSet() }
            .toList()
        if (targetIds.isEmpty()) {
            _uiState.update { it.copy(lastError = "Нет каналов для metadata rules") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingMetadata = true, lastError = null, lastInfo = null) }
            when (
                val result = channelMetadataRepository.applyMetadataRules(
                    playlistId = playlistId,
                    rulesText = rules,
                    channelIds = targetIds
                )
            ) {
                is AppResult.Success -> {
                    _uiState.value.editDraft.channelId?.let(::loadSelectedMetadata)
                    _uiState.update {
                        it.copy(
                            isRefreshingMetadata = false,
                            lastInfo = "Metadata rules применены к каналам: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isRefreshingMetadata = false, lastError = result.message)
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun refreshCurrentPlaylistMetadata() {
        val playlistId = currentPlaylistIdOrError() ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingMetadata = true, lastError = null, lastInfo = null) }
            when (val result = channelMetadataRepository.refreshMetadata(playlistId)) {
                is AppResult.Success -> {
                    _uiState.value.editDraft.channelId?.let(::loadSelectedMetadata)
                    _uiState.update {
                        it.copy(
                            isRefreshingMetadata = false,
                            lastInfo = "Метаданные обновлены, логотипов применено: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isRefreshingMetadata = false, lastError = result.message)
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun applyExternalLogoPack() {
        val playlistId = currentPlaylistIdOrError() ?: return
        val logoPackJson = _uiState.value.externalLogoPackJson.trim()
        if (logoPackJson.isBlank()) {
            _uiState.update { it.copy(lastError = "Вставьте JSON logo pack") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingMetadata = true, lastError = null, lastInfo = null) }
            when (val result = channelMetadataRepository.refreshMetadataWithLogoPack(playlistId, logoPackJson)) {
                is AppResult.Success -> {
                    _uiState.value.editDraft.channelId?.let(::loadSelectedMetadata)
                    _uiState.update {
                        it.copy(
                            isRefreshingMetadata = false,
                            externalLogoPackJson = "",
                            lastInfo = "Внешний logo pack применён, логотипов обновлено: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isRefreshingMetadata = false, lastError = result.message)
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun updateExternalLogoPackUrl(value: String) {
        _uiState.update { it.copy(externalLogoPackUrl = value, lastError = null, lastInfo = null) }
    }

    fun applyExternalLogoPackUrl() {
        val playlistId = currentPlaylistIdOrError() ?: return
        val logoPackUrl = _uiState.value.externalLogoPackUrl.trim()
        if (logoPackUrl.isBlank()) {
            _uiState.update { it.copy(lastError = "Введите URL logo pack") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingMetadata = true, lastError = null, lastInfo = null) }
            when (val result = channelMetadataRepository.refreshMetadataWithLogoPackUrl(playlistId, logoPackUrl)) {
                is AppResult.Success -> {
                    _uiState.value.editDraft.channelId?.let(::loadSelectedMetadata)
                    _uiState.update {
                        it.copy(
                            isRefreshingMetadata = false,
                            lastInfo = "Сетевой logo pack применён, логотипов обновлено: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> _uiState.update {
                    it.copy(isRefreshingMetadata = false, lastError = result.message)
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun toggleChannelFavorite(channelId: Long) {
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

    private fun observePlaylists() {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                _uiState.update { state ->
                    val preferred = state.effectivePlaylistId ?: requestedPlaylistId ?: playlists.firstOrNull()?.id
                    val effective = preferred?.takeIf { id -> playlists.any { it.id == id } }
                        ?: playlists.firstOrNull()?.id
                    if (effective == null) {
                        state.copy(
                            playlists = playlists,
                            effectivePlaylistId = null,
                            channels = emptyList(),
                            selectedChannelIds = emptySet(),
                            editDraft = ChannelEditDraft(),
                            selectedMetadata = null,
                            manualCountryInput = "",
                            manualLanguageInput = "",
                            manualCategoryInput = ""
                        )
                    } else {
                        state.copy(
                            playlists = playlists,
                            effectivePlaylistId = effective
                        )
                    }
                }
                val target = _uiState.value.effectivePlaylistId
                if (target != observedPlaylistId) {
                    observeChannels(target)
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
                    selectedChannelIds = emptySet(),
                    editDraft = ChannelEditDraft()
                )
            }
            return
        }

        channelsJob = viewModelScope.launch {
            playlistRepository.observeChannels(playlistId).collect { channels ->
                _uiState.update { state ->
                    val selected = state.selectedChannelIds.filter { id -> channels.any { it.id == id } }.toSet()
                    val draft = if (state.editDraft.channelId != null) {
                        channels.firstOrNull { it.id == state.editDraft.channelId }?.toDraft() ?: ChannelEditDraft()
                    } else {
                        ChannelEditDraft()
                    }
                    state.copy(
                        channels = channels,
                        selectedChannelIds = selected,
                        editDraft = draft,
                        selectedMetadata = state.selectedMetadata.takeIf { it?.channelId == draft.channelId }
                    )
                }
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            favoritesRepository.observeFavorites().collect { channels ->
                _uiState.update { it.copy(favoriteChannelIds = channels.map { channel -> channel.id }.toSet()) }
            }
        }
    }

    private fun loadSelectedMetadata(channelId: Long) {
        viewModelScope.launch {
            when (val result = channelMetadataRepository.resolveMetadata(channelId)) {
                is AppResult.Success -> _uiState.update { state ->
                    if (state.editDraft.channelId == channelId) {
                        state.copy(
                            selectedMetadata = result.data,
                            manualCountryInput = result.data?.manualCountry ?: result.data?.country.orEmpty(),
                            manualLanguageInput = result.data?.manualLanguage ?: result.data?.language.orEmpty(),
                            manualCategoryInput = result.data?.manualCategory ?: result.data?.category.orEmpty(),
                            lastError = null
                        )
                    } else {
                        state
                    }
                }
                is AppResult.Error -> _uiState.update { state ->
                    if (state.editDraft.channelId == channelId) {
                        state.copy(selectedMetadata = null, lastError = result.message)
                    } else {
                        state
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executeEditorAction(action: suspend () -> AppResult<EditorActionResult>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastInfo = null, exportPreview = null) }
            when (val result = action()) {
                is AppResult.Success -> onActionSuccess(result.data)
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, lastError = result.message) }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun onActionSuccess(result: EditorActionResult) {
        val copySuffix = if (result.createdWorkingCopy) " (создана COW-копия)" else ""
        _uiState.update {
            it.copy(
                isLoading = false,
                effectivePlaylistId = result.effectivePlaylistId,
                selectedChannelIds = emptySet(),
                editDraft = ChannelEditDraft(),
                selectedMetadata = null,
                manualCountryInput = "",
                manualLanguageInput = "",
                manualCategoryInput = "",
                exportedFilePath = null,
                lastInfo = "${result.message}: ${result.affectedCount}$copySuffix",
                lastError = null
            )
        }
        if (result.effectivePlaylistId != observedPlaylistId) {
            observeChannels(result.effectivePlaylistId)
        }
    }

    private fun selectedIdsOrError(): List<Long>? {
        val selected = _uiState.value.selectedChannelIds.toList()
        if (selected.isEmpty()) {
            _uiState.update { it.copy(lastError = "Выберите каналы") }
            return null
        }
        return selected
    }

    private fun currentPlaylistIdOrError(): Long? {
        val playlistId = _uiState.value.effectivePlaylistId
        if (playlistId == null) {
            _uiState.update { it.copy(lastError = "Плейлист не выбран") }
            return null
        }
        return playlistId
    }

    private fun Channel.toDraft(): ChannelEditDraft {
        return ChannelEditDraft(
            channelId = id,
            name = name,
            group = group.orEmpty(),
            logo = logo.orEmpty(),
            streamUrl = streamUrl
        )
    }

    private companion object {
        const val EXPORT_PREVIEW_MAX_LEN = 4000
    }
}

private data class TextExportResult(
    val content: String,
    val playlistsCount: Int,
    val channelsCount: Int
)

internal fun filterEditorChannels(channels: List<Channel>, query: String): List<Channel> {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isBlank()) {
        return channels
    }

    return channels.filter { channel ->
        listOfNotNull(
            channel.name,
            channel.group,
            channel.tvgId,
            channel.logo,
            channel.streamUrl
        ).any { value -> value.lowercase().contains(normalizedQuery) }
    }
}

internal fun filterEditorChannelsWithoutLogo(channels: List<Channel>, query: String): List<Channel> {
    return filterEditorChannels(channels, query).filter { it.logo.isNullOrBlank() }
}

internal const val METADATA_RULE_MATCH_ANY = "match"
internal const val METADATA_RULE_MATCH_NAME = "name"
internal const val METADATA_RULE_MATCH_GROUP = "group"
internal const val METADATA_RULE_MATCH_TVG_ID = "tvg-id"
internal const val METADATA_RULE_MATCH_SOURCE = "source"

internal val metadataRuleMatcherTypes = listOf(
    METADATA_RULE_MATCH_ANY,
    METADATA_RULE_MATCH_NAME,
    METADATA_RULE_MATCH_GROUP,
    METADATA_RULE_MATCH_TVG_ID,
    METADATA_RULE_MATCH_SOURCE
)

data class SharedMetadataRulePack(
    val id: String,
    val title: String,
    val rules: String
)

data class SharedMetadataRulesCatalogInfo(
    val title: String? = null,
    val version: String? = null,
    val updatedAt: String? = null,
    val description: String? = null,
    val checksumSha256: String? = null,
    val computedSha256: String? = null,
    val checksumStatus: SharedRulesCatalogChecksumStatus = SharedRulesCatalogChecksumStatus.NOT_DECLARED
) {
    val hasAnyValue: Boolean
        get() = listOf(title, version, updatedAt, description, checksumSha256).any { !it.isNullOrBlank() }
}

enum class SharedRulesCatalogChecksumStatus {
    NOT_DECLARED,
    VALID,
    INVALID
}

internal val sharedMetadataRulePacks = listOf(
    SharedMetadataRulePack(
        id = "basic-categories",
        title = "Базовые категории",
        rules = """
            # Shared pack: basic categories
            match=news; category=News
            match=новост; category=News
            match=sport; category=Sports
            match=спорт; category=Sports
            match=movie; category=Movies
            match=кино; category=Movies
            match=music; category=Music
            match=музык; category=Music
            match=kids; category=Kids
            match=детск; category=Kids
        """.trimIndent()
    ),
    SharedMetadataRulePack(
        id = "cis-language-country",
        title = "KZ/RU/UA язык и страна",
        rules = """
            # Shared pack: CIS language/country hints
            match=kaz; country=KZ; language=kk
            match=каз; country=KZ; language=kk
            match=kz; country=KZ
            match=rus; country=RU; language=ru
            match=рос; country=RU; language=ru
            match=ukr; country=UA; language=uk
            match=укр; country=UA; language=uk
        """.trimIndent()
    ),
    SharedMetadataRulePack(
        id = "source-domains",
        title = "Домены источников",
        rules = """
            # Shared pack: common source-domain hints
            source=.kz; country=KZ
            source=.ru; country=RU; language=ru
            source=.ua; country=UA; language=uk
            source=.tr; country=TR; language=tr
            source=.de; country=DE; language=de
            source=.fr; country=FR; language=fr
        """.trimIndent()
    )
)

internal fun buildMetadataRuleLine(
    matcherType: String,
    matcherValue: String,
    country: String,
    language: String,
    category: String
): String? {
    val normalizedMatcher = normalizeMetadataRuleMatcherType(matcherType)
    val normalizedMatcherValue = matcherValue.toRuleValue()
    val fields = listOfNotNull(
        normalizedMatcher.takeIf { normalizedMatcherValue.isNotBlank() }?.let { "$it=$normalizedMatcherValue" },
        country.toRuleValue().takeIf { it.isNotBlank() }?.let { "country=$it" },
        language.toRuleValue().takeIf { it.isNotBlank() }?.let { "language=$it" },
        category.toRuleValue().takeIf { it.isNotBlank() }?.let { "category=$it" }
    )
    val hasMetadata = fields.any {
        it.startsWith("country=") || it.startsWith("language=") || it.startsWith("category=")
    }
    return fields.joinToString("; ").takeIf { normalizedMatcherValue.isNotBlank() && hasMetadata }
}

internal fun appendMetadataRulesText(existingRules: String, newRules: String): String {
    val existing = existingRules.trim()
    val incoming = newRules.trim()
    return when {
        existing.isBlank() -> incoming
        incoming.isBlank() -> existing
        else -> "$existing\n$incoming"
    }
}

internal fun normalizeSharedRulesCatalogUrl(value: String): String? {
    val trimmed = value.trim()
    val lower = trimmed.lowercase(Locale.ROOT)
    return trimmed.takeIf {
        lower.startsWith("https://") || lower.startsWith("http://")
    }
}

internal fun parseSharedMetadataRulesCatalogInfo(catalogText: String): SharedMetadataRulesCatalogInfo {
    var title: String? = null
    var version: String? = null
    var updatedAt: String? = null
    var description: String? = null
    var checksumSha256: String? = null

    catalogText.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        if (!line.startsWith("#")) return@forEach

        val content = line.removePrefix("#").trim()
        val key = content.substringBefore(':', missingDelimiterValue = "").trim().lowercase(Locale.ROOT)
        val value = content.substringAfter(':', missingDelimiterValue = "").trim().takeIf { it.isNotBlank() }
            ?: return@forEach

        when (key) {
            "catalog", "title", "name" -> if (title == null) title = value
            "version", "catalog-version" -> if (version == null) version = value
            "updated", "updated-at", "date" -> if (updatedAt == null) updatedAt = value
            "description", "desc" -> if (description == null) description = value
            "sha256", "checksum", "checksum-sha256" -> if (checksumSha256 == null) checksumSha256 = value
        }
    }

    val normalizedChecksum = checksumSha256
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    val computedChecksum = normalizedChecksum?.let {
        catalogText.withoutSharedRulesChecksumHeaders().sha256Hex()
    }
    val checksumStatus = when {
        checksumSha256 == null -> SharedRulesCatalogChecksumStatus.NOT_DECLARED
        normalizedChecksum == null -> SharedRulesCatalogChecksumStatus.INVALID
        normalizedChecksum == computedChecksum -> SharedRulesCatalogChecksumStatus.VALID
        else -> SharedRulesCatalogChecksumStatus.INVALID
    }

    return SharedMetadataRulesCatalogInfo(
        title = title,
        version = version,
        updatedAt = updatedAt,
        description = description,
        checksumSha256 = normalizedChecksum,
        computedSha256 = computedChecksum,
        checksumStatus = checksumStatus
    )
}

internal fun buildSharedRulesCatalogLoadedMessage(
    prefix: String,
    packsCount: Int,
    info: SharedMetadataRulesCatalogInfo
): String {
    val details = listOfNotNull(
        info.title?.takeIf { it.isNotBlank() },
        info.version?.takeIf { it.isNotBlank() }?.let { "v$it" },
        info.updatedAt?.takeIf { it.isNotBlank() }?.let { "updated $it" },
        when (info.checksumStatus) {
            SharedRulesCatalogChecksumStatus.VALID -> "sha256 ok"
            SharedRulesCatalogChecksumStatus.INVALID -> "sha256 mismatch"
            SharedRulesCatalogChecksumStatus.NOT_DECLARED -> null
        }
    ).joinToString(", ")
    return if (details.isBlank()) {
        "$prefix: $packsCount"
    } else {
        "$prefix: $packsCount ($details)"
    }
}

internal fun canonicalSharedRulesCatalogForChecksum(catalogText: String): String {
    return catalogText.withoutSharedRulesChecksumHeaders()
}

internal fun parseSharedMetadataRulePacksCatalog(catalogText: String): List<SharedMetadataRulePack> {
    val packs = mutableListOf<SharedMetadataRulePack>()
    var title: String? = null
    val lines = mutableListOf<String>()

    fun flushPack() {
        val rules = lines.joinToString("\n").trim()
        if (isValidSharedRulesPack(rules)) {
            val packTitle = title?.trim().orEmpty().ifBlank { "Imported pack ${packs.size + 1}" }
            packs += SharedMetadataRulePack(
                id = "external-${packs.size + 1}-${packTitle.toPackId()}",
                title = packTitle,
                rules = rules
            )
        }
        title = null
        lines.clear()
    }

    catalogText.lineSequence().forEach { rawLine ->
        val line = rawLine.trimEnd()
        val nextTitle = line.trim().removePrefix("#").trim()
            .takeIf { it.startsWith("pack:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
        if (nextTitle != null) {
            flushPack()
            title = nextTitle
        } else {
            lines += line
        }
    }
    flushPack()
    return packs
}

private fun isValidSharedRulesPack(rules: String): Boolean {
    return rules.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .any { it.contains('=') && it.contains(';') }
}

internal fun metadataRulePreviewCount(
    channels: List<Channel>,
    matcherType: String,
    matcherValue: String
): Int {
    val needle = matcherValue.trim().lowercase(Locale.ROOT)
    if (needle.isBlank()) return 0
    val normalizedMatcher = normalizeMetadataRuleMatcherType(matcherType)
    return channels.count { channel ->
        when (normalizedMatcher) {
            METADATA_RULE_MATCH_NAME -> channel.name.containsNeedle(needle)
            METADATA_RULE_MATCH_GROUP -> channel.group.containsNeedle(needle)
            METADATA_RULE_MATCH_TVG_ID -> channel.tvgId.containsNeedle(needle)
            METADATA_RULE_MATCH_SOURCE -> channel.streamUrl.containsNeedle(needle)
            else -> listOf(channel.name, channel.group, channel.tvgId, channel.logo, channel.streamUrl)
                .any { it.containsNeedle(needle) }
        }
    }
}

internal fun normalizeMetadataRuleMatcherType(value: String): String {
    return metadataRuleMatcherTypes.firstOrNull { it.equals(value.trim(), ignoreCase = true) }
        ?: METADATA_RULE_MATCH_ANY
}

private fun String?.containsNeedle(needle: String): Boolean {
    return orEmpty().lowercase(Locale.ROOT).contains(needle)
}

private fun String.toRuleValue(): String {
    return trim()
        .replace(';', ' ')
        .replace('=', ' ')
        .replace(Regex("\\s+"), " ")
}

private fun String.toPackId(): String {
    return lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "pack" }
}

private fun String.withoutSharedRulesChecksumHeaders(): String {
    return lineSequence()
        .filterNot { rawLine ->
            val content = rawLine.trim()
                .takeIf { it.startsWith("#") }
                ?.removePrefix("#")
                ?.trim()
                .orEmpty()
            val key = content.substringBefore(':', missingDelimiterValue = "").trim().lowercase(Locale.ROOT)
            key == "sha256" || key == "checksum" || key == "checksum-sha256"
        }
        .joinToString("\n")
        .trim()
}

private fun String.sha256Hex(): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun String.sanitizeFileName(): String {
    return trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(50)
}
