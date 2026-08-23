package com.iptv.tv.feature.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
internal const val VOLUME_STEP = 0.05f

internal fun stableUseWidePlayerLayout(widthDp: Float, heightDp: Float): Boolean =
    widthDp >= 1_080f && heightDp >= 560f

internal fun stableUseCompactPlayerControls(heightDp: Float): Boolean = heightDp < 760f

internal enum class StableRemoteAction {
    TOGGLE_CONTROLS,
    TOGGLE_FULLSCREEN,
    NEXT_CHANNEL,
    PREVIOUS_CHANNEL,
    VOLUME_UP,
    VOLUME_DOWN,
    TOGGLE_MUTE,
    TOGGLE_PLAYBACK,
    NONE
}

internal fun stableRemoteActionForKey(
    keyCode: Int,
    fullscreen: Boolean = false
): StableRemoteAction = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER,
    KeyEvent.KEYCODE_ENTER,
    KeyEvent.KEYCODE_NUMPAD_ENTER,
    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_GUIDE -> StableRemoteAction.TOGGLE_CONTROLS

    KeyEvent.KEYCODE_F -> StableRemoteAction.TOGGLE_FULLSCREEN

    KeyEvent.KEYCODE_CHANNEL_UP,
    KeyEvent.KEYCODE_MEDIA_NEXT -> StableRemoteAction.NEXT_CHANNEL

    KeyEvent.KEYCODE_CHANNEL_DOWN,
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> StableRemoteAction.PREVIOUS_CHANNEL

    KeyEvent.KEYCODE_DPAD_LEFT ->
        if (fullscreen) StableRemoteAction.PREVIOUS_CHANNEL else StableRemoteAction.NONE
    KeyEvent.KEYCODE_DPAD_RIGHT ->
        if (fullscreen) StableRemoteAction.NEXT_CHANNEL else StableRemoteAction.NONE
    KeyEvent.KEYCODE_DPAD_UP ->
        if (fullscreen) StableRemoteAction.VOLUME_UP else StableRemoteAction.NONE
    KeyEvent.KEYCODE_DPAD_DOWN ->
        if (fullscreen) StableRemoteAction.VOLUME_DOWN else StableRemoteAction.NONE

    KeyEvent.KEYCODE_VOLUME_UP -> StableRemoteAction.VOLUME_UP
    KeyEvent.KEYCODE_VOLUME_DOWN -> StableRemoteAction.VOLUME_DOWN
    KeyEvent.KEYCODE_VOLUME_MUTE,
    KeyEvent.KEYCODE_MUTE -> StableRemoteAction.TOGGLE_MUTE

    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_HEADSETHOOK -> StableRemoteAction.TOGGLE_PLAYBACK

    else -> StableRemoteAction.NONE
}

internal fun stableAdjacentChannelId(
    channelIds: List<Long>,
    selectedChannelId: Long?,
    step: Int
): Long? = StableChannelNavigation.adjacentId(
    channelIds = channelIds,
    selectedChannelId = selectedChannelId,
    step = step
)

internal enum class StablePlayerPanel {
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

