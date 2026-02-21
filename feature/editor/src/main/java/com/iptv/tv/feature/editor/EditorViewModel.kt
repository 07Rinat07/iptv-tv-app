package com.iptv.tv.feature.editor

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.PlaylistEditorRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EditorActionResult
import com.iptv.tv.core.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
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
    val exportPreview: String? = null,
    val exportFileExtension: String = "m3u",
    val exportedFilePath: String? = null,
    val isLoading: Boolean = false,
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val playlistRepository: PlaylistRepository,
    private val editorRepository: PlaylistEditorRepository,
    private val favoritesRepository: FavoritesRepository,
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
                exportedFilePath = null,
                lastError = null,
                lastInfo = null
            )
        }
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

    fun clearSelection() {
        _uiState.update {
            it.copy(
                selectedChannelIds = emptySet(),
                editDraft = ChannelEditDraft(),
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
                val targetDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: appContext.filesDir
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

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
                val file = File(targetDir, fileName)
                file.writeText(content)
                file.absolutePath
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
                exportedFilePath = null,
                lastError = null,
                lastInfo = null
            )
        }
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
                            editDraft = ChannelEditDraft()
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
                        editDraft = draft
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

private fun String.sanitizeFileName(): String {
    return trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(50)
}

