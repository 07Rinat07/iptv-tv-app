package com.iptv.tv.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Playlist

/**
 * Wide TV-first Home presentation derived from the visual direction documented in README.
 *
 * This screen deliberately does not create a Player/P2P runtime. The central area is a launch
 * surface only; playback still starts through the existing navigation callbacks.
 */
@Composable
internal fun HomeReadmeDashboard(
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        Color(0xFF06131E),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        if (!shouldUseWideHomeDashboard(maxWidth.value, maxHeight.value)) {
            HomeDashboard(
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
            return@BoxWithConstraints
        }

        val heroFocusRequester = remember { FocusRequester() }
        val channelRailFocusRequester = remember { FocusRequester() }
        val channelRailListState = rememberLazyListState()
        val heroPlaylist = remember(state.playlists, state.channelRailPlaylistId) {
            state.channelRailPlaylistId?.let { playlistId ->
                state.playlists.firstOrNull { it.id == playlistId }
            } ?: state.playlists.firstOrNull()
        }
        val railChannels = remember(state.channelRailChannels) {
            homeChannelRailItems(state.channelRailChannels)
        }

        LaunchedEffect(Unit) {
            runCatching { heroFocusRequester.requestFocus() }
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ReadmeHomeNavigationRail(
                modifier = Modifier
                    .width(190.dp)
                    .fillMaxHeight(),
                onOpenPlayer = onOpenPlayer,
                onOpenPlaylists = onOpenPlaylists,
                onOpenReadyPlaylists = onOpenReadyPlaylists,
                onOpenImporter = onOpenImporter,
                onOpenEpg = onOpenEpg,
                onOpenScanner = onOpenScanner,
                onOpenSettings = onOpenSettings,
                onOpenDiagnostics = onOpenDiagnostics
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ReadmeHomeHeader(state)

                ReadmeHomeHero(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = state,
                    playlist = heroPlaylist,
                    heroFocusRequester = heroFocusRequester,
                    onWatchPlaylist = onWatchPlaylist,
                    onOpenPlayer = onOpenPlayer,
                    onOpenPlaylists = onOpenPlaylists
                )

                if (state.isImporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.lastError?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                state.lastInfo?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (heroPlaylist != null && railChannels.isNotEmpty()) {
                    HomeChannelRail(
                        playlistId = heroPlaylist.id,
                        playlistName = heroPlaylist.name,
                        channels = railChannels,
                        selectedChannelId = state.channelRailSelectedChannelId,
                        enabled = !state.isImporting,
                        listState = channelRailListState,
                        focusRequester = channelRailFocusRequester,
                        onZoneFocused = {},
                        requestMainFocus = {
                            runCatching { heroFocusRequester.requestFocus() }.isSuccess
                        },
                        onWatchChannel = onWatchChannel
                    )
                }
            }

            ReadmeHomeQuickSources(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                state = state,
                onWatchReadyPlaylist = onWatchReadyPlaylist,
                onOpenScanner = onOpenScanner,
                onOpenReadyPlaylists = onOpenReadyPlaylists,
                onPrimaryAction = onPrimaryAction,
                primaryLabel = primaryLabel
            )
        }
    }
}

@Composable
private fun ReadmeHomeHeader(state: HomeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = state.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.primaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        ) {
            Text(
                text = "LIVE TV",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ReadmeHomeNavigationRail(
    modifier: Modifier,
    onOpenPlayer: (() -> Unit)?,
    onOpenPlaylists: (() -> Unit)?,
    onOpenReadyPlaylists: (() -> Unit)?,
    onOpenImporter: (() -> Unit)?,
    onOpenEpg: (() -> Unit)?,
    onOpenScanner: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    onOpenDiagnostics: (() -> Unit)?
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "R",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    "Rinat IPTV",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = "Главная",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            HomeRailAction("Эфир", onOpenPlayer)
            HomeRailAction("Плейлисты", onOpenPlaylists)
            HomeRailAction("Готовые списки", onOpenReadyPlaylists)
            HomeRailAction("Импорт", onOpenImporter)
            HomeRailAction("Телепрограмма", onOpenEpg)
            HomeRailAction("Сканер", onOpenScanner)
            HomeRailAction("Настройки", onOpenSettings)
            HomeRailAction("Диагностика", onOpenDiagnostics)

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "TV • D-pad • Mouse",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Media3 • LibVLC • P2P",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HomeRailAction(label: String, action: (() -> Unit)?) {
    if (action == null) return
    OutlinedButton(
        onClick = action,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusOutline()
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ReadmeHomeHero(
    modifier: Modifier,
    state: HomeUiState,
    playlist: Playlist?,
    heroFocusRequester: FocusRequester,
    onWatchPlaylist: (Long) -> Unit,
    onOpenPlayer: (() -> Unit)?,
    onOpenPlaylists: (() -> Unit)?
) {
    val primaryAction: (() -> Unit)? = when {
        playlist != null -> ({ onWatchPlaylist(playlist.id) })
        onOpenPlayer != null -> onOpenPlayer
        else -> null
    }
    val primaryLabel = when {
        state.isImporting -> "Загрузка…"
        playlist != null -> "Смотреть эфир"
        else -> "Открыть плеер"
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.74f),
                            Color(0xFF0D2540),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.TopStart),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "ПРЯМОЙ ЭФИР",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = playlist?.name ?: "Ваше телевидение в одном экране",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = playlist?.let { "${it.channelCount} каналов • быстрый переход в Player" }
                        ?: "Выберите плейлист, готовый источник или запустите поиск каналов.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                primaryAction?.let { action ->
                    Button(
                        onClick = action,
                        enabled = !state.isImporting,
                        modifier = Modifier
                            .focusRequester(heroFocusRequester)
                            .tvFocusOutline()
                    ) {
                        Text(primaryLabel)
                    }
                }
                onOpenPlaylists?.let { action ->
                    OutlinedButton(
                        onClick = action,
                        enabled = !state.isImporting,
                        modifier = Modifier.tvFocusOutline()
                    ) {
                        Text("Мои плейлисты")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "HOME • LIVE",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun ReadmeHomeQuickSources(
    modifier: Modifier,
    state: HomeUiState,
    onWatchReadyPlaylist: (ReadyPlaylistPreset) -> Unit,
    onOpenScanner: (() -> Unit)?,
    onOpenReadyPlaylists: (() -> Unit)?,
    onPrimaryAction: (() -> Unit)?,
    primaryLabel: String
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        TvScrollableLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Быстрый старт", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Готовые источники и поиск новых каналов",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

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
                        Text(
                            text = preset.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (state.importingUrl == preset.url) "Загрузка…" else preset.note,
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
                    ) {
                        Text("Найти каналы")
                    }
                }
            }

            onOpenReadyPlaylists?.let { action ->
                item {
                    OutlinedButton(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusOutline()
                    ) {
                        Text("Все готовые списки")
                    }
                }
            }

            onPrimaryAction?.let { action ->
                item {
                    OutlinedButton(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .tvFocusOutline()
                    ) {
                        Text(primaryLabel)
                    }
                }
            }
        }
    }
}
