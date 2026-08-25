package com.iptv.tv.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

/**
 * Production Home dispatcher.
 *
 * All viewport classes stay in the same README-aligned design family. The old HomeDashboard is
 * intentionally not selected here as a compact/windowed fallback.
 */
@Composable
internal fun HomeResponsiveDashboard(
    state: HomeUiState,
    onWatchPlaylist: (Long) -> Unit,
    onWatchChannel: (Long, Long) -> Unit,
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (shouldUseWideHomeDashboard(maxWidth.value, maxHeight.value)) {
            HomeReadmeDashboard(
                state = state,
                onWatchPlaylist = onWatchPlaylist,
                onWatchChannel = onWatchChannel,
                onWatchReadyPlaylist = onWatchReadyPlaylist,
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
        } else {
            HomeCompactReadmeDashboard(
                state = state,
                onWatchPlaylist = onWatchPlaylist,
                onWatchChannel = onWatchChannel,
                onWatchReadyPlaylist = onWatchReadyPlaylist,
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
        }
    }
}

@Composable
private fun HomeCompactReadmeDashboard(
    state: HomeUiState,
    onWatchPlaylist: (Long) -> Unit,
    onWatchChannel: (Long, Long) -> Unit,
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
    val activePlaylist = remember(state.playlists, state.channelRailPlaylistId) {
        state.channelRailPlaylistId?.let { id -> state.playlists.firstOrNull { it.id == id } }
            ?: state.playlists.firstOrNull()
    }
    val railChannels = remember(state.channelRailChannels) {
        homeChannelRailItems(state.channelRailChannels)
    }
    val railListState = rememberLazyListState()
    val railFocusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        Color(0xFF06131E),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        TvScrollableLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = state.description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            "LIVE TV",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusOutline(),
                    shape = MaterialTheme.shapes.large,
                    color = Color.Black,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 0.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                                        Color(0xFF0D2540),
                                        Color.Black
                                    )
                                )
                            )
                            .padding(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.align(Alignment.TopStart),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                "ПРЯМОЙ ЭФИР",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                activePlaylist?.name ?: "Ваше телевидение в одном экране",
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                activePlaylist?.let { "${it.channelCount} каналов" }
                                    ?: "Добавьте список или откройте плеер",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Row(
                            modifier = Modifier.align(Alignment.BottomStart),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val primaryAction: (() -> Unit)? = activePlaylist?.let { playlist ->
                                { onWatchPlaylist(playlist.id) }
                            } ?: onOpenPlayer
                            primaryAction?.let { action ->
                                Button(
                                    onClick = action,
                                    enabled = !state.isImporting,
                                    modifier = Modifier.tvFocusOutline()
                                ) {
                                    Text(if (state.isImporting) "Загрузка…" else "Смотреть")
                                }
                            }
                            onOpenPlaylists?.let { action ->
                                OutlinedButton(
                                    onClick = action,
                                    modifier = Modifier.tvFocusOutline()
                                ) {
                                    Text("Плейлисты")
                                }
                            }
                        }
                    }
                }
            }

            if (state.isImporting) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }
            state.lastError?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            state.lastInfo?.let { message ->
                item {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            item {
                Text("Разделы", style = MaterialTheme.typography.titleMedium)
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val actions = listOfNotNull(
                        onOpenPlayer?.let { "Эфир" to it },
                        onOpenPlaylists?.let { "Плейлисты" to it },
                        onOpenReadyPlaylists?.let { "Готовые" to it },
                        onOpenImporter?.let { "Импорт" to it },
                        onOpenEpg?.let { "Программа" to it },
                        onOpenScanner?.let { "Сканер" to it },
                        onOpenSettings?.let { "Настройки" to it },
                        onOpenDiagnostics?.let { "Диагностика" to it }
                    )
                    items(actions, key = { it.first }) { (label, action) ->
                        OutlinedButton(
                            onClick = action,
                            modifier = Modifier.tvFocusOutline()
                        ) {
                            Text(label, maxLines = 1)
                        }
                    }
                }
            }

            if (activePlaylist != null && railChannels.isNotEmpty()) {
                item {
                    HomeChannelRail(
                        playlistId = activePlaylist.id,
                        playlistName = activePlaylist.name,
                        channels = railChannels,
                        selectedChannelId = state.channelRailSelectedChannelId,
                        enabled = !state.isImporting,
                        listState = railListState,
                        focusRequester = railFocusRequester,
                        onZoneFocused = {},
                        requestMainFocus = { false },
                        onWatchChannel = onWatchChannel
                    )
                }
            }

            if (state.playlists.isNotEmpty()) {
                item { Text("Мои плейлисты", style = MaterialTheme.typography.titleMedium) }
                items(state.playlists.take(5), key = { it.id }) { playlist ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${playlist.channelCount} каналов",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(
                                onClick = { onWatchPlaylist(playlist.id) },
                                enabled = !state.isImporting,
                                modifier = Modifier.tvFocusOutline()
                            ) { Text("Смотреть") }
                        }
                    }
                }
            }

            item { Text("Быстрый старт", style = MaterialTheme.typography.titleMedium) }
            items(READY_PLAYLIST_PRESETS, key = { it.url }) { preset ->
                OutlinedButton(
                    onClick = { onWatchReadyPlaylist(preset) },
                    enabled = !state.isImporting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusOutline()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (state.importingUrl == preset.url) "Загрузка…" else preset.note,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            onOpenScanner?.let { action ->
                item {
                    Button(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusOutline()
                    ) { Text("Найти новые каналы") }
                }
            }
            onPrimaryAction?.let { action ->
                item {
                    OutlinedButton(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusOutline()
                    ) { Text(primaryLabel) }
                }
            }
        }
    }
}
