package com.iptv.tv.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Playlist

private data class HomeDashboardActions(
    val onOpenScanner: (() -> Unit)?,
    val onOpenImporter: (() -> Unit)?,
    val onOpenReadyPlaylists: (() -> Unit)?,
    val onOpenPlaylists: (() -> Unit)?,
    val onOpenEpg: (() -> Unit)?,
    val onOpenPlayer: (() -> Unit)?,
    val onOpenSettings: (() -> Unit)?,
    val onOpenDiagnostics: (() -> Unit)?,
    val onPrimaryAction: (() -> Unit)?,
    val primaryLabel: String
)

@Composable
internal fun HomeDashboard(
    state: HomeUiState,
    onWatchPlaylist: (Long) -> Unit,
    onWatchReadyPlaylist: (ReadyPlaylistPreset) -> Unit,
    onOpenScanner: (() -> Unit)?,
    onOpenImporter: (() -> Unit)?,
    onOpenReadyPlaylists: (() -> Unit)?,
    onOpenPlaylists: (() -> Unit)?,
    onOpenEpg: (() -> Unit)?,
    onOpenPlayer: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onOpenDiagnostics: (() -> Unit)?,
    onPrimaryAction: (() -> Unit)?,
    primaryLabel: String
) {
    val actions = HomeDashboardActions(
        onOpenScanner = onOpenScanner,
        onOpenImporter = onOpenImporter,
        onOpenReadyPlaylists = onOpenReadyPlaylists,
        onOpenPlaylists = onOpenPlaylists,
        onOpenEpg = onOpenEpg,
        onOpenPlayer = onOpenPlayer,
        onOpenSettings = onOpenSettings,
        onOpenDiagnostics = onOpenDiagnostics,
        onPrimaryAction = onPrimaryAction,
        primaryLabel = primaryLabel
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (shouldUseWideHomeDashboard(maxWidth.value, maxHeight.value)) {
            WideHomeDashboard(
                state = state,
                onWatchPlaylist = onWatchPlaylist,
                onWatchReadyPlaylist = onWatchReadyPlaylist,
                actions = actions
            )
        } else {
            CompactHomeDashboard(
                state = state,
                onWatchPlaylist = onWatchPlaylist,
                onWatchReadyPlaylist = onWatchReadyPlaylist,
                actions = actions
            )
        }
    }
}

