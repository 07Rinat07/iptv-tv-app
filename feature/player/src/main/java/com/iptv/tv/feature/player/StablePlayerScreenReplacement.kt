package com.iptv.tv.feature.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CHANNEL_BANNER_DURATION_MS = 5_000L
private const val VOLUME_STEP = 0.05f

internal enum class StableRemoteAction {
    TOGGLE_FULLSCREEN,
    NEXT_CHANNEL,
    PREVIOUS_CHANNEL,
    VOLUME_UP,
    VOLUME_DOWN,
    TOGGLE_MUTE,
    NONE
}

internal fun stableRemoteActionForKey(keyCode: Int): StableRemoteAction = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER -> StableRemoteAction.TOGGLE_FULLSCREEN

    KeyEvent.KEYCODE_CHANNEL_UP,
    KeyEvent.KEYCODE_MEDIA_NEXT -> StableRemoteAction.NEXT_CHANNEL

    KeyEvent.KEYCODE_CHANNEL_DOWN,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> StableRemoteAction.PREVIOUS_CHANNEL

    KeyEvent.KEYCODE_VOLUME_UP -> StableRemoteAction.VOLUME_UP
    KeyEvent.KEYCODE_VOLUME_DOWN -> StableRemoteAction.VOLUME_DOWN
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MUTE -> StableRemoteAction.TOGGLE_MUTE

    else -> StableRemoteAction.NONE
}

internal fun stableAdjacentChannelId(
    channelIds: List<Long>,
    selectedChannelId: Long?,
    step: Int
): Long? {
    if (channelIds.isEmpty()) return null
    val currentIndex = channelIds.indexOf(selectedChannelId).takeIf { it >= 0 } ?: 0
    return channelIds[Math.floorMod(currentIndex + step, channelIds.size)]
}

private enum class StablePlayerPanel {
    NONE,
    PLAYLISTS,
    GROUPS,
    SETTINGS
}

