package com.iptv.tv.feature.favorites

import android.content.ContentValues
import android.content.Context
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
import com.iptv.tv.core.model.FavoritesShareableExportFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
    val isExporting: Boolean = false,
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
                    val selectedId = state.selectedChannelId?.takeIf { id -> channels.any { it.id == id } }
                    state.copy(
                        channels = channels,
                        epgProgramsByChannel = state.epgProgramsByChannel.filterKeys { id ->
                            channels.any { it.id == id }
                        },
                        selectedChannelId = selectedId ?: channels.firstOrNull()?.id
                    )
                }
                loadFavoritesEpg(channels)
            }
        }
    }

    fun selectChannel(channelId: Long) {
        _uiState.update { it.copy(selectedChannelId = channelId, lastError = null) }
    }

    fun removeSelectedFromFavorites() {
        val selected = _uiState.value.selectedChannelId
        if (selected == null) {
            _uiState.update { it.copy(lastError = "Канал не выбран") }
            return
        }
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
        if (_uiState.value.channels.isEmpty()) {
            _uiState.update { it.copy(lastError = "Избранных каналов пока нет", lastInfo = null) }
            return
        }
        if (_uiState.value.isExporting) return

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

private data class ExportPayload(
    val content: String,
    val infoSuffix: String
)

private data class SavedExport(
    val path: String,
    val infoSuffix: String
)

private const val FAVORITES_EPG_WINDOW_MS = 3 * 60 * 60 * 1000L
private val FAVORITES_EXPORT_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Oral")

private fun favoriteExportTimestamp(): String {
    return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
        timeZone = FAVORITES_EXPORT_TIME_ZONE
    }.format(Date())
}