@Composable
private fun WideHomeDashboard(
    state: HomeUiState,
    onWatchPlaylist: (Long) -> Unit,
    onWatchReadyPlaylist: (ReadyPlaylistPreset) -> Unit,
    actions: HomeDashboardActions
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        HomeNavigationRail(
            modifier = Modifier
                .width(188.dp)
                .fillMaxHeight(),
            actions = actions
        )

        TvScrollableLazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(state.title, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        state.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            item {
                HomeVideoHero(
                    isImporting = state.isImporting,
                    onOpenPlayer = actions.onOpenPlayer
                )
            }

            statusItems(state)

            if (state.playlists.isNotEmpty()) {
                item { Text("Мои списки каналов", style = MaterialTheme.typography.titleLarge) }
                items(state.playlists.take(6), key = { it.id }) { playlist ->
                    SavedPlaylistCard(
                        playlist = playlist,
                        enabled = !state.isImporting,
                        onWatch = { onWatchPlaylist(playlist.id) }
                    )
                }
                if (state.playlists.size > 6) {
                    actions.onOpenPlaylists?.let { action ->
                        item {
                            OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                                Text("Показать все мои списки (${state.playlists.size})")
                            }
                        }
                    }
                }
            }
        }

        TvScrollableLazyColumn(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Быстрые источники", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Готовые списки для быстрого запуска просмотра",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            readyPlaylistItems(
                state = state,
                onWatchReadyPlaylist = onWatchReadyPlaylist
            )

            actions.onOpenScanner?.let { action ->
                item {
                    Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
                        Text("Найти новые списки")
                    }
                }
            }

            actions.onPrimaryAction?.let { action ->
                item {
                    OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                        Text(actions.primaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactHomeDashboard(
    state: HomeUiState,
    onWatchPlaylist: (Long) -> Unit,
    onWatchReadyPlaylist: (ReadyPlaylistPreset) -> Unit,
    actions: HomeDashboardActions
) {
    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(state.title, style = MaterialTheme.typography.headlineMedium)
                Text(state.description, style = MaterialTheme.typography.bodyLarge)
            }
        }

        item {
            HomeVideoHero(
                isImporting = state.isImporting,
                onOpenPlayer = actions.onOpenPlayer
            )
        }

        statusItems(state)

        if (state.playlists.isNotEmpty()) {
            item { Text("Мои списки каналов", style = MaterialTheme.typography.titleLarge) }
            items(state.playlists.take(8), key = { it.id }) { playlist ->
                SavedPlaylistCard(
                    playlist = playlist,
                    enabled = !state.isImporting,
                    onWatch = { onWatchPlaylist(playlist.id) }
                )
            }
            if (state.playlists.size > 8) {
                actions.onOpenPlaylists?.let { action ->
                    item {
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
                    "Выберите источник — приложение загрузит его и откроет в плеере.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        readyPlaylistItems(
            state = state,
            onWatchReadyPlaylist = onWatchReadyPlaylist
        )

        actions.onOpenScanner?.let { action ->
            item {
                Button(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text("Найти новые списки в сканере")
                }
            }
        }

        item { HomeNavigationActions(actions) }

        actions.onPrimaryAction?.let { action ->
            item {
                OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
                    Text(actions.primaryLabel)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.statusItems(state: HomeUiState) {
    if (state.isImporting) {
        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
    }
    state.lastError?.let { error ->
        item { Text(error, color = MaterialTheme.colorScheme.error) }
    }
    state.lastInfo?.let { info ->
        item { Text(info, color = MaterialTheme.colorScheme.primary) }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.readyPlaylistItems(
    state: HomeUiState,
    onWatchReadyPlaylist: (ReadyPlaylistPreset) -> Unit
) {
    items(READY_PLAYLIST_PRESETS, key = { it.url }) { preset ->
        ReadyPlaylistCard(
            preset = preset,
            importing = state.importingUrl == preset.url,
            enabled = !state.isImporting,
            onWatch = { onWatchReadyPlaylist(preset) }
        )
    }
}

@Composable
private fun HomeNavigationRail(
    modifier: Modifier,
    actions: HomeDashboardActions
) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Text("Rinat IPTV", style = MaterialTheme.typography.titleLarge)
            Text(
                "Главная",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            HomeNavigationButtons(actions)
        }
    }
}

@Composable
private fun HomeNavigationActions(actions: HomeDashboardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Другие разделы", style = MaterialTheme.typography.titleMedium)
        HomeNavigationButtons(actions)
    }
}

@Composable
private fun HomeNavigationButtons(actions: HomeDashboardActions) {
    navigationActions(actions).forEach { (label, action) ->
        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun navigationActions(actions: HomeDashboardActions): List<Pair<String, () -> Unit>> =
    listOfNotNull(
        actions.onOpenPlaylists?.let { "Мои плейлисты" to it },
        actions.onOpenReadyPlaylists?.let { "Готовые списки" to it },
        actions.onOpenImporter?.let { "Импорт" to it },
        actions.onOpenEpg?.let { "Телепрограмма" to it },
        actions.onOpenPlayer?.let { "Плеер" to it },
        actions.onOpenSettings?.let { "Настройки" to it },
        actions.onOpenDiagnostics?.let { "Диагностика" to it }
    )

@Composable
private fun HomeVideoHero(
    isImporting: Boolean,
    onOpenPlayer: (() -> Unit)?
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = if (isImporting) {
                        "Загрузка списка каналов…"
                    } else {
                        "Выберите список каналов или откройте плеер"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge
                )
                if (!isImporting) {
                    onOpenPlayer?.let { action ->
                        Button(onClick = action) {
                            Text("Открыть плеер")
                        }
                    }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text("Каналов: ${playlist.channelCount}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onWatch, enabled = enabled) {
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                preset.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                preset.note,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                preset.url,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onWatch, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(if (importing) "Загрузка…" else "Смотреть")
            }
        }
    }
}