@Composable
@UnstableApi
fun StablePlayerScreen(
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Настройки",
    onBack: (() -> Unit)? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()

    var panel by rememberSaveable { mutableStateOf(StablePlayerPanel.NONE) }
    var showChannelDrawer by rememberSaveable { mutableStateOf(false) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var hideUnavailable by rememberSaveable { mutableStateOf(true) }
    var optimisticFavoriteIds by remember { mutableStateOf(state.favoriteChannelIds) }
    var channelBannerVersion by remember { mutableIntStateOf(0) }
    var showChannelBanner by remember { mutableStateOf(false) }
    var volume by rememberSaveable { mutableFloatStateOf(1f) }
    var lastAudibleVolume by rememberSaveable { mutableFloatStateOf(1f) }

    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        if (volume > 0f) lastAudibleVolume = volume
    }

    fun toggleMute() {
        setVolume(if (volume > 0f) 0f else lastAudibleVolume.coerceAtLeast(0.25f))
    }

    LaunchedEffect(state.favoriteChannelIds) {
        optimisticFavoriteIds = state.favoriteChannelIds
    }

    val filteredChannels = remember(
        state.channels,
        state.channelQuery,
        state.selectedGroup,
        state.selectedSubGroup,
        optimisticFavoriteIds,
        favoritesOnly,
        hideUnavailable,
        state.parentalControlEnabled,
        state.parentalHideAdultChannels,
        state.parentalBlockedKeywords
    ) {
        val query = state.channelQuery.trim().lowercase(Locale.ROOT)
        state.channels.asSequence()
            .filter { stableChannelMatchesSelection(it, state.selectedGroup, state.selectedSubGroup) }
            .filter { channel ->
                query.isBlank() ||
                    channel.name.lowercase(Locale.ROOT).contains(query) ||
                    channel.group.orEmpty().lowercase(Locale.ROOT).contains(query) ||
                    channel.tvgId.orEmpty().lowercase(Locale.ROOT).contains(query)
            }
            .filter { !hideUnavailable || it.health != ChannelHealth.UNAVAILABLE }
            .filter { !favoritesOnly || it.id in optimisticFavoriteIds }
            .filterNot { channel ->
                state.parentalControlEnabled &&
                    state.parentalHideAdultChannels &&
                    stableMatchesParentalKeywords(channel, state.parentalBlockedKeywords)
            }
            .sortedWith(
                compareBy<Channel> { stableHealthPriority(it.health) }
                    .thenBy { it.orderIndex }
                    .thenBy { it.name }
            )
            .toList()
    }

    val selectedChannel = state.channels.firstOrNull { it.id == state.selectedChannelId }
    val selectedPrograms = selectedChannel
        ?.let { state.channelListEpgPrograms[it.id].orEmpty() }
        .orEmpty()

    fun toggleFavorite(channelId: Long) {
        optimisticFavoriteIds = if (channelId in optimisticFavoriteIds) {
            optimisticFavoriteIds - channelId
        } else {
            optimisticFavoriteIds + channelId
        }
        viewModel.toggleChannelFavorite(channelId)
    }

    fun playChannel(channelId: Long) {
        viewModel.playChannelInternal(channelId)
        channelBannerVersion += 1
        showChannelBanner = true
    }

    fun playAdjacent(step: Int) {
        stableAdjacentChannelId(
            channelIds = filteredChannels.map { it.id },
            selectedChannelId = state.selectedChannelId,
            step = step
        )?.let(::playChannel)
    }

    LaunchedEffect(channelBannerVersion) {
        if (channelBannerVersion == 0) return@LaunchedEffect
        showChannelBanner = true
        delay(CHANNEL_BANNER_DURATION_MS)
        showChannelBanner = false
    }

    LaunchedEffect(state.internalPlayerExpanded) {
        onFullscreenChanged(state.internalPlayerExpanded)
    }

    DisposableEffect(Unit) {
        onDispose { onFullscreenChanged(false) }
    }

    BackHandler(
        enabled = state.internalPlayerExpanded ||
            panel != StablePlayerPanel.NONE ||
            showChannelDrawer
    ) {
        when {
            panel != StablePlayerPanel.NONE -> panel = StablePlayerPanel.NONE
            showChannelDrawer -> showChannelDrawer = false
            state.internalPlayerExpanded -> viewModel.setInternalPlayerExpanded(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .then(
                if (state.internalPlayerExpanded) Modifier
                else Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            )
    ) {
        if (state.internalPlayerExpanded) {
            StableFullscreenPlayerReplacement(
                session = state.internalSession,
                channel = selectedChannel,
                programs = selectedPrograms,
                scale = state.playerVideoScale,
                volume = volume,
                showChannelBanner = showChannelBanner,
                onVolumeChange = ::setVolume,
                onToggleMute = ::toggleMute,
                onReady = { viewModel.onInternalPlaybackReady(it) },
                onError = { sessionId, message ->
                    viewModel.onInternalPlaybackError(message, context, sessionId)
                },
                onToggleFullscreen = { viewModel.setInternalPlayerExpanded(false) },
                onPreviousChannel = { playAdjacent(-1) },
                onNextChannel = { playAdjacent(1) },
                onStop = viewModel::stopInternalPlayback
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wide = maxWidth >= 920.dp
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StablePlayerRailReplacement(
                            modifier = Modifier.width(170.dp).fillMaxHeight(),
                            favoritesOnly = favoritesOnly,
                            onBack = onBack,
                            onLive = { favoritesOnly = false },
                            onPlaylists = { panel = StablePlayerPanel.PLAYLISTS },
                            onGroups = { panel = StablePlayerPanel.GROUPS },
                            onFavorites = { favoritesOnly = !favoritesOnly },
                            onSettings = { panel = StablePlayerPanel.SETTINGS }
                        )
                        StableCenterPaneReplacement(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            session = state.internalSession,
                            selectedChannel = selectedChannel,
                            programs = selectedPrograms,
                            channels = filteredChannels,
                            favoriteIds = optimisticFavoriteIds,
                            epgByChannel = state.channelListEpgPrograms,
                            scale = state.playerVideoScale,
                            volume = volume,
                            isStartingPlayback = state.isStartingPlayback,
                            onVolumeChange = ::setVolume,
                            onToggleMute = ::toggleMute,
                            onReady = viewModel::onInternalPlaybackReady,
                            onError = { sessionId, message ->
                                viewModel.onInternalPlaybackError(message, context, sessionId)
                            },
                            onPlaySelected = { viewModel.playSelected(context) },
                            onToggleFullscreen = viewModel::toggleInternalPlayerSize,
                            onPreviousChannel = { playAdjacent(-1) },
                            onNextChannel = { playAdjacent(1) },
                            onSelectChannel = ::playChannel,
                            onToggleFavorite = ::toggleFavorite,
                            onOpenChannels = { showChannelDrawer = true }
                        )
                        StableChannelBrowserReplacement(
                            modifier = Modifier.width(350.dp).fillMaxHeight(),
                            query = state.channelQuery,
                            onQueryChange = viewModel::updateChannelQuery,
                            channels = filteredChannels,
                            selectedChannelId = state.selectedChannelId,
                            favoriteIds = optimisticFavoriteIds,
                            epgByChannel = state.channelListEpgPrograms,
                            onSelect = ::playChannel,
                            onToggleFavorite = ::toggleFavorite,
                            onOpenGroups = { panel = StablePlayerPanel.GROUPS }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(onClick = { onBack?.invoke() }) { Text("Назад") }
                            Text(
                                selectedChannel?.name ?: "Плеер",
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            OutlinedButton(onClick = { showChannelDrawer = true }) { Text("Каналы") }
                            OutlinedButton(onClick = { panel = StablePlayerPanel.SETTINGS }) { Text("⚙") }
                        }
                        StableCenterPaneReplacement(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            session = state.internalSession,
                            selectedChannel = selectedChannel,
                            programs = selectedPrograms,
                            channels = filteredChannels,
                            favoriteIds = optimisticFavoriteIds,
                            epgByChannel = state.channelListEpgPrograms,
                            scale = state.playerVideoScale,
                            volume = volume,
                            isStartingPlayback = state.isStartingPlayback,
                            onVolumeChange = ::setVolume,
                            onToggleMute = ::toggleMute,
                            onReady = viewModel::onInternalPlaybackReady,
                            onError = { sessionId, message ->
                                viewModel.onInternalPlaybackError(message, context, sessionId)
                            },
                            onPlaySelected = { viewModel.playSelected(context) },
                            onToggleFullscreen = viewModel::toggleInternalPlayerSize,
                            onPreviousChannel = { playAdjacent(-1) },
                            onNextChannel = { playAdjacent(1) },
                            onSelectChannel = ::playChannel,
                            onToggleFavorite = ::toggleFavorite,
                            onOpenChannels = { showChannelDrawer = true }
                        )
                    }
                }
            }
        }

        if (showChannelDrawer && !state.internalPlayerExpanded) {
            StableChannelDrawerReplacement(
                channels = filteredChannels,
                query = state.channelQuery,
                selectedChannelId = state.selectedChannelId,
                favoriteIds = optimisticFavoriteIds,
                epgByChannel = state.channelListEpgPrograms,
                onQueryChange = viewModel::updateChannelQuery,
                onSelect = {
                    showChannelDrawer = false
                    playChannel(it)
                },
                onToggleFavorite = ::toggleFavorite,
                onOpenGroups = { panel = StablePlayerPanel.GROUPS },
                onDismiss = { showChannelDrawer = false }
            )
        }

        if (panel != StablePlayerPanel.NONE && !state.internalPlayerExpanded) {
            StablePanelDialogReplacement(
                panel = panel,
                playlists = state.playlists,
                selectedPlaylistId = state.selectedPlaylistId,
                groups = state.availableGroups,
                selectedGroup = state.selectedGroup,
                subGroups = state.availableSubGroups,
                selectedSubGroup = state.selectedSubGroup,
                hideUnavailable = hideUnavailable,
                favoritesOnly = favoritesOnly,
                bufferSummary = state.adaptiveBufferSummary,
                epgStatus = state.channelListEpgStatus,
                scale = state.playerVideoScale,
                volume = volume,
                primaryLabel = primaryLabel,
                onVolumeChange = ::setVolume,
                onToggleMute = ::toggleMute,
                onDismiss = { panel = StablePlayerPanel.NONE },
                onSelectPlaylist = {
                    viewModel.selectPlaylist(it)
                    panel = StablePlayerPanel.NONE
                },
                onSelectGroup = viewModel::selectGroup,
                onSelectSubGroup = viewModel::selectSubGroup,
                onToggleUnavailable = { hideUnavailable = !hideUnavailable },
                onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },
                onCycleScale = viewModel::cycleVideoScale,
                onOpenAppSettings = onPrimaryAction
            )
        }
    }
}