    // Torrent TV availability is informational only. Never probe or hide the full catalog.
    // We remember the last result only for channels the user actually tried.
    LaunchedEffect(
        state.selectedChannelId,
        state.isStartingPlayback,
        state.internalSession?.sessionId,
        state.enginePeers,
        state.engineSpeedKbps,
        state.resolvedStreamUrl,
        state.lastError
    ) {
        P2pChannelAvailabilityUiCache.resetSupersededSearches(state.selectedChannelId)
        val channel = state.channels.firstOrNull { it.id == state.selectedChannelId }
            ?: return@LaunchedEffect
        if (PlayerP2pDescriptor.detect(channel.streamUrl) == null) return@LaunchedEffect

        val activeSession = state.internalSession?.takeIf { it.channelId == channel.id }
        val previous = P2pChannelAvailabilityUiCache.statuses[channel.id]
        val p2pFailure = state.lastError?.takeIf { message ->
            message.contains("Torrent TV", ignoreCase = true) ||
                message.contains("P2P", ignoreCase = true) ||
                message.contains("подготовить поток", ignoreCase = true)
        }
        val availability = when {
            activeSession != null && !state.isStartingPlayback -> P2pChannelAvailabilityState.PLAYING
            activeSession != null -> P2pChannelAvailabilityState.READY
            state.isStartingPlayback -> P2pChannelAvailabilityState.SEARCHING
            p2pFailure != null -> p2pAvailabilityFromResolveError(p2pFailure)
            state.resolvedStreamUrl != null -> P2pChannelAvailabilityState.READY
            else -> previous?.state ?: P2pChannelAvailabilityState.UNCHECKED
        }
        val peers = when (availability) {
            P2pChannelAvailabilityState.SEARCHING,
            P2pChannelAvailabilityState.NO_PEERS,
            P2pChannelAvailabilityState.ERROR -> 0
            else -> state.enginePeers.coerceAtLeast(0)
        }
        val speed = if (availability == P2pChannelAvailabilityState.PLAYING) {
            state.engineSpeedKbps.coerceAtLeast(0)
        } else {
            0
        }
        if (availability == P2pChannelAvailabilityState.SEARCHING) {
            P2pChannelAvailabilityUiCache.beginSearch(channel.id)
        } else {
            P2pChannelAvailabilityUiCache.mark(
                channelId = channel.id,
                state = availability,
                peers = peers,
                speedKbps = speed
            )
        }
    }

    val filteredChannels = remember(
        state.channels,
        state.channelQuery,
        state.selectedGroup,
        state.selectedSubGroup,
        optimisticFavoriteIds,
        favoritesOnly,
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

    LaunchedEffect(
        state.availableGroups,
        state.availableSubGroups,
        state.selectedGroup,
        state.selectedSubGroup
    ) {
        val normalized = StableChannelNavigation.normalizeGroupSelection(
            selectedGroup = state.selectedGroup,
            selectedSubGroup = state.selectedSubGroup,
            availableGroups = state.availableGroups,
            availableSubGroups = state.availableSubGroups
        )
        when {
            normalized.first != state.selectedGroup -> viewModel.selectGroup(normalized.first)
            normalized.second != state.selectedSubGroup -> viewModel.selectSubGroup(normalized.second)
        }
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
        onDispose {
            viewModel.stopInternalPlayback()
            onFullscreenChanged(false)
        }
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
                onP2pBoundaryTelemetry = viewModel::onP2pPlayerBoundaryTelemetry,
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
                val compactHeight = stableUseCompactPlayerControls(maxHeight.value)
                val wide = stableUseWidePlayerLayout(maxWidth.value, maxHeight.value)
                if (wide) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        StablePlayerRailReplacement(
                            modifier = Modifier.width(if (compactHeight) 148.dp else 170.dp).fillMaxHeight(),
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
                            compact = compactHeight,
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
                            onP2pBoundaryTelemetry = viewModel::onP2pPlayerBoundaryTelemetry,
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
                            modifier = Modifier.width(if (compactHeight) 300.dp else 350.dp).fillMaxHeight(),
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
                            compact = true,
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
                            onP2pBoundaryTelemetry = viewModel::onP2pPlayerBoundaryTelemetry,
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
                onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },
                onCycleScale = viewModel::cycleVideoScale,
                onOpenAppSettings = onPrimaryAction
            )
        }

