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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Playlist

private const val HOME_VIDEO_FOCUS_KEY = "content:video"
private const val HOME_SHOW_ALL_PLAYLISTS_FOCUS_KEY = "content:playlists:all"
private const val HOME_SCANNER_FOCUS_KEY = "sources:scanner"
private const val HOME_PRIMARY_FOCUS_KEY = "sources:primary"

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

private data class HomeNavigationAction(
    val focusKey: String,
    val label: String,
    val onClick: () -> Unit
)

private data class HomeDashboardFocusContext(
    val restoreKey: String?,
    val restorePending: Boolean,
    val onFocused: (String) -> Unit,
    val onRestoreConsumed: () -> Unit
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
    val navigationEntries = navigationActions(actions)
    val navigationAnchorKey = navigationEntries.firstOrNull()?.focusKey
    val contentAnchorKey = when {
        !state.isImporting && actions.onOpenPlayer != null -> HOME_VIDEO_FOCUS_KEY
        !state.isImporting && state.playlists.isNotEmpty() -> savedPlaylistFocusKey(state.playlists.first().id)
        state.playlists.size > 6 && actions.onOpenPlaylists != null -> HOME_SHOW_ALL_PLAYLISTS_FOCUS_KEY
        else -> null
    }
    val sourcesAnchorKey = when {
        !state.isImporting && READY_PLAYLIST_PRESETS.isNotEmpty() -> readyPlaylistFocusKey(READY_PLAYLIST_PRESETS.first().url)
        actions.onOpenScanner != null -> HOME_SCANNER_FOCUS_KEY
        actions.onPrimaryAction != null -> HOME_PRIMARY_FOCUS_KEY
        else -> null
    }

    val availableFocusKeys = buildList {
        addAll(navigationEntries.map(HomeNavigationAction::focusKey))
        if (!state.isImporting && actions.onOpenPlayer != null) add(HOME_VIDEO_FOCUS_KEY)
        if (!state.isImporting) {
            addAll(state.playlists.take(6).map { savedPlaylistFocusKey(it.id) })
        }
        if (state.playlists.size > 6 && actions.onOpenPlaylists != null) {
            add(HOME_SHOW_ALL_PLAYLISTS_FOCUS_KEY)
        }
        if (!state.isImporting) {
            addAll(READY_PLAYLIST_PRESETS.map { readyPlaylistFocusKey(it.url) })
        }
        if (actions.onOpenScanner != null) add(HOME_SCANNER_FOCUS_KEY)
        if (actions.onPrimaryAction != null) add(HOME_PRIMARY_FOCUS_KEY)
    }
    val fallbackFocusKey = navigationAnchorKey ?: contentAnchorKey ?: sourcesAnchorKey
    var savedFocusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var restorePending by remember { mutableStateOf(true) }
    val restoreKey = resolveHomeDashboardRestoreKey(
        savedKey = savedFocusKey,
        availableKeys = availableFocusKeys,
        fallbackKey = fallbackFocusKey
    )
    val focusContext = HomeDashboardFocusContext(
        restoreKey = restoreKey,
        restorePending = restorePending,
        onFocused = { savedFocusKey = it },
        onRestoreConsumed = { restorePending = false }
    )

    val navigationAnchorRequester = remember { FocusRequester() }
    val contentAnchorRequester = remember { FocusRequester() }
    val sourcesAnchorRequester = remember { FocusRequester() }
    val availableSections = buildSet {
        if (navigationAnchorKey != null) add(HomeDashboardFocusSection.NAVIGATION)
        if (contentAnchorKey != null) add(HomeDashboardFocusSection.CONTENT)
        if (sourcesAnchorKey != null) add(HomeDashboardFocusSection.SOURCES)
    }
    val sectionRequester: (HomeDashboardFocusSection) -> FocusRequester? = { section ->
        when (section) {
            HomeDashboardFocusSection.NAVIGATION -> navigationAnchorRequester.takeIf { navigationAnchorKey != null }
            HomeDashboardFocusSection.CONTENT -> contentAnchorRequester.takeIf { contentAnchorKey != null }
            HomeDashboardFocusSection.SOURCES -> sourcesAnchorRequester.takeIf { sourcesAnchorKey != null }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        HomeNavigationRail(
            modifier = Modifier
                .width(188.dp)
                .fillMaxHeight()
                .homeDashboardHorizontalNavigation(
                    currentSection = HomeDashboardFocusSection.NAVIGATION,
                    availableSections = availableSections,
                    sectionRequester = sectionRequester
                ),
            actions = actions,
            entries = navigationEntries,
            focusContext = focusContext,
            anchorKey = navigationAnchorKey,
            anchorRequester = navigationAnchorRequester
        )

        TvScrollableLazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .homeDashboardHorizontalNavigation(
                    currentSection = HomeDashboardFocusSection.CONTENT,
                    availableSections = availableSections,
                    sectionRequester = sectionRequester
                ),
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
                    onOpenPlayer = actions.onOpenPlayer,
                    focusKey = HOME_VIDEO_FOCUS_KEY.takeIf { !state.isImporting && actions.onOpenPlayer != null },
                    focusContext = focusContext,
                    anchorKey = contentAnchorKey,
                    anchorRequester = contentAnchorRequester
                )
            }

            statusItems(state)

            if (state.playlists.isNotEmpty()) {
                item { Text("Мои списки каналов", style = MaterialTheme.typography.titleLarge) }
                items(state.playlists.take(6), key = { it.id }) { playlist ->
                    SavedPlaylistCard(
                        playlist = playlist,
                        enabled = !state.isImporting,
                        onWatch = { onWatchPlaylist(playlist.id) },
                        focusKey = savedPlaylistFocusKey(playlist.id).takeIf { !state.isImporting },
                        focusContext = focusContext,
                        anchorKey = contentAnchorKey,
                        anchorRequester = contentAnchorRequester
                    )
                }
                if (state.playlists.size > 6) {
                    actions.onOpenPlaylists?.let { action ->
                        item {
                            OutlinedButton(
                                onClick = action,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .homeDashboardFocusItem(
                                        focusKey = HOME_SHOW_ALL_PLAYLISTS_FOCUS_KEY,
                                        focusContext = focusContext,
                                        anchorKey = contentAnchorKey,
                                        anchorRequester = contentAnchorRequester
                                    )
                            ) {
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
                .fillMaxHeight()
                .homeDashboardHorizontalNavigation(
                    currentSection = HomeDashboardFocusSection.SOURCES,
                    availableSections = availableSections,
                    sectionRequester = sectionRequester
                ),
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
                onWatchReadyPlaylist = onWatchReadyPlaylist,
                focusContext = focusContext,
                anchorKey = sourcesAnchorKey,
                anchorRequester = sourcesAnchorRequester
            )

            actions.onOpenScanner?.let { action ->
                item {
                    Button(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .homeDashboardFocusItem(
                                focusKey = HOME_SCANNER_FOCUS_KEY,
                                focusContext = focusContext,
                                anchorKey = sourcesAnchorKey,
                                anchorRequester = sourcesAnchorRequester
                            )
                    ) {
                        Text("Найти новые списки")
                    }
                }
            }

            actions.onPrimaryAction?.let { action ->
                item {
                    OutlinedButton(
                        onClick = action,
                        modifier = Modifier
                            .fillMaxWidth()
                            .homeDashboardFocusItem(
                                focusKey = HOME_PRIMARY_FOCUS_KEY,
                                focusContext = focusContext,
                                anchorKey = sourcesAnchorKey,
                                anchorRequester = sourcesAnchorRequester
                            )
                    ) {
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
    onWatchReadyPlaylist: (ReadyPlaylistPreset) -> Unit,
    focusContext: HomeDashboardFocusContext? = null,
    anchorKey: String? = null,
    anchorRequester: FocusRequester? = null
) {
    items(READY_PLAYLIST_PRESETS, key = { it.url }) { preset ->
        ReadyPlaylistCard(
            preset = preset,
            importing = state.importingUrl == preset.url,
            enabled = !state.isImporting,
            onWatch = { onWatchReadyPlaylist(preset) },
            focusKey = readyPlaylistFocusKey(preset.url).takeIf { !state.isImporting },
            focusContext = focusContext,
            anchorKey = anchorKey,
            anchorRequester = anchorRequester
        )
    }
}

@Composable
private fun HomeNavigationRail(
    modifier: Modifier,
    actions: HomeDashboardActions,
    entries: List<HomeNavigationAction> = navigationActions(actions),
    focusContext: HomeDashboardFocusContext? = null,
    anchorKey: String? = null,
    anchorRequester: FocusRequester? = null
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
            HomeNavigationButtons(
                entries = entries,
                focusContext = focusContext,
                anchorKey = anchorKey,
                anchorRequester = anchorRequester
            )
        }
    }
}

@Composable
private fun HomeNavigationActions(actions: HomeDashboardActions) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Другие разделы", style = MaterialTheme.typography.titleMedium)
        HomeNavigationButtons(entries = navigationActions(actions))
    }
}

@Composable
private fun HomeNavigationButtons(
    entries: List<HomeNavigationAction>,
    focusContext: HomeDashboardFocusContext? = null,
    anchorKey: String? = null,
    anchorRequester: FocusRequester? = null
) {
    entries.forEach { entry ->
        OutlinedButton(
            onClick = entry.onClick,
            modifier = Modifier
                .fillMaxWidth()
                .homeDashboardFocusItem(
                    focusKey = entry.focusKey.takeIf { focusContext != null },
                    focusContext = focusContext,
                    anchorKey = anchorKey,
                    anchorRequester = anchorRequester
                )
        ) {
            Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun navigationActions(actions: HomeDashboardActions): List<HomeNavigationAction> =
    listOfNotNull(
        actions.onOpenPlaylists?.let {
            HomeNavigationAction("navigation:playlists", "Мои плейлисты", it)
        },
        actions.onOpenReadyPlaylists?.let {
            HomeNavigationAction("navigation:ready", "Готовые списки", it)
        },
        actions.onOpenImporter?.let {
            HomeNavigationAction("navigation:import", "Импорт", it)
        },
        actions.onOpenEpg?.let {
            HomeNavigationAction("navigation:epg", "Телепрограмма", it)
        },
        actions.onOpenPlayer?.let {
            HomeNavigationAction("navigation:player", "Плеер", it)
        },
        actions.onOpenSettings?.let {
            HomeNavigationAction("navigation:settings", "Настройки", it)
        },
        actions.onOpenDiagnostics?.let {
            HomeNavigationAction("navigation:diagnostics", "Диагностика", it)
        }
    )

@Composable
private fun HomeVideoHero(
    isImporting: Boolean,
    onOpenPlayer: (() -> Unit)?,
    focusKey: String? = null,
    focusContext: HomeDashboardFocusContext? = null,
    anchorKey: String? = null,
    anchorRequester: FocusRequester? = null
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
                        Button(
                            onClick = action,
                            modifier = Modifier.homeDashboardFocusItem(
                                focusKey = focusKey,
                                focusContext = focusContext,
                                anchorKey = anchorKey,
                                anchorRequester = anchorRequester
                            )
                        ) {
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
    onWatch: () -> Unit,
    focusKey: String? = null,
    focusContext: HomeDashboardFocusContext? = null,
    anchorKey: String? = null,
    anchorRequester: FocusRequester? = null
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
            Button(
                onClick = onWatch,
                enabled = enabled,
                modifier = Modifier.homeDashboardFocusItem(
                    focusKey = focusKey,
                    focusContext = focusContext,
                    anchorKey = anchorKey,
                    anchorRequester = anchorRequester
                )
            ) {
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
    onWatch: () -> Unit,
    focusKey: String? = null,
    focusContext: HomeDashboardFocusContext? = null,
    anchorKey: String? = null,
    anchorRequester: FocusRequester? = null
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
            Button(
                onClick = onWatch,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .homeDashboardFocusItem(
                        focusKey = focusKey,
                        focusContext = focusContext,
                        anchorKey = anchorKey,
                        anchorRequester = anchorRequester
                    )
            ) {
                Text(if (importing) "Загрузка…" else "Смотреть")
            }
        }
    }
}

@Composable
private fun Modifier.homeDashboardFocusItem(
    focusKey: String?,
    focusContext: HomeDashboardFocusContext?,
    anchorKey: String?,
    anchorRequester: FocusRequester?
): Modifier {
    if (focusKey == null || focusContext == null) return this

    val localRequester = remember(focusKey) { FocusRequester() }
    val requester = if (focusKey == anchorKey && anchorRequester != null) {
        anchorRequester
    } else {
        localRequester
    }

    LaunchedEffect(focusContext.restorePending, focusContext.restoreKey, focusKey) {
        if (focusContext.restorePending && focusContext.restoreKey == focusKey) {
            val restored = runCatching {
                requester.requestFocus()
                true
            }.getOrDefault(false)
            if (restored) focusContext.onRestoreConsumed()
        }
    }

    return this
        .focusRequester(requester)
        .onFocusChanged { state ->
            if (state.isFocused) focusContext.onFocused(focusKey)
        }
}

private fun Modifier.homeDashboardHorizontalNavigation(
    currentSection: HomeDashboardFocusSection,
    availableSections: Set<HomeDashboardFocusSection>,
    sectionRequester: (HomeDashboardFocusSection) -> FocusRequester?
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val direction = when (event.key) {
        Key.DirectionLeft -> HomeDashboardHorizontalDirection.LEFT
        Key.DirectionRight -> HomeDashboardHorizontalDirection.RIGHT
        else -> return@onPreviewKeyEvent false
    }
    val targetSection = nextHomeDashboardFocusSection(
        current = currentSection,
        direction = direction,
        availableSections = availableSections
    ) ?: return@onPreviewKeyEvent false
    val requester = sectionRequester(targetSection) ?: return@onPreviewKeyEvent false
    runCatching { requester.requestFocus() }.isSuccess
}

private fun savedPlaylistFocusKey(playlistId: Long): String = "content:playlist:$playlistId"

private fun readyPlaylistFocusKey(url: String): String = "sources:ready:$url"