@Composable
private fun StablePlayerRailReplacement(
    modifier: Modifier,
    favoritesOnly: Boolean,
    onBack: (() -> Unit)?,
    onLive: () -> Unit,
    onPlaylists: () -> Unit,
    onGroups: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit
) {
    val state = rememberLazyListState()
    Card(modifier = modifier.tvFocusOutline()) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Text(
                "Rinat IPTV",
                modifier = Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Row(Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().focusGroup(),
                    state = state,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) { Text("Эфир") } }
                    item { OutlinedButton(onClick = onPlaylists, modifier = Modifier.fillMaxWidth()) { Text("Плейлисты") } }
                    item { OutlinedButton(onClick = onGroups, modifier = Modifier.fillMaxWidth()) { Text("Группы") } }
                    item {
                        if (favoritesOnly) {
                            Button(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("★ Избранное") }
                        } else {
                            OutlinedButton(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("☆ Избранное") }
                        }
                    }
                    item { OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Настройки") } }
                    item { onBack?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Назад") } } }
                }
                VerticalScrollControls(state = state, itemCount = 6)
            }
        }
    }
}

@Composable
@UnstableApi
private fun StableCenterPaneReplacement(
    modifier: Modifier,
    session: InternalPlaybackSession?,
    selectedChannel: Channel?,
    programs: List<EpgProgram>,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    scale: PlayerVideoScale,
    volume: Float,
    isStartingPlayback: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onReady: (Long?) -> Unit,
    onError: (Long?, String) -> Unit,
    onPlaySelected: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onSelectChannel: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenChannels: () -> Unit
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
            if (session != null) {
                StableVideoSurface(
                    session = session,
                    scale = scale,
                    expanded = false,
                    volume = volume,
                    onVolumeUp = { onVolumeChange(volume + VOLUME_STEP) },
                    onVolumeDown = { onVolumeChange(volume - VOLUME_STEP) },
                    onToggleMute = onToggleMute,
                    onReady = { onReady(session.sessionId) },
                    onError = { onError(session.sessionId, it) },
                    onToggleFullscreen = onToggleFullscreen,
                    onPreviousChannel = onPreviousChannel,
                    onNextChannel = onNextChannel,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                        .clickable(onClick = onToggleFullscreen),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selectedChannel?.name ?: "Выберите канал", color = Color.White)
                        Button(
                            onClick = onPlaySelected,
                            enabled = selectedChannel != null && !isStartingPlayback,
                            modifier = Modifier.padding(top = 10.dp)
                        ) {
                            Text(if (isStartingPlayback) "Подключение…" else "Смотреть")
                        }
                    }
                    StableFullscreenButton(
                        expanded = false,
                        onClick = onToggleFullscreen,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    )
                }
            }
        }

        StableNowNextCardReplacement(
            channel = selectedChannel,
            programs = programs,
            isFavorite = selectedChannel?.id?.let { it in favoriteIds } == true,
            volume = volume,
            onVolumeChange = onVolumeChange,
            onToggleMute = onToggleMute,
            onToggleFavorite = { selectedChannel?.id?.let(onToggleFavorite) },
            onPrevious = onPreviousChannel,
            onNext = onNextChannel,
            onOpenChannels = onOpenChannels
        )

        StableNearbyChannelsReplacement(
            channels = channels,
            epgByChannel = epgByChannel,
            onSelectChannel = onSelectChannel,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

@Composable
private fun StableNowNextCardReplacement(
    channel: Channel?,
    programs: List<EpgProgram>,
    isFavorite: Boolean,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenChannels: () -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val current = stableCurrentProgram(programs, nowMs)
    val next = stableNextProgram(programs, current, nowMs)
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AsyncImage(model = channel?.logo, contentDescription = channel?.name, modifier = Modifier.size(42.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        channel?.name ?: "Канал не выбран",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(channel?.group ?: "Без группы", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                OutlinedButton(onClick = onToggleFavorite, enabled = channel != null) {
                    Text(if (isFavorite) "★" else "☆")
                }
            }
            Text(
                current?.let { "Сейчас ${stableRange(it)} · ${it.title}" }
                    ?: "Сейчас: программа не найдена",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                next?.let { "Далее ${stableRange(it)} · ${it.title}" } ?: "Далее: данных нет",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            VolumeControl(
                volume = volume,
                onVolumeChange = onVolumeChange,
                onToggleMute = onToggleMute
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPrevious) { Text("◀ Канал") }
                OutlinedButton(onClick = onNext) { Text("Канал ▶") }
                OutlinedButton(onClick = onOpenChannels) { Text("Список") }
            }
        }
    }
}

@Composable
private fun StableNearbyChannelsReplacement(
    channels: List<Channel>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onSelectChannel: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Каналы рядом", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) { Text("В начало") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem((listState.firstVisibleItemIndex - 3).coerceAtLeast(0))
                    }
                }
            ) { Text("◀") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val last = (channels.size - 1).coerceAtLeast(0)
                        listState.animateScrollToItem((listState.firstVisibleItemIndex + 3).coerceAtMost(last))
                    }
                }
            ) { Text("▶") }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(channels, key = { it.id }) { channel ->
                val current = stableCurrentProgram(
                    epgByChannel[channel.id].orEmpty(),
                    System.currentTimeMillis()
                )
                Card(
                    modifier = Modifier
                        .width(184.dp)
                        .tvFocusOutline()
                        .clickable { onSelectChannel(channel.id) }
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            channel.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            current?.let { "${stableTime(it.startEpochMs)} ${it.title}" } ?: "EPG нет",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StableChannelBrowserReplacement(
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onSelect: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenGroups: () -> Unit
) {
    Card(modifier = modifier.tvFocusOutline()) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Каналы · ${channels.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(onClick = onOpenGroups) { Text("Группы") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск") },
                singleLine = true
            )
            StableChannelListReplacement(
                modifier = Modifier.fillMaxWidth().weight(1f),
                channels = channels,
                selectedChannelId = selectedChannelId,
                favoriteIds = favoriteIds,
                epgByChannel = epgByChannel,
                onSelect = onSelect,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

@Composable
private fun StableChannelListReplacement(
    modifier: Modifier,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onSelect: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedChannelId, channels) {
        val index = channels.indexOfFirst { it.id == selectedChannelId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    Row(modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight().focusGroup(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(channels, key = { it.id }) { channel ->
                val current = stableCurrentProgram(
                    epgByChannel[channel.id].orEmpty(),
                    System.currentTimeMillis()
                )
                val selected = channel.id == selectedChannelId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusOutline()
                        .clickable { onSelect(channel.id) },
                    tonalElevation = if (selected) 8.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AsyncImage(
                            model = channel.logo,
                            contentDescription = channel.name,
                            modifier = Modifier.size(38.dp)
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                channel.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                            Text(
                                current?.let {
                                    "${stableTime(it.startEpochMs)}–${stableTime(it.endEpochMs)} ${it.title}"
                                } ?: "Программа не найдена",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(onClick = { onToggleFavorite(channel.id) }) {
                            Text(if (channel.id in favoriteIds) "★" else "☆")
                        }
                    }
                }
            }
        }
        VerticalScrollControls(state = listState, itemCount = channels.size)
    }
}

@Composable
private fun StableChannelDrawerReplacement(
    channels: List<Channel>,
    query: String,
    selectedChannelId: Long?,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onQueryChange: (String) -> Unit,
    onSelect: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenGroups: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Каналы · ${channels.size}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 650.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск канала") },
                    singleLine = true
                )
                StableChannelListReplacement(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    channels = channels,
                    selectedChannelId = selectedChannelId,
                    favoriteIds = favoriteIds,
                    epgByChannel = epgByChannel,
                    onSelect = onSelect,
                    onToggleFavorite = onToggleFavorite
                )
            }
        },
        confirmButton = { OutlinedButton(onClick = onOpenGroups) { Text("Группы") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
@UnstableApi
private fun StableFullscreenPlayerReplacement(
    session: InternalPlaybackSession?,
    channel: Channel?,
    programs: List<EpgProgram>,
    scale: PlayerVideoScale,
    volume: Float,
    showChannelBanner: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onReady: (Long) -> Unit,
    onError: (Long?, String) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onStop: () -> Unit
) {
    BackHandler(onBack = onToggleFullscreen)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (session != null) {
            StableVideoSurface(
                session = session,
                scale = scale,
                expanded = true,
                volume = volume,
                onVolumeUp = { onVolumeChange(volume + VOLUME_STEP) },
                onVolumeDown = { onVolumeChange(volume - VOLUME_STEP) },
                onToggleMute = onToggleMute,
                onReady = { onReady(session.sessionId) },
                onError = { onError(session.sessionId, it) },
                onToggleFullscreen = onToggleFullscreen,
                onPreviousChannel = onPreviousChannel,
                onNextChannel = onNextChannel,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().clickable(onClick = onToggleFullscreen),
                contentAlignment = Alignment.Center
            ) {
                Text(channel?.name ?: "Канал не выбран", color = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.68f))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onPreviousChannel) { Text("◀") }
                Column(Modifier.weight(1f)) {
                    Text(
                        channel?.name ?: "Плеер",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val current = stableCurrentProgram(programs, System.currentTimeMillis())
                    Text(
                        current?.let { "${stableRange(it)} · ${it.title}" } ?: "Программа не найдена",
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(onClick = onStop) { Text("■") }
                OutlinedButton(onClick = onNextChannel) { Text("▶") }
                StableFullscreenButton(expanded = true, onClick = onToggleFullscreen)
            }
            VolumeControl(
                volume = volume,
                onVolumeChange = onVolumeChange,
                onToggleMute = onToggleMute,
                dark = true
            )
        }

        if (showChannelBanner) {
            StableChannelBannerReplacement(
                channel = channel,
                programs = programs,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
            )
        }
    }
}

@Composable
private fun StableChannelBannerReplacement(
    channel: Channel?,
    programs: List<EpgProgram>,
    modifier: Modifier = Modifier
) {
    val nowMs = System.currentTimeMillis()
    val current = stableCurrentProgram(programs, nowMs)
    val next = stableNextProgram(programs, current, nowMs)
    Card(modifier = modifier.fillMaxWidth(0.62f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(channel?.name ?: "Канал", fontWeight = FontWeight.Bold)
            Text(current?.let { "Сейчас ${stableRange(it)} · ${it.title}" } ?: "Сейчас: программа не найдена")
            Text(
                next?.let { "Далее ${stableRange(it)} · ${it.title}" } ?: "Далее: данных нет",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun VolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    dark: Boolean = false
) {
    val labelColor = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onToggleMute) { Text(if (volume <= 0f) "🔇" else "🔊") }
        OutlinedButton(onClick = { onVolumeChange(volume - VOLUME_STEP) }) { Text("−") }
        Slider(
            value = volume,
            onValueChange = { onVolumeChange(it.coerceIn(0f, 1f)) },
            modifier = Modifier.weight(1f),
            steps = 19,
            valueRange = 0f..1f
        )
        OutlinedButton(onClick = { onVolumeChange(volume + VOLUME_STEP) }) { Text("+") }
        Text("${(volume * 100).toInt()}%", color = labelColor)
    }
}

@Composable
private fun VerticalScrollControls(
    state: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val pageSize = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
    val lastIndex = (itemCount - 1).coerceAtLeast(0)

    Column(
        modifier = modifier.padding(start = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedButton(
            onClick = { scope.launch { state.animateScrollToItem(0) } },
            enabled = itemCount > 0 && state.canScrollBackward
        ) { Text("⌂") }
        OutlinedButton(
            onClick = {
                scope.launch {
                    state.animateScrollToItem((state.firstVisibleItemIndex - pageSize).coerceAtLeast(0))
                }
            },
            enabled = state.canScrollBackward
        ) { Text("▲") }
        Spacer(Modifier.weight(1f))
        Text("${if (itemCount == 0) 0 else state.firstVisibleItemIndex + 1}/$itemCount")
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                scope.launch {
                    state.animateScrollToItem((state.firstVisibleItemIndex + pageSize).coerceAtMost(lastIndex))
                }
            },
            enabled = state.canScrollForward
        ) { Text("▼") }
    }
}

@Composable
private fun StablePanelDialogReplacement(
    panel: StablePlayerPanel,
    playlists: List<Playlist>,
    selectedPlaylistId: Long?,
    groups: List<String>,
    selectedGroup: String?,
    subGroups: List<String>,
    selectedSubGroup: String?,
    hideUnavailable: Boolean,
    favoritesOnly: Boolean,
    bufferSummary: String,
    epgStatus: String,
    scale: PlayerVideoScale,
    volume: Float,
    primaryLabel: String,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onSelectGroup: (String?) -> Unit,
    onSelectSubGroup: (String?) -> Unit,
    onToggleUnavailable: () -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onCycleScale: () -> Unit,
    onOpenAppSettings: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (panel) {
                    StablePlayerPanel.PLAYLISTS -> "Плейлисты"
                    StablePlayerPanel.GROUPS -> "Группы и фильтры"
                    StablePlayerPanel.SETTINGS -> "Настройки плеера"
                    StablePlayerPanel.NONE -> "Плеер"
                }
            )
        },
        text = {
            when (panel) {
                StablePlayerPanel.PLAYLISTS -> ScrollableButtonList(
                    labels = playlists.map { "${it.name} · ${it.channelCount}" },
                    selectedIndex = playlists.indexOfFirst { it.id == selectedPlaylistId },
                    onClick = { index -> playlists.getOrNull(index)?.id?.let(onSelectPlaylist) }
                )

                StablePlayerPanel.GROUPS -> Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScrollableButtonList(
                        labels = listOf("Все группы") + groups,
                        selectedIndex = if (selectedGroup == null) 0 else groups.indexOf(selectedGroup) + 1,
                        onClick = { index -> onSelectGroup(if (index == 0) null else groups.getOrNull(index - 1)) },
                        modifier = Modifier.weight(1f)
                    )
                    if (subGroups.isNotEmpty()) {
                        ScrollableButtonList(
                            labels = listOf("Все подкатегории") + subGroups,
                            selectedIndex = if (selectedSubGroup == null) 0 else subGroups.indexOf(selectedSubGroup) + 1,
                            onClick = { index ->
                                onSelectSubGroup(if (index == 0) null else subGroups.getOrNull(index - 1))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(onClick = onToggleUnavailable, modifier = Modifier.fillMaxWidth()) {
                        Text(if (hideUnavailable) "Показывать недоступные" else "Скрывать недоступные")
                    }
                    OutlinedButton(onClick = onToggleFavoritesOnly, modifier = Modifier.fillMaxWidth()) {
                        Text(if (favoritesOnly) "Показать все каналы" else "Только избранное")
                    }
                }

                StablePlayerPanel.SETTINGS -> Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Режим кадра: $scale")
                    OutlinedButton(onClick = onCycleScale, modifier = Modifier.fillMaxWidth()) {
                        Text("Сменить режим кадра")
                    }
                    VolumeControl(
                        volume = volume,
                        onVolumeChange = onVolumeChange,
                        onToggleMute = onToggleMute
                    )
                    Text(bufferSummary, style = MaterialTheme.typography.bodySmall)
                    Text(epgStatus, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Media3 автоматически выбирает дорожки. Для проблемных потоков выполняется " +
                            "проверка видеокодека и перезапуск декодера."
                    )
                    onOpenAppSettings?.let {
                        Button(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
                    }
                }

                StablePlayerPanel.NONE -> Unit
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun ScrollableButtonList(
    labels: List<String>,
    selectedIndex: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyListState()
    Row(modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp)) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            state = state,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(labels.size, key = { it }) { index ->
                if (index == selectedIndex) {
                    Button(onClick = { onClick(index) }, modifier = Modifier.fillMaxWidth()) {
                        Text(labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(onClick = { onClick(index) }, modifier = Modifier.fillMaxWidth()) {
                        Text(labels[index], maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        VerticalScrollControls(state = state, itemCount = labels.size)
    }
}

private fun stableChannelMatchesSelection(
    channel: Channel,
    selectedGroup: String?,
    selectedSubGroup: String?
): Boolean {
    if (selectedGroup.isNullOrBlank()) return true
    val parts = stableGroupParts(channel.group)
    if (!parts.first.equals(selectedGroup, ignoreCase = true)) return false
    return selectedSubGroup.isNullOrBlank() || parts.second.equals(selectedSubGroup, ignoreCase = true)
}

private fun stableGroupParts(value: String?): Pair<String?, String?> {
    val normalized = value.orEmpty().trim()
    if (normalized.isBlank()) return null to null
    val separator = listOf(" / ", " > ", "|", "::", "/").firstOrNull { normalized.contains(it) }
        ?: return normalized to null
    val parts = normalized.split(separator, limit = 2).map { it.trim() }
    return parts.getOrNull(0)?.ifBlank { null } to parts.getOrNull(1)?.ifBlank { null }
}

private fun stableMatchesParentalKeywords(channel: Channel, keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = "${channel.name} ${channel.group.orEmpty()}".lowercase(Locale.ROOT)
    return keywords.any { keyword ->
        keyword.isNotBlank() && haystack.contains(keyword.trim().lowercase(Locale.ROOT))
    }
}

private fun stableHealthPriority(health: ChannelHealth): Int = when (health) {
    ChannelHealth.AVAILABLE -> 0
    ChannelHealth.UNKNOWN -> 1
    ChannelHealth.UNSTABLE -> 2
    ChannelHealth.UNAVAILABLE -> 3
}

internal fun stableCurrentProgram(programs: List<EpgProgram>, nowMs: Long): EpgProgram? =
    programs.firstOrNull { nowMs >= it.startEpochMs && nowMs < it.endEpochMs }

internal fun stableNextProgram(
    programs: List<EpgProgram>,
    current: EpgProgram?,
    nowMs: Long
): EpgProgram? {
    val threshold = current?.endEpochMs ?: nowMs
    return programs.filter { it.startEpochMs >= threshold }.minByOrNull { it.startEpochMs }
}

private fun stableTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun stableRange(program: EpgProgram): String =
    "${stableTime(program.startEpochMs)}–${stableTime(program.endEpochMs)}"