        StablePlaybackFeedbackBanner(
            lastError = state.lastError,
            isStartingPlayback = state.isStartingPlayback,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
@UnstableApi
private fun StableCenterPaneReplacement(
    modifier: Modifier,
    compact: Boolean,
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
    onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit,
    onError: (Long?, String) -> Unit,
    onPlaySelected: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onSelectChannel: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenChannels: () -> Unit
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val targetVideoHeight = (maxWidth * 9f / 16f)
                .coerceAtMost(if (compact) 320.dp else 480.dp)
                .coerceAtLeast(180.dp)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(targetVideoHeight)
                    .tvFocusOutline()
            ) {
                if (session != null) {
                    StableVideoSurface(
                        session = session,
                        scale = scale,
                        expanded = false,
                        volume = volume,
                        showControls = controlsVisible,
                        onToggleControls = { controlsVisible = !controlsVisible },
                        onVolumeUp = { onVolumeChange(volume + VOLUME_STEP) },
                        onVolumeDown = { onVolumeChange(volume - VOLUME_STEP) },
                        onToggleMute = onToggleMute,
                        onReady = { onReady(session.sessionId) },
                        onP2pBoundaryTelemetry = onP2pBoundaryTelemetry,
                        onError = { onError(session.sessionId, it) },
                        onToggleFullscreen = onToggleFullscreen,
                        onPreviousChannel = onPreviousChannel,
                        onNextChannel = onNextChannel,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black)
                            .combinedClickable(
                                onClick = { controlsVisible = !controlsVisible },
                                onDoubleClick = onToggleFullscreen
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(selectedChannel?.name ?: "Выберите канал", color = Color.White)
                            Button(
                                onClick = onPlaySelected,
                                enabled = selectedChannel != null && !isStartingPlayback,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(if (isStartingPlayback) "Подключение…" else "Смотреть")
                            }
                        }
                        if (controlsVisible) {
                            StableFullscreenButton(
                                expanded = false,
                                onClick = onToggleFullscreen,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                            )
                        }
                    }
                }
            }
        }

        if (compact) {
            StableCompactControlsReplacement(
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
        } else {
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

}

@Composable
private fun StableCompactControlsReplacement(
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
    val current = stableCurrentProgram(programs, System.currentTimeMillis())
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    channel?.name ?: "Канал не выбран",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    current?.let { "${stableRange(it)} · ${it.title}" } ?: "EPG нет",
                    modifier = Modifier.weight(1.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = onToggleFavorite,
                    enabled = channel != null,
                    modifier = Modifier.size(42.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(if (isFavorite) "★" else "☆") }
            }
            VolumeControl(
                volume = volume,
                onVolumeChange = onVolumeChange,
                onToggleMute = onToggleMute
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(1f)) { Text("◀") }
                OutlinedButton(onClick = onOpenChannels, modifier = Modifier.weight(1.4f)) { Text("Каналы") }
                OutlinedButton(onClick = onNext, modifier = Modifier.weight(1f)) { Text("▶") }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
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
    onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit,
    onError: (Long?, String) -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onStop: () -> Unit
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }
    BackHandler(onBack = onToggleFullscreen)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (session != null) {
            StableVideoSurface(
                session = session,
                scale = scale,
                expanded = true,
                volume = volume,
                showControls = controlsVisible,
                onToggleControls = { controlsVisible = !controlsVisible },
                onVolumeUp = { onVolumeChange(volume + VOLUME_STEP) },
                onVolumeDown = { onVolumeChange(volume - VOLUME_STEP) },
                onToggleMute = onToggleMute,
                onReady = { onReady(session.sessionId) },
                onP2pBoundaryTelemetry = onP2pBoundaryTelemetry,
                onError = { onError(session.sessionId, it) },
                onToggleFullscreen = onToggleFullscreen,
                onPreviousChannel = onPreviousChannel,
                onNextChannel = onNextChannel,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize().combinedClickable(
                            onClick = { controlsVisible = !controlsVisible },
                            onDoubleClick = onToggleFullscreen
                        ),
                contentAlignment = Alignment.Center
            ) {
                Text(channel?.name ?: "Канал не выбран", color = Color.White)
            }
        }

        if (controlsVisible) {
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
