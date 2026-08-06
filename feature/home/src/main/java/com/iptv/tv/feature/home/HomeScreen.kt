package com.iptv.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Playlist

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenScanner: (() -> Unit)? = null,
    onOpenImporter: (() -> Unit)? = null,
    onOpenReadyPlaylists: (() -> Unit)? = null,
    onOpenPlaylists: (() -> Unit)? = null,
    onOpenPlaylist: ((Long) -> Unit)? = null,
    onOpenEpg: (() -> Unit)? = null,
    onOpenPlayer: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Открыть",
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.pendingOpenPlaylistId) {
        val playlistId = state.pendingOpenPlaylistId ?: return@LaunchedEffect
        if (onOpenPlaylist != null) {
            onOpenPlaylist(playlistId)
        } else {
            onOpenPlayer?.invoke()
        }
        viewModel.consumeOpenPlaylistRequest()
    }

    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
                Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (state.isImporting) "Загрузка списка каналов…" else "Выберите список каналов для просмотра",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }

        if (state.isImporting) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state.lastError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        state.lastInfo?.let { info ->
            item { Text(info, color = MaterialTheme.colorScheme.primary) }
        }

        if (state.playlists.isNotEmpty()) {
            item {
                Text("Мои списки каналов", style = MaterialTheme.typography.titleLarge)
            }
            items(state.playlists.take(8), key = { it.id }) { playlist ->
                SavedPlaylistCard(
                    playlist = playlist,
                    enabled = !state.isImporting,
                    onWatch = { viewModel.requestOpenPlaylist(playlist.id) }
                )
            }
            if (state.playlists.size > 8) {
                item {
                    onOpenPlaylists?.let { action ->
                        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                            Text("Показать все мои списки (${state.playlists.size})")
                        }
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Готовые списки", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Они уже добавлены в приложение как быстрые источники. Нажмите «Смотреть» — список загрузится и откроется в плеере.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        items(READY_PLAYLIST_PRESETS, key = { it.url }) { preset ->
            ReadyPlaylistCard(
                preset = preset,
                importing = state.importingUrl == preset.url,
                enabled = !state.isImporting,
                onWatch = { viewModel.watchReadyPlaylist(preset) }
            )
        }

        item {
            onOpenScanner?.let { action ->
                Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text("Найти новые списки в сканере")
                }
            }
        }

        item {
            Text("Другие разделы", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                onOpenPlaylists?.let { HomeActionCard("Мои плейлисты", "Полный список и редактор", it) }
                onOpenReadyPlaylists?.let { HomeActionCard("Каталог готовых", "Отдельный каталог источников", it) }
                onOpenImporter?.let { HomeActionCard("Ручной импорт", "URL, файл или текст M3U", it) }
                onOpenEpg?.let { HomeActionCard("Телепрограмма", "EPG по сохранённым каналам", it) }
                onOpenPlayer?.let { HomeActionCard("Плеер", "Открыть без выбора списка", it) }
                onOpenSettings?.let { HomeActionCard("Настройки", "Плеер, буфер и сеть", it) }
                onOpenDiagnostics?.let { HomeActionCard("Логи", "Ошибки и диагностика", it) }
            }
        }

        onPrimaryAction?.let { action ->
            item {
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

@Composable
private fun SavedPlaylistCard(
    playlist: Playlist,
    enabled: Boolean,
    onWatch: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium)
            Text(
                "Каналов: ${playlist.channelCount}",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onWatch, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Смотреть")
            }
        }
    }
}

@Composable
private fun ReadyPlaylistCard(
    preset: ReadyPlaylistPreset,
    importing: Boolean,
    enabled: Boolean,
    onWatch: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(preset.name, style = MaterialTheme.typography.titleMedium)
            Text(preset.note, style = MaterialTheme.typography.bodySmall)
            Text(
                preset.url,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onWatch, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (importing) "Загрузка…" else "Смотреть")
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.49f)
            .tvFocusOutline()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                Text("Открыть")
            }
        }
    }
}
