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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import kotlinx.coroutines.launch

private const val HOME_MAIN_FOCUS_ITEM_INDEX = 1

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

private data class HomeDashboardFocusRequesters(
    val navigation: FocusRequester,
    val mainContent: FocusRequester,
    val channelRail: FocusRequester,
    val quickSources: FocusRequester
) {
    fun requesterFor(zone: HomeDashboardFocusZone): FocusRequester = when (zone) {
        HomeDashboardFocusZone.NAVIGATION -> navigation
        HomeDashboardFocusZone.MAIN_CONTENT -> mainContent
        HomeDashboardFocusZone.CHANNEL_RAIL -> channelRail
        HomeDashboardFocusZone.QUICK_SOURCES -> quickSources
    }
}

@Composable
internal fun HomeDashboard(
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
                onWatchChannel = onWatchChannel,
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
    onWatchChannel: (Long, Long) -> Unit,
    onWatchReadyPlaylist: (ReadyPlaylistPreset) -> Unit,
    actions: HomeDashboardActions
) {
    val focusRequesters = remember {
        HomeDashboardFocusRequesters(
            navigation = FocusRequester(),
            mainContent = FocusRequester(),
            channelRail = FocusRequester(),
            quickSources = FocusRequester()
        )
    }
    val mainContentListState = rememberLazyListState()
    val channelRailListState = rememberLazyListState()
    val quickSourcesListState = rememberLazyListState()
    val focusScope = rememberCoroutineScope()
    var lastFocusedZoneName by rememberSaveable {
        mutableStateOf(HomeDashboardFocusZone.MAIN_CONTENT.name)
    }
    var didRestoreInitialFocus by remember { mutableStateOf(false) }

    LaunchedEffect(state.channelRailPlaylistId) {
        channelRailListState.scrollToItem(0)
    }

    val channelRailChannels = remember(state.channelRailChannels) {
        homeChannelRailItems(state.channelRailChannels)
    }
    val channelRailPlaylist = remember(state.playlists, state.channelRailPlaylistId) {
        state.channelRailPlaylistId?.let { playlistId ->
            state.playlists.firstOrNull { it.id == playlistId }
        }
    }
    val channelRailFocusIndex = remember(channelRailChannels, state.channelRailSelectedChannelId) {
        homeChannelRailFocusIndex(channelRailChannels, state.channelRailSelectedChannelId)
    }
    val channelRailFocusChannelId = channelRailFocusIndex?.let { index ->
        channelRailChannels.getOrNull(index)?.id
    }

    val hasNavigationAnchor = navigationActions(actions).isNotEmpty()
    val hasMainContentAnchor = !state.isImporting && actions.onOpenPlayer != null
    val hasChannelRailAnchor =
        !state.isImporting && channelRailPlaylist != null && channelRailFocusIndex != null
    val quickSourceAnchor = when {
        !state.isImporting && READY_PLAYLIST_PRESETS.isNotEmpty() -> {
            HomeDashboardQuickFocusAnchor.READY_PLAYLIST
        }
        actions.onOpenScanner != null -> HomeDashboardQuickFocusAnchor.SCANNER
        actions.onPrimaryAction != null -> HomeDashboardQuickFocusAnchor.PRIMARY_ACTION
        else -> HomeDashboardQuickFocusAnchor.NONE
    }
    val quickSourceAnchorItemIndex = homeDashboardQuickFocusItemIndex(
        anchor = quickSourceAnchor,
        readySourceCount = READY_PLAYLIST_PRESETS.size,
        hasScanner = actions.onOpenScanner != null
    )

    fun canFocusZone(zone: HomeDashboardFocusZone): Boolean = when (zone) {
        HomeDashboardFocusZone.NAVIGATION -> hasNavigationAnchor
        HomeDashboardFocusZone.MAIN_CONTENT -> hasMainContentAnchor
        HomeDashboardFocusZone.CHANNEL_RAIL -> hasChannelRailAnchor
        HomeDashboardFocusZone.QUICK_SOURCES -> quickSourceAnchorItemIndex != null
    }

    val onZoneFocused: (HomeDashboardFocusZone) -> Unit = { zone ->
        lastFocusedZoneName = zone.name
    }

    suspend fun requestZoneFocusNow(zone: HomeDashboardFocusZone): Boolean {
        if (!canFocusZone(zone)) return false

        when (zone) {
            HomeDashboardFocusZone.NAVIGATION -> Unit
            HomeDashboardFocusZone.MAIN_CONTENT -> {
                mainContentListState.scrollToItem(HOME_MAIN_FOCUS_ITEM_INDEX)
            }
            HomeDashboardFocusZone.CHANNEL_RAIL -> {
                val itemIndex = channelRailFocusIndex ?: return false
                channelRailListState.scrollToItem(itemIndex)
            }
            HomeDashboardFocusZone.QUICK_SOURCES -> {
                val itemIndex = quickSourceAnchorItemIndex ?: return false
                quickSourcesListState.scrollToItem(itemIndex)
            }
        }

        repeat(2) {
            withFrameNanos { }
            val focused = runCatching {
                focusRequesters.requesterFor(zone).requestFocus()
                true
            }.getOrDefault(false)
            if (focused) return true
        }
        return false
    }

    val requestZoneFocus: (HomeDashboardFocusZone) -> Boolean = { zone ->
        if (!canFocusZone(zone)) {
            false
        } else {
            focusScope.launch { requestZoneFocusNow(zone) }
            true
        }
    }

    LaunchedEffect(Unit) {
        val restored = restoreHomeDashboardFocusZone(lastFocusedZoneName)
        val target = when {
            canFocusZone(restored) -> restored
            canFocusZone(HomeDashboardFocusZone.MAIN_CONTENT) -> HomeDashboardFocusZone.MAIN_CONTENT
            canFocusZone(HomeDashboardFocusZone.NAVIGATION) -> HomeDashboardFocusZone.NAVIGATION
            canFocusZone(HomeDashboardFocusZone.CHANNEL_RAIL) -> HomeDashboardFocusZone.CHANNEL_RAIL
            canFocusZone(HomeDashboardFocusZone.QUICK_SOURCES) -> HomeDashboardFocusZone.QUICK_SOURCES
            else -> null
        }
        target?.let { requestZoneFocusNow(it) }
        didRestoreInitialFocus = true
    }

    LaunchedEffect(channelRailFocusChannelId, state.isImporting) {
        if (
            didRestoreInitialFocus &&
            lastFocusedZoneName == HomeDashboardFocusZone.CHANNEL_RAIL.name &&
            canFocusZone(HomeDashboardFocusZone.CHANNEL_RAIL)
        ) {
            requestZoneFocusNow(HomeDashboardFocusZone.CHANNEL_RAIL)
        }
    }

    LaunchedEffect(quickSourceAnchor) {
        if (
            didRestoreInitialFocus &&
            lastFocusedZoneName == HomeDashboardFocusZone.QUICK_SOURCES.name &&
            canFocusZone(HomeDashboardFocusZone.QUICK_SOURCES)
        ) {
            requestZoneFocusNow(HomeDashboardFocusZone.QUICK_SOURCES)
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
                .homeDashboardHorizontalFocusNavigation(
                    zone = HomeDashboardFocusZone.NAVIGATION,
                    requestZoneFocus = requestZoneFocus
                ),
            actions = actions,
            focusRequester = focusRequesters.navigation,
            onZoneFocused = onZoneFocused
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TvScrollableLazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .homeDashboardHorizontalFocusNavigation(
                        zone = HomeDashboardFocusZone.MAIN_CONTENT,
                        requestZoneFocus = requestZoneFocus
                    ),
                state = mainContentListState,
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
                        buttonModifier = Modifier
                            .focusRequester(focusRequesters.mainContent)
                            .trackHomeDashboardFocus(
                                zone = HomeDashboardFocusZone.MAIN_CONTENT,
                                onZoneFocused = onZoneFocused
                            )
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
                            buttonModifier = Modifier.trackHomeDashboardFocus(
                                zone = HomeDashboardFocusZone.MAIN_CONTENT,
                                onZoneFocused = onZoneFocused
                            )
                        )
                    }
                    if (state.playlists.size > 6) {
                        actions.onOpenPlaylists?.let { action ->
                            item {
                                OutlinedButton(
                                    onClick = action,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .trackHomeDashboardFocus(
                                            zone = HomeDashboardFocusZone.MAIN_CONTENT,
                                            onZoneFocused = onZoneFocused
                                        )
                                ) {
                                    Text("Показать все мои списки (${state.playlists.size})")
                                }
                            }
                        }
                    }
                }
            }

            if (channelRailPlaylist != null && channelRailChannels.isNotEmpty()) {
                HomeChannelRail(
                    playlistId = channelRailPlaylist.id,
                    playlistName = channelRailPlaylist.name,
                    channels = channelRailChannels,
                    selectedChannelId = state.channelRailSelectedChannelId,
                    enabled = !state.isImporting,
                    listState = channelRailListState,
                    focusRequester = focusRequesters.channelRail,
                    onZoneFocused = onZoneFocused,
                    requestMainFocus = {
                        requestZoneFocus(HomeDashboardFocusZone.MAIN_CONTENT)
                    },
                    onWatchChannel = onWatchChannel
                )
            }
        }

        TvScrollableLazyColumn(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .homeDashboardHorizontalFocusNavigation(
                    zone = HomeDashboardFocusZone.QUICK_SOURCES,
                    requestZoneFocus = requestZoneFocus
                ),
            state = quickSourcesListState,
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
                firstButtonFocusRequester = if (
                    quickSourceAnchor == HomeDashboardQuickFocusAnchor.READY_PLAYLIST
                ) {
                    focusRequesters.quickSources
                } else {
                    null
                },
                focusZone = HomeDashboardFocusZone.QUICK_SOURCES,
                onZoneFocused = onZoneFocused
            )

            actions.onOpenScanner?.let { action ->
                item {
                    var modifier = Modifier
                        .fillMaxWidth()
                        .trackHomeDashboardFocus(
                            zone = HomeDashboardFocusZone.QUICK_SOURCES,
                            onZoneFocused = onZoneFocused
                        )
                    if (quickSourceAnchor == HomeDashboardQuickFocusAnchor.SCANNER) {
                        modifier = modifier.focusRequester(focusRequesters.quickSources)
                    }
                    Button(onClick = action, modifier = modifier) {
                        Text("Найти новые списки")
                    }
                }
            }

            actions.onPrimaryAction?.let { action ->
                item {
                    var modifier = Modifier
                        .fillMaxWidth()
                        .trackHomeDashboardFocus(
                            zone = HomeDashboardFocusZone.QUICK_SOURCES,
                            onZoneFocused = onZoneFocused
                        )
                    if (quickSourceAnchor == HomeDashboardQuickFocusAnchor.PRIMARY_ACTION) {
                        modifier = modifier.focusRequester(focusRequesters.quickSources)
                    }
                    OutlinedButton(onClick = action, modifier = modifier) {
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
    firstButtonFocusRequester: FocusRequester? = null,
    focusZone: HomeDashboardFocusZone? = null,
    onZoneFocused: ((HomeDashboardFocusZone) -> Unit)? = null
) {
    itemsIndexed(READY_PLAYLIST_PRESETS, key = { _, preset -> preset.url }) { index, preset ->
        var buttonModifier: Modifier = Modifier
        if (index == 0 && firstButtonFocusRequester != null) {
            buttonModifier = buttonModifier.focusRequester(firstButtonFocusRequester)
        }
        if (focusZone != null && onZoneFocused != null) {
            buttonModifier = buttonModifier.trackHomeDashboardFocus(focusZone, onZoneFocused)
        }
        ReadyPlaylistCard(
            preset = preset,
            importing = state.importingUrl == preset.url,
            enabled = !state.isImporting,
            onWatch = { onWatchReadyPlaylist(preset) },
            buttonModifier = buttonModifier
        )
    }
}

@Composable
private fun HomeNavigationRail(
    modifier: Modifier,
    actions: HomeDashboardActions,
    focusRequester: FocusRequester,
    onZoneFocused: (HomeDashboardFocusZone) -> Unit
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
                actions = actions,
                firstButtonFocusRequester = focusRequester,
                focusZone = HomeDashboardFocusZone.NAVIGATION,
                onZoneFocused = onZoneFocused
            )
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
private fun HomeNavigationButtons(
    actions: HomeDashboardActions,
    firstButtonFocusRequester: FocusRequester? = null,
    focusZone: HomeDashboardFocusZone? = null,
    onZoneFocused: ((HomeDashboardFocusZone) -> Unit)? = null
) {
    navigationActions(actions).forEachIndexed { index, (label, action) ->
        var modifier = Modifier.fillMaxWidth()
        if (index == 0 && firstButtonFocusRequester != null) {
            modifier = modifier.focusRequester(firstButtonFocusRequester)
        }
        if (focusZone != null && onZoneFocused != null) {
            modifier = modifier.trackHomeDashboardFocus(focusZone, onZoneFocused)
        }
        OutlinedButton(onClick = action, modifier = modifier) {
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
    onOpenPlayer: (() -> Unit)?,
    buttonModifier: Modifier = Modifier
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
                        Button(onClick = action, modifier = buttonModifier) {
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
    buttonModifier: Modifier = Modifier
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
            Button(onClick = onWatch, enabled = enabled, modifier = buttonModifier) {
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
    buttonModifier: Modifier = Modifier
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
                modifier = buttonModifier.fillMaxWidth()
            ) {
                Text(if (importing) "Загрузка…" else "Смотреть")
            }
        }
    }
}

private fun Modifier.trackHomeDashboardFocus(
    zone: HomeDashboardFocusZone,
    onZoneFocused: (HomeDashboardFocusZone) -> Unit
): Modifier = onFocusChanged { focusState ->
    if (focusState.isFocused) {
        onZoneFocused(zone)
    }
}

private fun Modifier.homeDashboardHorizontalFocusNavigation(
    zone: HomeDashboardFocusZone,
    requestZoneFocus: (HomeDashboardFocusZone) -> Boolean
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) {
        return@onPreviewKeyEvent false
    }

    val direction = when (event.key) {
        Key.DirectionLeft -> HomeDashboardFocusDirection.LEFT
        Key.DirectionRight -> HomeDashboardFocusDirection.RIGHT
        else -> return@onPreviewKeyEvent false
    }
    val target = nextHomeDashboardFocusZone(zone, direction)
        ?: return@onPreviewKeyEvent false

    requestZoneFocus(target)
}
