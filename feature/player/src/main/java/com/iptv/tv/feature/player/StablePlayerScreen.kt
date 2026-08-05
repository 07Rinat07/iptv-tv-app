package com.iptv.tv.feature.player

import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.player.toLoadControl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val STABLE_IPTV_USER_AGENT = "Rinat-IPTV/1.0 (Android TV; Media3)"
private const val CHANNEL_BANNER_DURATION_MS = 5_000L


internal enum class StableRemoteAction {
    TOGGLE_FULLSCREEN,
    NEXT_CHANNEL,
    PREVIOUS_CHANNEL,
    NONE
}

internal fun stableRemoteActionForKey(keyCode: Int): StableRemoteAction = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_CENTER,
    AndroidKeyEvent.KEYCODE_ENTER,
    AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> StableRemoteAction.TOGGLE_FULLSCREEN

    AndroidKeyEvent.KEYCODE_CHANNEL_UP,
    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> StableRemoteAction.NEXT_CHANNEL

    AndroidKeyEvent.KEYCODE_CHANNEL_DOWN,
    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> StableRemoteAction.PREVIOUS_CHANNEL

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

/**
 * TV-first player screen used by the application routes.
 *
 * Key interaction contract:
 * - mouse/touchpad click or touch tap on video toggles fullscreen;
 * - DPAD_CENTER/ENTER toggles fullscreen when the video has focus;
 * - CHANNEL_UP/DOWN and MEDIA_NEXT/PREVIOUS switch channels;
 * - BACK closes an overlay first and then exits fullscreen.
 */
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
        state.channels
            .asSequence()
            .filter { channel -> stableChannelMatchesSelection(channel, state.selectedGroup, state.selectedSubGroup) }
            .filter { channel ->
                query.isBlank() ||
                    channel.name.lowercase(Locale.ROOT).contains(query) ||
                    channel.group.orEmpty().lowercase(Locale.ROOT).contains(query) ||
                    channel.tvgId.orEmpty().lowercase(Locale.ROOT).contains(query)
            }
            .filter { channel -> !hideUnavailable || channel.health != ChannelHealth.UNAVAILABLE }
            .filter { channel -> !favoritesOnly || channel.id in optimisticFavoriteIds }
            .filterNot { channel ->
                state.parentalControlEnabled &&
                    state.parentalHideAdultChannels &&
                    stableMatchesParentalKeywords(channel, state.parentalBlockedKeywords)
            }
            .sortedWith(compareBy<Channel> { stableHealthPriority(it.health) }.thenBy { it.orderIndex }.thenBy { it.name })
            .toList()
    }

    val selectedChannel = state.channels.firstOrNull { it.id == state.selectedChannelId }
    val selectedPrograms = selectedChannel?.let { state.channelListEpgPrograms[it.id].orEmpty() }.orEmpty()

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
        enabled = state.internalPlayerExpanded || panel != StablePlayerPanel.NONE || showChannelDrawer
    ) {
        when {
            panel != StablePlayerPanel.NONE -> panel = StablePlayerPanel.NONE
            showChannelDrawer -> showChannelDrawer = false
            state.internalPlayerExpanded -> viewModel.setInternalPlayerExpanded(false)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (state.internalPlayerExpanded) {
            StableFullscreenPlayer(
                session = state.internalSession,
                channel = selectedChannel,
                programs = selectedPrograms,
                scale = state.playerVideoScale,
                showChannelBanner = showChannelBanner,
                onReady = { sessionId -> viewModel.onInternalPlaybackReady(sessionId) },
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
                        StablePlayerRail(
                            modifier = Modifier.width(154.dp).fillMaxHeight(),
                            favoritesOnly = favoritesOnly,
                            onBack = onBack,
                            onLive = { favoritesOnly = false },
                            onPlaylists = { panel = StablePlayerPanel.PLAYLISTS },
                            onGroups = { panel = StablePlayerPanel.GROUPS },
                            onFavorites = { favoritesOnly = !favoritesOnly },
                            onSettings = { panel = StablePlayerPanel.SETTINGS }
                        )
                        StableCenterPane(
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            session = state.internalSession,
                            selectedChannel = selectedChannel,
                            programs = selectedPrograms,
                            channels = filteredChannels,
                            favoriteIds = optimisticFavoriteIds,
                            epgByChannel = state.channelListEpgPrograms,
                            scale = state.playerVideoScale,
                            isStartingPlayback = state.isStartingPlayback,
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
                        StableChannelBrowser(
                            modifier = Modifier.width(326.dp).fillMaxHeight(),
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
                        StableCenterPane(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            session = state.internalSession,
                            selectedChannel = selectedChannel,
                            programs = selectedPrograms,
                            channels = filteredChannels,
                            favoriteIds = optimisticFavoriteIds,
                            epgByChannel = state.channelListEpgPrograms,
                            scale = state.playerVideoScale,
                            isStartingPlayback = state.isStartingPlayback,
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
            AlertDialog(
                onDismissRequest = { showChannelDrawer = false },
                title = { Text("Каналы · ${filteredChannels.size}") },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 650.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.channelQuery,
                            onValueChange = viewModel::updateChannelQuery,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Поиск канала") },
                            singleLine = true
                        )
                        StableChannelList(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            channels = filteredChannels,
                            selectedChannelId = state.selectedChannelId,
                            favoriteIds = optimisticFavoriteIds,
                            epgByChannel = state.channelListEpgPrograms,
                            onSelect = {
                                showChannelDrawer = false
                                playChannel(it)
                            },
                            onToggleFavorite = ::toggleFavorite
                        )
                    }
                },
                confirmButton = {
                    OutlinedButton(onClick = { panel = StablePlayerPanel.GROUPS }) { Text("Группы") }
                },
                dismissButton = {
                    Button(onClick = { showChannelDrawer = false }) { Text("Закрыть") }
                }
            )
        }

        if (panel != StablePlayerPanel.NONE && !state.internalPlayerExpanded) {
            StablePanelDialog(
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
                primaryLabel = primaryLabel,
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
private fun StablePlayerRail(
    modifier: Modifier,
    favoritesOnly: Boolean,
    onBack: (() -> Unit)?,
    onLive: () -> Unit,
    onPlaylists: () -> Unit,
    onGroups: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit
) {
    Card(modifier = modifier.tvFocusOutline()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Rinat IPTV", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) { Text("Эфир") }
            OutlinedButton(onClick = onPlaylists, modifier = Modifier.fillMaxWidth()) { Text("Плейлисты") }
            OutlinedButton(onClick = onGroups, modifier = Modifier.fillMaxWidth()) { Text("Группы") }
            if (favoritesOnly) {
                Button(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("★ Избранное") }
            } else {
                OutlinedButton(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("☆ Избранное") }
            }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Настройки") }
            Spacer(modifier = Modifier.weight(1f))
            onBack?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Назад") } }
        }
    }
}

@Composable
@UnstableApi
private fun StableCenterPane(
    modifier: Modifier,
    session: InternalPlaybackSession?,
    selectedChannel: Channel?,
    programs: List<EpgProgram>,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    scale: PlayerVideoScale,
    isStartingPlayback: Boolean,
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
                    onReady = { onReady(session.sessionId) },
                    onError = { onError(session.sessionId, it) },
                    onToggleFullscreen = onToggleFullscreen,
                    onPreviousChannel = onPreviousChannel,
                    onNextChannel = onNextChannel,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
                        .clickable(onClick = onToggleFullscreen),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(selectedChannel?.name ?: "Выберите канал", color = Color.White)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onPlaySelected, enabled = selectedChannel != null && !isStartingPlayback) {
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

        StableNowNextCard(
            channel = selectedChannel,
            programs = programs,
            isFavorite = selectedChannel?.id?.let { it in favoriteIds } == true,
            onToggleFavorite = { selectedChannel?.id?.let(onToggleFavorite) },
            onPrevious = onPreviousChannel,
            onNext = onNextChannel,
            onOpenChannels = onOpenChannels
        )

        Text("Каналы рядом", style = MaterialTheme.typography.titleSmall)
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(channels.take(30), key = { it.id }) { channel ->
                val currentProgram = stableCurrentProgram(epgByChannel[channel.id].orEmpty(), System.currentTimeMillis())
                Card(
                    modifier = Modifier.width(184.dp).tvFocusOutline().clickable { onSelectChannel(channel.id) }
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            currentProgram?.let { "${stableTime(it.startEpochMs)} ${it.title}" } ?: "EPG нет",
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
private fun StableNowNextCard(
    channel: Channel?,
    programs: List<EpgProgram>,
    isFavorite: Boolean,
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
                AsyncImage(
                    model = channel?.logo,
                    contentDescription = channel?.name,
                    modifier = Modifier.size(42.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(channel?.name ?: "Канал не выбран", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(channel?.group ?: "Без группы", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                OutlinedButton(onClick = onToggleFavorite, enabled = channel != null) {
                    Text(if (isFavorite) "★" else "☆")
                }
            }
            Text(
                current?.let { "Сейчас ${stableRange(it)} · ${it.title}" } ?: "Сейчас: программа не найдена",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                next?.let { "Далее ${stableRange(it)} · ${it.title}" } ?: "Далее: данных нет",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
private fun StableChannelBrowser(
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
                Text("Каналы · ${channels.size}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onOpenGroups) { Text("Группы") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск") },
                singleLine = true
            )
            StableChannelList(
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
private fun StableChannelList(
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
    LazyColumn(
        modifier = modifier.focusGroup(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(channels, key = { it.id }) { channel ->
            val nowMs = System.currentTimeMillis()
            val current = stableCurrentProgram(epgByChannel[channel.id].orEmpty(), nowMs)
            val selected = channel.id == selectedChannelId
            Surface(
                modifier = Modifier.fillMaxWidth().tvFocusOutline().clickable { onSelect(channel.id) },
                tonalElevation = if (selected) 8.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AsyncImage(model = channel.logo, contentDescription = channel.name, modifier = Modifier.size(38.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            current?.let { "${stableTime(it.startEpochMs)}–${stableTime(it.endEpochMs)} ${it.title}" } ?: "Программа не найдена",
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
}

@Composable
@UnstableApi
private fun StableFullscreenPlayer(
    session: InternalPlaybackSession?,
    channel: Channel?,
    programs: List<EpgProgram>,
    scale: PlayerVideoScale,
    showChannelBanner: Boolean,
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

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f)).padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPreviousChannel) { Text("◀") }
            Column(Modifier.weight(1f)) {
                Text(channel?.name ?: "Плеер", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

        if (showChannelBanner) {
            StableChannelBanner(
                channel = channel,
                programs = programs,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
            )
        }
    }
}

@Composable
private fun StableChannelBanner(channel: Channel?, programs: List<EpgProgram>, modifier: Modifier = Modifier) {
    val nowMs = System.currentTimeMillis()
    val current = stableCurrentProgram(programs, nowMs)
    val next = stableNextProgram(programs, current, nowMs)
    Card(modifier = modifier.fillMaxWidth(0.62f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(channel?.name ?: "Канал", fontWeight = FontWeight.Bold)
            Text(current?.let { "Сейчас ${stableRange(it)} · ${it.title}" } ?: "Сейчас: программа не найдена")
            Text(next?.let { "Далее ${stableRange(it)} · ${it.title}" } ?: "Далее: данных нет", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StableFullscreenButton(
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Text(if (expanded) "⤢" else "⛶")
    }
}

@Composable
@UnstableApi
private fun StableVideoSurface(
    session: InternalPlaybackSession,
    scale: PlayerVideoScale,
    expanded: Boolean,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playerResult = remember(session.sessionId, session.streamUrl, session.requestHeaders) {
        runCatching {
            val requestHeaders = session.requestHeaders.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            val userAgent = session.requestHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                ?: STABLE_IPTV_USER_AGENT
            val httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(requestHeaders)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            val maxHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
            val trackSelector = DefaultTrackSelector(context).apply {
                val builder = buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setExceedVideoConstraintsIfNecessary(maxHeapMb > 256L)
                if (maxHeapMb <= 256L) {
                    builder.setMaxVideoSize(1280, 720)
                } else if (maxHeapMb <= 512L) {
                    builder.setMaxVideoSize(1920, 1080)
                }
                setParameters(builder)
            }
            ExoPlayer.Builder(context, renderersFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(session.bufferConfig.toLoadControl())
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
        }
    }
    val player = playerResult.getOrNull()
    val initError = playerResult.exceptionOrNull()

    if (player == null) {
        LaunchedEffect(initError) {
            onError("Не удалось создать Media3: ${initError?.message ?: "неизвестная ошибка"}")
        }
        Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Ошибка инициализации плеера", color = Color.White)
        }
        return
    }

    var readyReported by remember(session.sessionId) { mutableStateOf(false) }
    var bufferingSinceMs by remember(session.sessionId) { mutableStateOf(0L) }
    var softRecoveryCount by remember(session.sessionId) { mutableIntStateOf(0) }

    DisposableEffect(session.sessionId, player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        bufferingSinceMs = 0L
                        softRecoveryCount = 0
                        if (!readyReported) {
                            readyReported = true
                            onReady()
                        }
                    }
                    Player.STATE_BUFFERING -> if (bufferingSinceMs == 0L) {
                        bufferingSinceMs = System.currentTimeMillis()
                    }
                    else -> bufferingSinceMs = 0L
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                } else {
                    onError("${error.errorCodeName}: ${error.message ?: "ошибка воспроизведения"}")
                }
            }
        }
        player.addListener(listener)
        runCatching {
            val mediaItemBuilder = MediaItem.Builder().setUri(session.streamUrl)
            stableInferMimeType(session.streamUrl)?.let(mediaItemBuilder::setMimeType)
            player.setMediaItem(mediaItemBuilder.build())
            player.prepare()
            player.playWhenReady = true
        }.onFailure { onError(it.message ?: it.javaClass.simpleName) }

        onDispose {
            player.removeListener(listener)
            runCatching {
                player.playWhenReady = false
                player.stop()
                player.clearMediaItems()
                player.clearVideoSurface()
                player.release()
            }
        }
    }

    LaunchedEffect(session.sessionId, player) {
        while (isActive) {
            delay(2_000L)
            val since = bufferingSinceMs
            if (since == 0L) continue
            val elapsed = System.currentTimeMillis() - since
            val firstRecoveryAt = (session.bufferConfig.bufferForPlaybackAfterRebufferMs * 4L).coerceIn(10_000L, 24_000L)
            if (elapsed >= firstRecoveryAt && softRecoveryCount < 2) {
                softRecoveryCount += 1
                bufferingSinceMs = System.currentTimeMillis()
                runCatching {
                    if (player.isCurrentMediaItemLive) player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                }
            } else if (elapsed >= 45_000L && softRecoveryCount >= 2) {
                onError("Поток не отвечает после автоматического восстановления буфера")
                bufferingSinceMs = 0L
            }
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    controllerAutoShow = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    isClickable = true
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setOnClickListener { onToggleFullscreen() }
                    setOnKeyListener { _: View, keyCode: Int, event: AndroidKeyEvent ->
                        if (event.action != AndroidKeyEvent.ACTION_UP) return@setOnKeyListener false
                        when (stableRemoteActionForKey(keyCode)) {
                            StableRemoteAction.TOGGLE_FULLSCREEN -> {
                                onToggleFullscreen(); true
                            }
                            StableRemoteAction.NEXT_CHANNEL -> {
                                onNextChannel(); true
                            }
                            StableRemoteAction.PREVIOUS_CHANNEL -> {
                                onPreviousChannel(); true
                            }
                            StableRemoteAction.NONE -> false
                        }
                    }
                    this.player = player
                    if (expanded) post { requestFocus() }
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = when (scale) {
                    PlayerVideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PlayerVideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    PlayerVideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                view.setOnClickListener { onToggleFullscreen() }
                if (expanded && !view.hasFocus()) view.post { view.requestFocus() }
            },
            onRelease = { view ->
                view.setOnClickListener(null)
                view.setOnKeyListener(null)
                view.player = null
            }
        )
        StableFullscreenButton(
            expanded = expanded,
            onClick = onToggleFullscreen,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        )
    }
}

@Composable
private fun StablePanelDialog(
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
    primaryLabel: String,
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
                StablePlayerPanel.PLAYLISTS -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        val selected = playlist.id == selectedPlaylistId
                        if (selected) {
                            Button(onClick = { onSelectPlaylist(playlist.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${playlist.name} · ${playlist.channelCount}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            OutlinedButton(onClick = { onSelectPlaylist(playlist.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text("${playlist.name} · ${playlist.channelCount}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                StablePlayerPanel.GROUPS -> Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Категории")
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            if (selectedGroup == null) Button(onClick = { onSelectGroup(null) }, modifier = Modifier.fillMaxWidth()) { Text("Все группы") }
                            else OutlinedButton(onClick = { onSelectGroup(null) }, modifier = Modifier.fillMaxWidth()) { Text("Все группы") }
                        }
                        items(groups, key = { it }) { group ->
                            if (group == selectedGroup) Button(onClick = { onSelectGroup(group) }, modifier = Modifier.fillMaxWidth()) { Text(group) }
                            else OutlinedButton(onClick = { onSelectGroup(group) }, modifier = Modifier.fillMaxWidth()) { Text(group) }
                        }
                    }
                    if (subGroups.isNotEmpty()) {
                        Text("Подкатегории")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                OutlinedButton(onClick = { onSelectSubGroup(null) }) { Text("Все") }
                            }
                            items(subGroups, key = { it }) { subgroup ->
                                if (subgroup == selectedSubGroup) Button(onClick = { onSelectSubGroup(subgroup) }) { Text(subgroup) }
                                else OutlinedButton(onClick = { onSelectSubGroup(subgroup) }) { Text(subgroup) }
                            }
                        }
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
                    OutlinedButton(onClick = onCycleScale, modifier = Modifier.fillMaxWidth()) { Text("Сменить режим кадра") }
                    Text(bufferSummary, style = MaterialTheme.typography.bodySmall)
                    Text(epgStatus, style = MaterialTheme.typography.bodySmall)
                    Text("Аудиодорожки и субтитры выбираются автоматически. Расширенные параметры находятся в общих настройках.")
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

private fun stableChannelMatchesSelection(channel: Channel, selectedGroup: String?, selectedSubGroup: String?): Boolean {
    if (selectedGroup.isNullOrBlank()) return true
    val parts = stableGroupParts(channel.group)
    if (!parts.first.equals(selectedGroup, ignoreCase = true)) return false
    return selectedSubGroup.isNullOrBlank() || parts.second.equals(selectedSubGroup, ignoreCase = true)
}

private fun stableGroupParts(value: String?): Pair<String?, String?> {
    val normalized = value.orEmpty().trim()
    if (normalized.isBlank()) return null to null
    val separators = listOf(" / ", " > ", "|", "::", "/")
    val separator = separators.firstOrNull { normalized.contains(it) }
    if (separator == null) return normalized to null
    val parts = normalized.split(separator, limit = 2).map { it.trim() }
    return parts.getOrNull(0)?.ifBlank { null } to parts.getOrNull(1)?.ifBlank { null }
}

private fun stableMatchesParentalKeywords(channel: Channel, keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = "${channel.name} ${channel.group.orEmpty()}".lowercase(Locale.ROOT)
    return keywords.any { keyword -> keyword.isNotBlank() && haystack.contains(keyword.trim().lowercase(Locale.ROOT)) }
}

private fun stableHealthPriority(health: ChannelHealth): Int = when (health) {
    ChannelHealth.AVAILABLE -> 0
    ChannelHealth.UNKNOWN -> 1
    ChannelHealth.UNSTABLE -> 2
    ChannelHealth.UNAVAILABLE -> 3
}

internal fun stableCurrentProgram(programs: List<EpgProgram>, nowMs: Long): EpgProgram? {
    return programs.firstOrNull { nowMs >= it.startEpochMs && nowMs < it.endEpochMs }
}

internal fun stableNextProgram(programs: List<EpgProgram>, current: EpgProgram?, nowMs: Long): EpgProgram? {
    val threshold = current?.endEpochMs ?: nowMs
    return programs.filter { it.startEpochMs >= threshold }.minByOrNull { it.startEpochMs }
}

private fun stableTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun stableRange(program: EpgProgram): String =
    "${stableTime(program.startEpochMs)}–${stableTime(program.endEpochMs)}"

private fun stableInferMimeType(url: String): String? {
    val normalized = url.substringBefore('?').lowercase(Locale.ROOT)
    return when {
        normalized.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        normalized.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        normalized.endsWith(".ism") || normalized.endsWith(".isml") || normalized.contains(".ism/manifest") -> MimeTypes.APPLICATION_SS
        normalized.endsWith(".mp4") || normalized.endsWith(".m4v") -> MimeTypes.VIDEO_MP4
        normalized.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        normalized.endsWith(".ts") || normalized.endsWith(".mpegts") -> MimeTypes.VIDEO_MP2T
        else -> null
    }
}
