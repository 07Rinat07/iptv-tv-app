package com.iptv.tv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
import com.iptv.tv.core.utils.FileLogger
import android.content.Context
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.player.toLoadControl
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val QUICK_SCROLL_STEP = 8
private const val QUICK_PAGE_STEP = 24
private const val IPTV_USER_AGENT = "myscanerIPTV/0.1 (Android TV; Media3)"

private data class PlayerTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
    val supported: Boolean
)

private enum class PlayerOverlay {
    NONE,
    PLAYLISTS,
    FILTERS,
    SETTINGS,
    INFO
}

@Composable
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
fun PlayerScreen(
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Настройки",
    onBack: (() -> Unit)? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var hideUnavailable by rememberSaveable { mutableStateOf(true) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var showChannelDrawer by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(PlayerOverlay.NONE) }
    var expandedEpgChannelIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }

    val filteredChannels = remember(
        state.channels,
        state.channelQuery,
        state.selectedGroup,
        state.selectedSubGroup,
        state.favoriteChannelIds,
        favoritesOnly,
        hideUnavailable,
        state.parentalControlEnabled,
        state.parentalHideAdultChannels,
        state.parentalBlockedKeywords
    ) {
        val query = state.channelQuery.trim().lowercase()
        state.channels
            .asSequence()
            .filter { channel ->
                channelMatchesGroup(
                    channel = channel,
                    selectedGroup = state.selectedGroup,
                    selectedSubGroup = state.selectedSubGroup
                )
            }
            .filter { channel ->
                query.isBlank() ||
                    channel.name.lowercase().contains(query) ||
                    channel.group?.lowercase()?.contains(query) == true ||
                    channel.streamUrl.lowercase().contains(query)
            }
            .filter { channel -> !hideUnavailable || channel.health != ChannelHealth.UNAVAILABLE }
            .filter { channel -> !favoritesOnly || channel.id in state.favoriteChannelIds }
            .filterNot { channel ->
                state.parentalControlEnabled &&
                    state.parentalHideAdultChannels &&
                    channel.matchesParentalKeywords(state.parentalBlockedKeywords)
            }
            .sortedWith(
                compareBy<Channel> { healthPriority(it.health) }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }

    val selectedChannelName = state.channels.firstOrNull { it.id == state.selectedChannelId }?.name

    fun toggleChannelProgram(channelId: Long) {
        expandedEpgChannelIds = if (channelId in expandedEpgChannelIds) {
            expandedEpgChannelIds - channelId
        } else {
            expandedEpgChannelIds + channelId
        }
    }

    fun playAdjacentChannel(step: Int) {
        val channelId = adjacentChannelId(
            channelIds = filteredChannels.map { it.id },
            selectedChannelId = state.selectedChannelId,
            step = step
        ) ?: return
        viewModel.playChannelInternal(channelId)
    }

    LaunchedEffect(state.internalPlayerExpanded) {
        onFullscreenChanged(state.internalPlayerExpanded)
    }

    DisposableEffect(Unit) {
        onDispose { onFullscreenChanged(false) }
    }

    BackHandler(
        enabled = state.internalPlayerExpanded || showChannelDrawer || overlay != PlayerOverlay.NONE
    ) {
        when {
            state.internalPlayerExpanded -> viewModel.setInternalPlayerExpanded(false)
            showChannelDrawer -> showChannelDrawer = false
            overlay != PlayerOverlay.NONE -> overlay = PlayerOverlay.NONE
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.internalPlayerExpanded) {
            FullscreenInternalPlayerOverlay(
                session = state.internalSession,
                selectedChannelName = selectedChannelName,
                scale = state.playerVideoScale,
                onReady = { sessionId -> viewModel.onInternalPlaybackReady(sessionId) },
                onError = { message ->
                    viewModel.onInternalPlaybackError(
                        message = message,
                        context = context,
                        sessionId = state.internalSession?.sessionId
                    )
                },
                onClose = { viewModel.setInternalPlayerExpanded(false) },
                onPreviousChannel = { playAdjacentChannel(-1) },
                onNextChannel = { playAdjacentChannel(1) },
                onStop = viewModel::stopInternalPlayback
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val wideLayout = maxWidth >= 960.dp
                val outerPadding = if (wideLayout) 14.dp else 10.dp

                if (wideLayout) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(outerPadding),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        PlayerRail(
                            modifier = Modifier
                                .width(164.dp)
                                .fillMaxHeight(),
                            favoritesOnly = favoritesOnly,
                            onBack = onBack,
                            onLive = {
                                favoritesOnly = false
                                overlay = PlayerOverlay.NONE
                            },
                            onPlaylists = { overlay = PlayerOverlay.PLAYLISTS },
                            onFilters = { overlay = PlayerOverlay.FILTERS },
                            onFavorites = { favoritesOnly = !favoritesOnly },
                            onInfo = { overlay = PlayerOverlay.INFO },
                            onSettings = { overlay = PlayerOverlay.SETTINGS }
                        )

                        PlayerCenterPane(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            session = state.internalSession,
                            selectedChannelName = selectedChannelName,
                            isStartingPlayback = state.isStartingPlayback,
                            scale = state.playerVideoScale,
                            channels = filteredChannels,
                            selectedChannelId = state.selectedChannelId,
                            favoriteChannelIds = state.favoriteChannelIds,
                            epgProgramsByChannel = state.channelListEpgPrograms,
                            onReady = viewModel::onInternalPlaybackReady,
                            onError = { sessionId, message ->
                                viewModel.onInternalPlaybackError(
                                    message = message,
                                    context = context,
                                    sessionId = sessionId
                                )
                            },
                            onPlaySelected = { viewModel.playSelected(context) },
                            onStop = viewModel::stopInternalPlayback,
                            onToggleExpanded = viewModel::toggleInternalPlayerSize,
                            onCycleScale = viewModel::cycleVideoScale,
                            onSelectChannel = viewModel::playChannelInternal,
                            onToggleFavorite = viewModel::toggleChannelFavorite,
                            onOpenChannels = { showChannelDrawer = true },
                            showChannelsButton = false
                        )

                        PlayerChannelBrowser(
                            modifier = Modifier
                                .width(310.dp)
                                .fillMaxHeight(),
                            query = state.channelQuery,
                            onQueryChange = viewModel::updateChannelQuery,
                            channels = filteredChannels,
                            selectedChannelId = state.selectedChannelId,
                            favoriteChannelIds = state.favoriteChannelIds,
                            epgProgramsByChannel = state.channelListEpgPrograms,
                            expandedEpgChannelIds = expandedEpgChannelIds,
                            onToggleProgram = ::toggleChannelProgram,
                            onToggleFavorite = viewModel::toggleChannelFavorite,
                            onSelect = viewModel::playChannelInternal,
                            onOpenFilters = { overlay = PlayerOverlay.FILTERS }
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(outerPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        PlayerCompactHeader(
                            selectedChannelName = selectedChannelName,
                            onBack = onBack,
                            onOpenChannels = { showChannelDrawer = true },
                            onOpenSettings = { overlay = PlayerOverlay.SETTINGS }
                        )
                        PlayerCenterPane(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            session = state.internalSession,
                            selectedChannelName = selectedChannelName,
                            isStartingPlayback = state.isStartingPlayback,
                            scale = state.playerVideoScale,
                            channels = filteredChannels,
                            selectedChannelId = state.selectedChannelId,
                            favoriteChannelIds = state.favoriteChannelIds,
                            epgProgramsByChannel = state.channelListEpgPrograms,
                            onReady = viewModel::onInternalPlaybackReady,
                            onError = { sessionId, message ->
                                viewModel.onInternalPlaybackError(
                                    message = message,
                                    context = context,
                                    sessionId = sessionId
                                )
                            },
                            onPlaySelected = { viewModel.playSelected(context) },
                            onStop = viewModel::stopInternalPlayback,
                            onToggleExpanded = viewModel::toggleInternalPlayerSize,
                            onCycleScale = viewModel::cycleVideoScale,
                            onSelectChannel = viewModel::playChannelInternal,
                            onToggleFavorite = viewModel::toggleChannelFavorite,
                            onOpenChannels = { showChannelDrawer = true },
                            showChannelsButton = true
                        )
                    }
                }
            }
        }

        if (showChannelDrawer && !state.internalPlayerExpanded) {
            AlertDialog(
                onDismissRequest = { showChannelDrawer = false },
                title = { Text("Каналы") },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 360.dp, max = 620.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.channelQuery,
                            onValueChange = viewModel::updateChannelQuery,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Поиск") },
                            singleLine = true
                        )
                        ChannelQuickPanel(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            channels = filteredChannels,
                            selectedChannelId = state.selectedChannelId,
                            favoriteChannelIds = state.favoriteChannelIds,
                            epgProgramsByChannel = state.channelListEpgPrograms,
                            expandedEpgChannelIds = expandedEpgChannelIds,
                            onToggleProgram = ::toggleChannelProgram,
                            onToggleFavorite = viewModel::toggleChannelFavorite,
                            onSelect = {
                                showChannelDrawer = false
                                viewModel.playChannelInternal(it)
                            }
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = { showChannelDrawer = false }) { Text("Закрыть") }
                }
            )
        }

        if (overlay != PlayerOverlay.NONE && !state.internalPlayerExpanded) {
            PlayerOverlayDialog(
                overlay = overlay,
                state = state,
                hideUnavailable = hideUnavailable,
                favoritesOnly = favoritesOnly,
                onDismiss = { overlay = PlayerOverlay.NONE },
                onSelectPlaylist = {
                    viewModel.selectPlaylist(it)
                    overlay = PlayerOverlay.NONE
                },
                onSelectGroup = viewModel::selectGroup,
                onSelectSubGroup = viewModel::selectSubGroup,
                onToggleUnavailable = { hideUnavailable = !hideUnavailable },
                onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },
                onCycleScale = viewModel::cycleVideoScale,
                onPlayInternal = viewModel::playSelectedInternal,
                onPlayVlc = { viewModel.playSelectedVlc(context) },
                onExportLogs = { viewModel.exportLogs(context) },
                onOpenAppSettings = onPrimaryAction,
                primaryLabel = primaryLabel
            )
        }
    }
}

@Composable
private fun PlayerRail(
    modifier: Modifier,
    favoritesOnly: Boolean,
    onBack: (() -> Unit)?,
    onLive: () -> Unit,
    onPlaylists: () -> Unit,
    onFilters: () -> Unit,
    onFavorites: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit
) {
    Card(modifier = modifier.tvFocusOutline()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Rinat IPTV", style = MaterialTheme.typography.titleMedium)
            Text("Плеер", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onLive, modifier = Modifier.fillMaxWidth()) { Text("Эфир") }
            OutlinedButton(onClick = onPlaylists, modifier = Modifier.fillMaxWidth()) { Text("Плейлисты") }
            OutlinedButton(onClick = onFilters, modifier = Modifier.fillMaxWidth()) { Text("Группы") }
            if (favoritesOnly) {
                Button(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("Избранное") }
            } else {
                OutlinedButton(onClick = onFavorites, modifier = Modifier.fillMaxWidth()) { Text("Избранное") }
            }
            OutlinedButton(onClick = onInfo, modifier = Modifier.fillMaxWidth()) { Text("Информация") }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Настройки") }
            Spacer(modifier = Modifier.weight(1f))
            onBack?.let {
                OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
            }
        }
    }
}

@Composable
private fun PlayerCompactHeader(
    selectedChannelName: String?,
    onBack: (() -> Unit)?,
    onOpenChannels: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selectedChannelName ?: "Rinat IPTV",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            OutlinedButton(onClick = onOpenChannels) { Text("Каналы") }
            OutlinedButton(onClick = onOpenSettings) { Text("Опции") }
            onBack?.let { OutlinedButton(onClick = it) { Text("Назад") } }
        }
    }
}

@Composable
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
private fun PlayerCenterPane(
    modifier: Modifier,
    session: InternalPlaybackSession?,
    selectedChannelName: String?,
    isStartingPlayback: Boolean,
    scale: PlayerVideoScale,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteChannelIds: Set<Long>,
    epgProgramsByChannel: Map<Long, List<EpgProgram>>,
    onReady: (Long) -> Unit,
    onError: (Long, String) -> Unit,
    onPlaySelected: () -> Unit,
    onStop: () -> Unit,
    onToggleExpanded: () -> Unit,
    onCycleScale: () -> Unit,
    onSelectChannel: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenChannels: () -> Unit,
    showChannelsButton: Boolean
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            selectedChannelName ?: "Выберите канал",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (session == null) "Плеер готов" else "LIVE",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (showChannelsButton) {
                        OutlinedButton(onClick = onOpenChannels) { Text("Список") }
                    }
                }

                if (session != null) {
                    InternalPlayerHost(
                        session = session,
                        onReady = { onReady(session.sessionId) },
                        onError = { onError(session.sessionId, it) },
                        scale = scale,
                        expanded = false,
                        onToggleExpanded = onToggleExpanded,
                        forceFullWidth = true
                    )
                } else {
                    InternalPlayerPlaceholder(
                        expanded = false,
                        onToggleExpanded = onToggleExpanded,
                        forceFullWidth = true,
                        selectedChannelName = selectedChannelName
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onPlaySelected,
                        enabled = selectedChannelId != null && !isStartingPlayback
                    ) {
                        Text(if (isStartingPlayback) "Запуск..." else "Смотреть")
                    }
                    OutlinedButton(onClick = onStop) { Text("Стоп") }
                    OutlinedButton(onClick = onToggleExpanded) { Text("На весь экран") }
                    OutlinedButton(onClick = onCycleScale) { Text("Кадр: $scale") }
                }
            }
        }

        Text("Последние каналы", style = MaterialTheme.typography.titleSmall)
        PlayerChannelStrip(
            modifier = Modifier.fillMaxWidth(),
            channels = channels,
            selectedChannelId = selectedChannelId,
            favoriteChannelIds = favoriteChannelIds,
            epgProgramsByChannel = epgProgramsByChannel,
            onSelect = onSelectChannel,
            onToggleFavorite = onToggleFavorite
        )
    }
}

@Composable
private fun PlayerChannelStrip(
    modifier: Modifier,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteChannelIds: Set<Long>,
    epgProgramsByChannel: Map<Long, List<EpgProgram>>,
    onSelect: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit
) {
    val ordered = remember(channels, selectedChannelId) {
        val selected = channels.firstOrNull { it.id == selectedChannelId }
        buildList {
            if (selected != null) add(selected)
            addAll(channels.filterNot { it.id == selectedChannelId }.take(19))
        }
    }
    if (ordered.isEmpty()) {
        Card(modifier = modifier.tvFocusOutline()) {
            Text("Нет каналов по текущему фильтру", modifier = Modifier.padding(14.dp))
        }
        return
    }
    LazyRow(
        modifier = modifier.heightIn(min = 118.dp, max = 148.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ordered, key = { it.id }) { channel ->
            val selected = channel.id == selectedChannelId
            Card(
                modifier = Modifier
                    .width(156.dp)
                    .fillMaxHeight()
                    .tvFocusOutline()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ChannelLogo(channel.logo, Modifier.size(28.dp))
                        Text(
                            channel.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    ChannelEpgCompactLine(epgProgramsByChannel[channel.id].orEmpty())
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (selected) {
                            Button(onClick = { onSelect(channel.id) }) { Text("Играет") }
                        } else {
                            OutlinedButton(onClick = { onSelect(channel.id) }) { Text("Открыть") }
                        }
                        OutlinedButton(onClick = { onToggleFavorite(channel.id) }) {
                            Text(if (channel.id in favoriteChannelIds) "★" else "☆")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerChannelBrowser(
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteChannelIds: Set<Long>,
    epgProgramsByChannel: Map<Long, List<EpgProgram>>,
    expandedEpgChannelIds: Set<Long>,
    onToggleProgram: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSelect: (Long) -> Unit,
    onOpenFilters: () -> Unit
) {
    Card(modifier = modifier.tvFocusOutline()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Каналы", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = onOpenFilters) { Text("Фильтр") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск") },
                singleLine = true
            )
            ChannelQuickPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                channels = channels,
                selectedChannelId = selectedChannelId,
                favoriteChannelIds = favoriteChannelIds,
                epgProgramsByChannel = epgProgramsByChannel,
                expandedEpgChannelIds = expandedEpgChannelIds,
                onToggleProgram = onToggleProgram,
                onToggleFavorite = onToggleFavorite,
                onSelect = onSelect
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlayerOverlayDialog(
    overlay: PlayerOverlay,
    state: PlayerUiState,
    hideUnavailable: Boolean,
    favoritesOnly: Boolean,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onSelectGroup: (String?) -> Unit,
    onSelectSubGroup: (String?) -> Unit,
    onToggleUnavailable: () -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onCycleScale: () -> Unit,
    onPlayInternal: () -> Unit,
    onPlayVlc: () -> Unit,
    onExportLogs: () -> Unit,
    onOpenAppSettings: (() -> Unit)?,
    primaryLabel: String
) {
    val title = when (overlay) {
        PlayerOverlay.PLAYLISTS -> "Плейлисты"
        PlayerOverlay.FILTERS -> "Группы и фильтры"
        PlayerOverlay.SETTINGS -> "Настройки плеера"
        PlayerOverlay.INFO -> "Информация"
        PlayerOverlay.NONE -> ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            when (overlay) {
                PlayerOverlay.PLAYLISTS -> {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 560.dp)) {
                        PlaylistScrollPanel(
                            playlists = state.playlists,
                            selectedPlaylistId = state.selectedPlaylistId,
                            onSelectPlaylist = onSelectPlaylist
                        )
                    }
                }
                PlayerOverlay.FILTERS -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (hideUnavailable) {
                                    Button(onClick = onToggleUnavailable) { Text("Недоступные скрыты") }
                                } else {
                                    OutlinedButton(onClick = onToggleUnavailable) { Text("Скрыть недоступные") }
                                }
                                if (favoritesOnly) {
                                    Button(onClick = onToggleFavoritesOnly) { Text("Только избранное") }
                                } else {
                                    OutlinedButton(onClick = onToggleFavoritesOnly) { Text("Только избранное") }
                                }
                            }
                        }
                        item { Text("Группы", style = MaterialTheme.typography.titleSmall) }
                        item {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (state.selectedGroup == null) {
                                    Button(onClick = { onSelectGroup(null) }) { Text("Все") }
                                } else {
                                    OutlinedButton(onClick = { onSelectGroup(null) }) { Text("Все") }
                                }
                                state.availableGroups.forEach { group ->
                                    if (state.selectedGroup == group) {
                                        Button(onClick = { onSelectGroup(group) }) { Text(group) }
                                    } else {
                                        OutlinedButton(onClick = { onSelectGroup(group) }) { Text(group) }
                                    }
                                }
                            }
                        }
                        if (state.selectedGroup != null && state.availableSubGroups.isNotEmpty()) {
                            item { Text("Подгруппы", style = MaterialTheme.typography.titleSmall) }
                            item {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (state.selectedSubGroup == null) {
                                        Button(onClick = { onSelectSubGroup(null) }) { Text("Все") }
                                    } else {
                                        OutlinedButton(onClick = { onSelectSubGroup(null) }) { Text("Все") }
                                    }
                                    state.availableSubGroups.forEach { subgroup ->
                                        if (state.selectedSubGroup == subgroup) {
                                            Button(onClick = { onSelectSubGroup(subgroup) }) { Text(subgroup) }
                                        } else {
                                            OutlinedButton(onClick = { onSelectSubGroup(subgroup) }) { Text(subgroup) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                PlayerOverlay.SETTINGS -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Масштаб видео: ${state.playerVideoScale}")
                        Text("Плеер: ${state.effectivePlayer}")
                        Text("Буфер: ${state.bufferProfile}")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = onCycleScale) { Text("Сменить масштаб") }
                            Button(onClick = onPlayInternal, enabled = state.selectedChannelId != null) { Text("Встроенный") }
                            OutlinedButton(onClick = onPlayVlc, enabled = state.selectedChannelId != null) { Text("VLC") }
                            OutlinedButton(onClick = onExportLogs) { Text("Экспорт логов") }
                            onOpenAppSettings?.let {
                                OutlinedButton(onClick = it) { Text(primaryLabel) }
                            }
                        }
                    }
                }
                PlayerOverlay.INFO -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Каналов: ${state.channels.size}")
                        Text("Поток: ${state.selectedStreamKind}")
                        Text("EPG: ${state.epgStatus}")
                        Text("Engine: ${state.engineMessage}")
                        state.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.lastInfo?.let { Text(it) }
                    }
                }
                PlayerOverlay.NONE -> Unit
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerViewOptions(
    showQuickChannels: Boolean,
    showChannelCatalog: Boolean,
    showPlaylists: Boolean,
    showActions: Boolean,
    showStreamTools: Boolean,
    showEpgWizard: Boolean,
    showTechnicalInfo: Boolean,
    hideUnavailable: Boolean,
    onToggleQuickChannels: () -> Unit,
    onToggleChannelCatalog: () -> Unit,
    onTogglePlaylists: () -> Unit,
    onToggleActions: () -> Unit,
    onToggleStreamTools: () -> Unit,
    onToggleEpgWizard: () -> Unit,
    onToggleTechnicalInfo: () -> Unit,
    onToggleUnavailable: () -> Unit,
    onExportLogs: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompactToggleButton(showQuickChannels, "Быстрый список", onToggleQuickChannels)
        CompactToggleButton(showChannelCatalog, "Каталог", onToggleChannelCatalog)
        CompactToggleButton(showPlaylists, "Плейлисты", onTogglePlaylists)
        CompactToggleButton(showActions, "Действия", onToggleActions)
        CompactToggleButton(showStreamTools, "Тест потока", onToggleStreamTools)
        CompactToggleButton(showEpgWizard, "EPG", onToggleEpgWizard)
        CompactToggleButton(showTechnicalInfo, "Техинфо", onToggleTechnicalInfo)
        CompactToggleButton(!hideUnavailable, "Недоступные", onToggleUnavailable)
        OutlinedButton(onClick = onExportLogs) {
            Text("Логи")
        }
    }
}

@Composable
private fun CompactToggleButton(
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
private fun MultiviewPanel(
    multiviewMode: MultiviewMode,
    primarySession: InternalPlaybackSession?,
    additionalSessions: List<InternalPlaybackSession?>,
    selectedChannelName: String?,
    targetPaneIndex: Int,
    targetPaneIndices: List<Int>,
    primaryExpanded: Boolean,
    scale: PlayerVideoScale,
    onPrimaryReady: (Long) -> Unit,
    onPrimaryError: (Long, String) -> Unit,
    onPaneReady: (Int) -> Unit,
    onPaneError: (Int, String) -> Unit,
    onTargetPaneSelected: (Int) -> Unit,
    onTogglePrimaryExpanded: () -> Unit,
    onStopPane: (Int) -> Unit
) {
    val paneSessions = buildList {
        add(1 to primarySession.takeUnless { primaryExpanded })
        when (multiviewMode) {
            MultiviewMode.OFF -> Unit
            MultiviewMode.TWO_UP -> add(2 to additionalSessions.getOrNull(0))
            MultiviewMode.FOUR_UP -> {
                add(2 to additionalSessions.getOrNull(0))
                add(3 to additionalSessions.getOrNull(1))
                add(4 to additionalSessions.getOrNull(2))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Multiview ${if (multiviewMode == MultiviewMode.FOUR_UP) "4-up" else "2-up"}", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                paneSessions
                    .filter { it.first > 1 }
                    .forEach { (paneIndex, session) ->
                        OutlinedButton(onClick = { onStopPane(paneIndex) }, enabled = session != null) {
                            Text("Окно $paneIndex: стоп")
                        }
                    }
            }
        }
        if (targetPaneIndices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Быстрый выбор канала: целевое окно $targetPaneIndex",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    targetPaneIndices.forEach { paneIndex ->
                        if (paneIndex == targetPaneIndex) {
                            Button(onClick = { onTargetPaneSelected(paneIndex) }) {
                                Text("Окно $paneIndex")
                            }
                        } else {
                            OutlinedButton(onClick = { onTargetPaneSelected(paneIndex) }) {
                                Text("Окно $paneIndex")
                            }
                        }
                    }
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val wideTwoUp = maxWidth >= 760.dp
            val wideFourUp = maxWidth >= 960.dp
            when {
                multiviewMode == MultiviewMode.FOUR_UP && wideFourUp -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        paneSessions.chunked(2).forEach { rowSessions ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowSessions.forEach { (paneIndex, session) ->
                                    val realPrimarySession = primarySession.takeIf { paneIndex == 1 }
                                    MultiviewPane(
                                        title = "Окно $paneIndex",
                                        session = session,
                                        selectedChannelName = if (paneIndex == 1) {
                                            selectedChannelName
                                        } else {
                                            session?.channelName
                                        },
                                        scale = scale,
                                        onReady = if (paneIndex == 1) {
                                            { realPrimarySession?.sessionId?.let(onPrimaryReady) }
                                        } else {
                                            { onPaneReady(paneIndex) }
                                        },
                                        onError = if (paneIndex == 1) {
                                            { message -> realPrimarySession?.sessionId?.let { onPrimaryError(it, message) } }
                                        } else {
                                            { message -> onPaneError(paneIndex, message) }
                                        },
                                        onToggleExpanded = if (paneIndex == 1) onTogglePrimaryExpanded else ({}),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowSessions.size == 1) {
                                    SpacerPane(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                multiviewMode == MultiviewMode.TWO_UP && wideTwoUp -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        paneSessions.forEach { (paneIndex, session) ->
                            val realPrimarySession = primarySession.takeIf { paneIndex == 1 }
                            MultiviewPane(
                                title = "Окно $paneIndex",
                                session = session,
                                selectedChannelName = if (paneIndex == 1) {
                                    selectedChannelName
                                } else {
                                    session?.channelName
                                },
                                scale = scale,
                                onReady = if (paneIndex == 1) {
                                    { realPrimarySession?.sessionId?.let(onPrimaryReady) }
                                } else {
                                    { onPaneReady(paneIndex) }
                                },
                                onError = if (paneIndex == 1) {
                                    { message -> realPrimarySession?.sessionId?.let { onPrimaryError(it, message) } }
                                } else {
                                    { message -> onPaneError(paneIndex, message) }
                                },
                                onToggleExpanded = if (paneIndex == 1) onTogglePrimaryExpanded else ({}),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        paneSessions.forEach { (paneIndex, session) ->
                            val realPrimarySession = primarySession.takeIf { paneIndex == 1 }
                            MultiviewPane(
                                title = "Окно $paneIndex",
                                session = session,
                                selectedChannelName = if (paneIndex == 1) {
                                    selectedChannelName
                                } else {
                                    session?.channelName
                                },
                                scale = scale,
                                onReady = if (paneIndex == 1) {
                                    { realPrimarySession?.sessionId?.let(onPrimaryReady) }
                                } else {
                                    { onPaneReady(paneIndex) }
                                },
                                onError = if (paneIndex == 1) {
                                    { message -> realPrimarySession?.sessionId?.let { onPrimaryError(it, message) } }
                                } else {
                                    { message -> onPaneError(paneIndex, message) }
                                },
                                onToggleExpanded = if (paneIndex == 1) onTogglePrimaryExpanded else ({}),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpacerPane(modifier: Modifier = Modifier) {
    Box(modifier = modifier)
}

@Composable
@UnstableApi
private fun MultiviewPane(
    title: String,
    session: InternalPlaybackSession?,
    selectedChannelName: String?,
    scale: PlayerVideoScale,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (session != null) "$title: ${session.channelName}" else "$title: канал не выбран",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (session != null) {
            InternalPlayerHost(
                session = session,
                onReady = onReady,
                onError = onError,
                scale = scale,
                expanded = false,
                onToggleExpanded = onToggleExpanded,
                forceFullWidth = true
            )
        } else {
            InternalPlayerPlaceholder(
                expanded = false,
                onToggleExpanded = onToggleExpanded,
                forceFullWidth = true,
                selectedChannelName = selectedChannelName
            )
        }
    }
}

@Composable
@UnstableApi
private fun FullscreenInternalPlayerOverlay(
    session: InternalPlaybackSession?,
    selectedChannelName: String?,
    scale: PlayerVideoScale,
    onReady: (Long) -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onStop: () -> Unit
) {
    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (session != null) {
            InternalPlayerHost(
                session = session,
                onReady = { onReady(session.sessionId) },
                onError = onError,
                scale = scale,
                expanded = true,
                onToggleExpanded = onClose,
                forceFullWidth = true,
                fullscreenMode = true
            )
        } else {
            InternalPlayerPlaceholder(
                expanded = true,
                onToggleExpanded = onClose,
                forceFullWidth = true,
                selectedChannelName = selectedChannelName,
                fullscreenMode = true
            )
        }
        var localTime by remember { mutableStateOf(formatPlayerLocalTime(System.currentTimeMillis())) }
        LaunchedEffect(Unit) {
            while (isActive) {
                localTime = formatPlayerLocalTime(System.currentTimeMillis())
                delay(1_000L)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selectedChannelName ?: "Встроенный плеер",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                localTime,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.58f), MaterialTheme.shapes.medium)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = onPreviousChannel) { Text("◀ Канал") }
            OutlinedButton(onClick = onStop) { Text("Стоп") }
            OutlinedButton(onClick = onNextChannel) { Text("Канал ▶") }
            Button(onClick = onClose) { Text("Свернуть") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelQuickPanel(
    modifier: Modifier = Modifier,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteChannelIds: Set<Long>,
    epgProgramsByChannel: Map<Long, List<EpgProgram>>,
    expandedEpgChannelIds: Set<Long>,
    title: String = "Список каналов",
    onToggleProgram: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSelect: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedChannelId, channels) {
        val index = channels.indexOfFirst { it.id == selectedChannelId }
        if (index >= 0) listState.scrollToItem(index)
    }

    Card(
        modifier = modifier
            .tvFocusOutline()
            .onPreviewKeyEvent { event ->
                handleQuickListKeyEvent(
                    event = event,
                    listState = listState,
                    scope = scope,
                    canScroll = channels.isNotEmpty()
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "$title · ${channels.size}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (channels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет каналов")
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(channels, key = { it.id }) { channel ->
                        val programs = epgProgramsByChannel[channel.id].orEmpty()
                        val expanded = channel.id in expandedEpgChannelIds
                        val selected = channel.id == selectedChannelId
                        Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(7.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ChannelLogo(channel.logo, Modifier.size(30.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            if (selected) "● ${channel.name}" else channel.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        ChannelEpgCompactLine(programs)
                                    }
                                    OutlinedButton(onClick = { onToggleFavorite(channel.id) }) {
                                        Text(if (channel.id in favoriteChannelIds) "★" else "☆")
                                    }
                                }
                                if (expanded) ChannelProgramList(programs)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (selected) {
                                        Button(onClick = { onSelect(channel.id) }, modifier = Modifier.weight(1f)) { Text("Играет") }
                                    } else {
                                        OutlinedButton(onClick = { onSelect(channel.id) }, modifier = Modifier.weight(1f)) { Text("Смотреть") }
                                    }
                                    OutlinedButton(
                                        onClick = { onToggleProgram(channel.id) },
                                        enabled = programs.isNotEmpty()
                                    ) { Text(if (expanded) "−" else "+") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistScrollPanel(
    playlists: List<Playlist>,
    selectedPlaylistId: Long?,
    onSelectPlaylist: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedPlaylistId, playlists) {
        val index = playlists.indexOfFirst { it.id == selectedPlaylistId }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Плейлисты (${playlists.size})", style = MaterialTheme.typography.titleMedium)
            if (playlists.isEmpty()) {
                Text("Плейлисты не найдены")
                return@Column
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("${playlist.channelCount} каналов", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { onSelectPlaylist(playlist.id) }) {
                                    Text(
                                        if (playlist.id == selectedPlaylistId) {
                                            "Текущий плейлист"
                                        } else {
                                            "Открыть плейлист"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalListScrollControls(
                    listState = listState,
                    itemCount = playlists.size,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelCatalogScrollPanel(
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteChannelIds: Set<Long>,
    epgProgramsByChannel: Map<Long, List<EpgProgram>>,
    expandedEpgChannelIds: Set<Long>,
    availablePaneTargets: List<Int>,
    showTechnicalInfo: Boolean,
    onToggleProgram: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayChannel: (Long) -> Unit,
    onPlayInPane: (Long, Int) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedChannelId, channels) {
        val index = channels.indexOfFirst { it.id == selectedChannelId }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Каталог каналов · ${channels.size}", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 620.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(channels, key = { it.id }) { channel ->
                        ChannelCatalogRow(
                            channel = channel,
                            selected = channel.id == selectedChannelId,
                            epgPrograms = epgProgramsByChannel[channel.id].orEmpty(),
                            programExpanded = channel.id in expandedEpgChannelIds,
                            isFavorite = channel.id in favoriteChannelIds,
                            availablePaneTargets = availablePaneTargets,
                            showTechnicalInfo = showTechnicalInfo,
                            onToggleProgram = onToggleProgram,
                            onToggleFavorite = onToggleFavorite,
                            onPlayChannel = onPlayChannel,
                            onPlayInPane = onPlayInPane
                        )
                    }
                }
                VerticalListScrollControls(
                    listState = listState,
                    itemCount = channels.size,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelCatalogRow(
    channel: Channel,
    selected: Boolean,
    epgPrograms: List<EpgProgram>,
    programExpanded: Boolean,
    isFavorite: Boolean,
    availablePaneTargets: List<Int>,
    showTechnicalInfo: Boolean,
    onToggleProgram: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayChannel: (Long) -> Unit,
    onPlayInPane: (Long, Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                ChannelLogo(
                    logoUrl = channel.logo,
                    modifier = Modifier.size(54.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        channel.group ?: "Без группы",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ChannelEpgCompactLine(programs = epgPrograms)
                    if (programExpanded) {
                        ChannelProgramList(programs = epgPrograms)
                    }
                    if (showTechnicalInfo) {
                        Text("URL: ${channel.streamUrl}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onPlayChannel(channel.id) }) {
                    Text(if (selected) "Играет" else "Выбрать и играть")
                }
                OutlinedButton(
                    onClick = { onToggleProgram(channel.id) },
                    enabled = epgPrograms.isNotEmpty()
                ) {
                    Text(if (programExpanded) "Скрыть программу" else "Программа")
                }
                OutlinedButton(onClick = { onToggleFavorite(channel.id) }) {
                    Text(if (isFavorite) "Убрать из избранного" else "В избранное")
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availablePaneTargets.forEach { paneIndex ->
                        OutlinedButton(onClick = { onPlayInPane(channel.id, paneIndex) }) {
                            Text("В окно $paneIndex")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelEpgCompactLine(programs: List<EpgProgram>) {
    if (programs.isEmpty()) return
    val now = programs.firstOrNull()
    val next = programs.drop(1).firstOrNull()
    val text = buildString {
        now?.let { program ->
            append("Сейчас: ")
            append(formatEpgTime(program.startEpochMs))
            append(" ")
            append(program.title)
        }
        next?.let { program ->
            if (isNotEmpty()) append(" | ")
            append("Далее: ")
            append(formatEpgTime(program.startEpochMs))
            append(" ")
            append(program.title)
        }
    }
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ChannelProgramList(programs: List<EpgProgram>) {
    if (programs.isEmpty()) {
        Text(
            text = "Программа не найдена",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        programs.take(6).forEach { program ->
            Text(
                text = "${formatEpgTime(program.startEpochMs)}-${formatEpgTime(program.endEpochMs)}  ${program.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VerticalListScrollControls(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    lineStep: Int = QUICK_SCROLL_STEP,
    pageStep: Int = QUICK_PAGE_STEP
) {
    val scope = rememberCoroutineScope()
    val lastIndex = (itemCount - 1).coerceAtLeast(0)
    val firstVisible by remember(listState, lastIndex) {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, lastIndex) }
    }
    val enabled = itemCount > 1

    fun scrollTo(index: Int) {
        if (!enabled) return
        scope.launch {
            listState.animateScrollToItem(index.coerceIn(0, lastIndex))
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedButton(onClick = { scrollTo(0) }, enabled = enabled) {
            Text("В начало")
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { scrollTo(firstVisible - pageStep) }, enabled = enabled) {
                Text("Pg -")
            }
            OutlinedButton(onClick = { scrollTo(firstVisible - lineStep) }, enabled = enabled) {
                Text("▲")
            }
            Text("${firstVisible + 1}/$itemCount", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scrollTo(firstVisible + lineStep) }, enabled = enabled) {
                Text("▼")
            }
            OutlinedButton(onClick = { scrollTo(firstVisible + pageStep) }, enabled = enabled) {
                Text("Pg +")
            }
        }
        OutlinedButton(onClick = { scrollTo(lastIndex) }, enabled = enabled) {
            Text("В конец")
        }
    }
}

private fun wrappedIndex(rawIndex: Int, lastIndex: Int): Int {
    if (lastIndex <= 0) return 0
    return when {
        rawIndex < 0 -> lastIndex
        rawIndex > lastIndex -> 0
        else -> rawIndex
    }
}

private fun Channel.matchesParentalKeywords(keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = listOf(name, group.orEmpty())
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return keywords.any { keyword ->
        val normalized = keyword.trim().lowercase(Locale.ROOT)
        normalized.isNotBlank() && haystack.contains(normalized)
    }
}

private fun handleQuickListKeyEvent(
    event: KeyEvent,
    listState: LazyListState,
    scope: CoroutineScope,
    canScroll: Boolean
): Boolean {
    if (!canScroll || event.type != KeyEventType.KeyDown) return false
    val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    if (lastIndex == 0) return false
    val current = listState.firstVisibleItemIndex
    fun moveTo(index: Int): Boolean {
        val target = wrappedIndex(index, lastIndex)
        scope.launch { listState.animateScrollToItem(target) }
        return true
    }
    return when (event.key) {
        Key.PageUp -> moveTo(current - QUICK_PAGE_STEP)
        Key.PageDown -> moveTo(current + QUICK_PAGE_STEP)
        Key.MoveHome -> moveTo(0)
        Key.MoveEnd -> moveTo(lastIndex)
        else -> false
    }
}

@Composable
private fun ChannelLogo(
    logoUrl: String?,
    modifier: Modifier = Modifier
) {
    val normalized = logoUrl?.trim().orEmpty()
    if (normalized.isBlank()) {
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            Text("TV", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    AsyncImage(
        model = normalized,
        contentDescription = "Логотип канала",
        modifier = modifier.clip(MaterialTheme.shapes.small),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun InternalPlayerPlaceholder(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    forceFullWidth: Boolean = false,
    selectedChannelName: String?,
    fullscreenMode: Boolean = false
) {
    Box(
        modifier = Modifier.playerViewportModifier(
            expanded = expanded,
            forceFullWidth = forceFullWidth,
            fullscreenMode = fullscreenMode
        )
            .tvFocusOutline()
            .pointerInput(expanded) {
                detectTapGestures(onDoubleTap = { onToggleExpanded() })
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Встроенный плеер")
            if (selectedChannelName == null) {
                Text("Выберите канал из списка справа")
            } else {
                Text("Выбран: $selectedChannelName")
                Text("Канал запускается сразу при выборе")
            }
        }
        OutlinedButton(
            onClick = onToggleExpanded,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Text(if (expanded) "Свернуть" else "Развернуть")
        }
    }
}

private fun Modifier.playerViewportModifier(
    expanded: Boolean,
    forceFullWidth: Boolean,
    fullscreenMode: Boolean = false
): Modifier {
    return if (fullscreenMode) {
        fillMaxSize()
    } else if (forceFullWidth) {
        fillMaxWidth()
            .aspectRatio(if (expanded) 16f / 9f else 4f / 3f)
    } else if (expanded) {
        fillMaxWidth()
            .aspectRatio(16f / 9f)
    } else {
        fillMaxWidth()
            .aspectRatio(16f / 9f)
    }
}

@UnstableApi
@Composable
private fun InternalPlayerHost(
    session: InternalPlaybackSession,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    scale: PlayerVideoScale,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    forceFullWidth: Boolean = false,
    fullscreenMode: Boolean = false
) {
    val context = LocalContext.current
    val playerBuildResult = remember(session.sessionId, session.requestHeaders) {
        runCatching {
            val requestHeaders = compatibleHttpHeaders(session.requestHeaders)
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setUserAgent(requestHeaders["User-Agent"] ?: IPTV_USER_AGENT)
                .setDefaultRequestProperties(requestHeaders.filterKeys { it != "User-Agent" })
            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            val trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setExceedVideoConstraintsIfNecessary(true)
                        .setAllowVideoMixedMimeTypeAdaptiveness(true)
                )
            }
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(session.bufferConfig.toLoadControl())
                .build()
        }
    }
    val exoPlayer = playerBuildResult.getOrNull()
    val initError = playerBuildResult.exceptionOrNull()
    var currentTracks by remember(session.sessionId) { mutableStateOf(Tracks.EMPTY) }
    var readyReported by remember(session.sessionId) { mutableStateOf(false) }
    var inlineTrackPanelVisible by remember(session.sessionId) { mutableStateOf(false) }
    var fullscreenTrackPanelVisible by remember(session.sessionId) { mutableStateOf(false) }
    val trackPreferenceStore = remember(context) { PlayerTrackPreferenceStore(context) }
    var savedTrackPreferences by remember { mutableStateOf(trackPreferenceStore.loadAll()) }

    if (exoPlayer == null) {
        DisposableEffect(session.sessionId, initError?.message) {
            onError("Player init failed: ${initError?.message ?: "unknown"}")
            onDispose {}
        }
        Text(
            text = "Не удалось инициализировать встроенный плеер. Попробуйте VLC.",
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    DisposableEffect(session.sessionId) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !readyReported) {
                    readyReported = true
                    onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    return
                }
                val msg = formatPlaybackException(error)
                onError(msg)
                try {
                    FileLogger.write(context, "ERROR", "Player", msg, error)
                } catch (ignored: Exception) {}
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }
        }

        exoPlayer.addListener(listener)
        val startResult = runCatching {
            val mediaItem = MediaItem.Builder()
                .setUri(session.streamUrl)
                .apply {
                    inferMediaMimeType(session.streamUrl)?.let { mimeType ->
                        setMimeType(mimeType)
                    }
                }
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        startResult.exceptionOrNull()?.let { throwable ->
            val m = throwable.message ?: throwable.javaClass.simpleName
            onError(m)
            try { FileLogger.write(context, "ERROR", "PlayerInit", m, throwable) } catch (ignored: Exception) {}
        }

        onDispose {
            runCatching {
                exoPlayer.playWhenReady = false
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.clearVideoSurface()
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Один мягкий retry при долгой буферизации. Без лимита старые ТВ-боксы могут зависать
    // на повторяющихся stop/prepare во время плохого потока.
    DisposableEffect(session.sessionId, exoPlayer) {
        val recoveryJob = kotlinx.coroutines.Job()
        val recoveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + recoveryJob)
        var bufferingSince = 0L
        var recoveryAttempts = 0
        val recoveryPolicy = session.recoveryPolicy

        val stateListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    bufferingSince = System.currentTimeMillis()
                } else {
                    bufferingSince = 0L
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // пробуем небольшую очистку при ошибке
                bufferingSince = 0L
            }
        }

        exoPlayer.addListener(stateListener)

        recoveryScope.launch {
            try {
                while (isActive) {
                    kotlinx.coroutines.delay(recoveryPolicy.checkIntervalMs)
                    val since = bufferingSince
                    if (since != 0L) {
                        val elapsed = System.currentTimeMillis() - since
                        if (
                            elapsed > recoveryPolicy.retryAfterMs &&
                            recoveryAttempts < recoveryPolicy.maxRecoveryAttempts
                        ) {
                            recoveryAttempts += 1
                            bufferingSince = 0L
                            runCatching {
                                exoPlayer.playWhenReady = false
                                exoPlayer.stop()
                                exoPlayer.prepare()
                                exoPlayer.playWhenReady = true
                            }
                        } else if (
                            elapsed > recoveryPolicy.failAfterMs &&
                            recoveryAttempts >= recoveryPolicy.maxRecoveryAttempts
                        ) {
                            onError("Buffering timeout after recovery attempt")
                            break
                        }
                    }
                }
            } finally {
                // no-op
            }
        }

        onDispose {
            recoveryJob.cancel()
            exoPlayer.removeListener(stateListener)
        }
    }

    val viewportModifier = Modifier.playerViewportModifier(
        expanded = expanded,
        forceFullWidth = forceFullWidth,
        fullscreenMode = fullscreenMode
    )

    LaunchedEffect(exoPlayer, currentTracks, savedTrackPreferences) {
        val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@LaunchedEffect
        applySavedTrackPreferences(
            tracks = currentTracks,
            selector = selector,
            preferences = savedTrackPreferences
        )
    }

    Box(
        modifier = viewportModifier.pointerInput(session.sessionId) {
            detectTapGestures(onDoubleTap = { onToggleExpanded() })
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    resizeMode = when (scale) {
                        PlayerVideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PlayerVideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        PlayerVideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                    player = exoPlayer
                }
            },
            update = { view ->
                view.resizeMode = when (scale) {
                    PlayerVideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PlayerVideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    PlayerVideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                view.player = exoPlayer
            },
            onRelease = { view ->
                view.player = null
            }
        )
        OutlinedButton(
            onClick = onToggleExpanded,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Text(if (expanded) "Свернуть" else "Развернуть")
        }
        if (!fullscreenMode) {
            PlayerLocalTimeBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }
        if (fullscreenMode) {
            OutlinedButton(
                onClick = { fullscreenTrackPanelVisible = !fullscreenTrackPanelVisible },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Text(if (fullscreenTrackPanelVisible) "Скрыть дорожки" else "Дорожки")
            }
        }
        if (fullscreenMode && fullscreenTrackPanelVisible) {
            TrackSelectionPanel(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopStart)
                    .padding(8.dp)
                    .fillMaxWidth(0.72f)
                    .heightIn(max = 260.dp),
                tracks = currentTracks,
                savedPreferences = savedTrackPreferences,
                onSelectAuto = { trackType ->
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setTrackTypeDisabled(trackType, false)
                            .clearOverridesOfType(trackType)
                    )
                    savedTrackPreferences = trackPreferenceStore.saveAuto(trackType)
                },
                onDisable = { trackType ->
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    selector.setParameters(
                        selector.buildUponParameters()
                            .clearOverridesOfType(trackType)
                            .setTrackTypeDisabled(trackType, true)
                    )
                    savedTrackPreferences = trackPreferenceStore.saveDisabled(trackType)
                },
                onSelectTrack = { trackType, group, trackIndex ->
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    val format = group.getTrackFormat(trackIndex)
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setTrackTypeDisabled(trackType, false)
                            .clearOverridesOfType(trackType)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                    )
                    savedTrackPreferences = trackPreferenceStore.saveSelected(
                        trackType = trackType,
                        language = format.language,
                        label = format.label
                    )
                },
                onClearSavedPreferences = {
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    )
                    savedTrackPreferences = trackPreferenceStore.clearAll()
                }
            )
        }
    }

    if (!fullscreenMode && (currentTracks.groups.isNotEmpty() || savedTrackPreferences.isNotEmpty())) {
        OutlinedButton(
            onClick = { inlineTrackPanelVisible = !inlineTrackPanelVisible },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(if (inlineTrackPanelVisible) "Скрыть дорожки" else "Дорожки")
        }
    }

    if (!fullscreenMode && inlineTrackPanelVisible) {
        TrackSelectionPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            tracks = currentTracks,
            savedPreferences = savedTrackPreferences,
            onSelectAuto = { trackType ->
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                selector.setParameters(
                    selector.buildUponParameters()
                        .setTrackTypeDisabled(trackType, false)
                        .clearOverridesOfType(trackType)
                )
                savedTrackPreferences = trackPreferenceStore.saveAuto(trackType)
            },
            onDisable = { trackType ->
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                selector.setParameters(
                    selector.buildUponParameters()
                        .clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, true)
                )
                savedTrackPreferences = trackPreferenceStore.saveDisabled(trackType)
            },
            onSelectTrack = { trackType, group, trackIndex ->
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                val format = group.getTrackFormat(trackIndex)
                selector.setParameters(
                    selector.buildUponParameters()
                        .setTrackTypeDisabled(trackType, false)
                        .clearOverridesOfType(trackType)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                )
                savedTrackPreferences = trackPreferenceStore.saveSelected(
                    trackType = trackType,
                    language = format.language,
                    label = format.label
                )
            },
            onClearSavedPreferences = {
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                selector.setParameters(
                    selector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                )
                savedTrackPreferences = trackPreferenceStore.clearAll()
            }
        )
    }
}

@Composable
private fun PlayerLocalTimeBadge(modifier: Modifier = Modifier) {
    var localTime by remember { mutableStateOf(formatPlayerLocalTime(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (isActive) {
            localTime = formatPlayerLocalTime(System.currentTimeMillis())
            delay(1_000L)
        }
    }
    Text(
        text = localTime,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
        maxLines = 1
    )
}

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun TrackSelectionPanel(
    modifier: Modifier = Modifier,
    tracks: Tracks,
    savedPreferences: Map<Int, PlayerTrackPreference>,
    onSelectAuto: (Int) -> Unit,
    onDisable: (Int) -> Unit,
    onSelectTrack: (Int, Tracks.Group, Int) -> Unit,
    onClearSavedPreferences: () -> Unit
) {
    val groups = tracks.groups
    val videoOptions = remember(groups) { trackOptions(groups, C.TRACK_TYPE_VIDEO) }
    val audioOptions = remember(groups) { trackOptions(groups, C.TRACK_TYPE_AUDIO) }
    val textOptions = remember(groups) { trackOptions(groups, C.TRACK_TYPE_TEXT) }

    if (videoOptions.isEmpty() && audioOptions.isEmpty() && textOptions.isEmpty()) {
        Text("Дорожки: поток ещё не отдал список аудио/субтитров/видео", style = MaterialTheme.typography.bodySmall)
        return
    }

    Card(modifier = modifier.heightIn(max = 260.dp).tvFocusOutline()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Дорожки плеера", style = MaterialTheme.typography.titleSmall)
            }
            if (savedPreferences.isNotEmpty()) {
                item {
                    Text(
                        text = "Сохранённые предпочтения применяются автоматически",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    OutlinedButton(onClick = onClearSavedPreferences) {
                        Text("Сбросить предпочтения")
                    }
                }
            }
            item {
                TrackTypeRow(
                    title = "Видео",
                    trackType = C.TRACK_TYPE_VIDEO,
                    options = videoOptions,
                    groups = groups,
                    onSelectAuto = onSelectAuto,
                    onDisable = onDisable,
                    onSelectTrack = onSelectTrack
                )
            }
            item {
                TrackTypeRow(
                    title = "Аудио",
                    trackType = C.TRACK_TYPE_AUDIO,
                    options = audioOptions,
                    groups = groups,
                    onSelectAuto = onSelectAuto,
                    onDisable = onDisable,
                    onSelectTrack = onSelectTrack
                )
            }
            item {
                TrackTypeRow(
                    title = "Субтитры",
                    trackType = C.TRACK_TYPE_TEXT,
                    options = textOptions,
                    groups = groups,
                    onSelectAuto = onSelectAuto,
                    onDisable = onDisable,
                    onSelectTrack = onSelectTrack
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackTypeRow(
    title: String,
    trackType: Int,
    options: List<PlayerTrackOption>,
    groups: List<Tracks.Group>,
    onSelectAuto: (Int) -> Unit,
    onDisable: (Int) -> Unit,
    onSelectTrack: (Int, Tracks.Group, Int) -> Unit
) {
    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onSelectAuto(trackType) }) {
                Text("Авто")
            }
            OutlinedButton(onClick = { onDisable(trackType) }) {
                Text("Выкл")
            }
            options.forEach { option ->
                val group = groups.getOrNull(option.groupIndex) ?: return@forEach
                val buttonText = if (option.selected) {
                    "✓ ${option.label}"
                } else {
                    option.label
                }
                if (option.selected) {
                    Button(
                        onClick = { onSelectTrack(trackType, group, option.trackIndex) },
                        enabled = option.supported
                    ) {
                        Text(buttonText)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelectTrack(trackType, group, option.trackIndex) },
                        enabled = option.supported
                    ) {
                        Text(buttonText)
                    }
                }
            }
        }
    }
}

@UnstableApi
private fun trackOptions(groups: List<Tracks.Group>, trackType: Int): List<PlayerTrackOption> {
    return groups.flatMapIndexed { groupIndex, group ->
        if (group.type != trackType) {
            emptyList()
        } else {
            (0 until group.length).map { trackIndex ->
                PlayerTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = formatTrackLabel(group.getTrackFormat(trackIndex), trackType, trackIndex),
                    selected = group.isTrackSelected(trackIndex),
                    supported = group.isTrackSupported(trackIndex)
                )
            }
        }
    }
}

@UnstableApi
private fun applySavedTrackPreferences(
    tracks: Tracks,
    selector: DefaultTrackSelector,
    preferences: Map<Int, PlayerTrackPreference>
) {
    if (tracks.groups.isEmpty() || preferences.isEmpty()) return

    val builder = selector.buildUponParameters()
    var changed = false
    preferences.forEach { (trackType, preference) ->
        when (preference.mode) {
            PlayerTrackPreferenceMode.AUTO -> {
                builder.setTrackTypeDisabled(trackType, false)
                    .clearOverridesOfType(trackType)
                changed = true
            }
            PlayerTrackPreferenceMode.DISABLED -> {
                builder.clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                changed = true
            }
            PlayerTrackPreferenceMode.SELECTED -> {
                val candidate = PlayerTrackPreferenceMatcher.select(
                    preference = preference,
                    candidates = trackPreferenceCandidates(tracks.groups, trackType)
                ) ?: return@forEach
                val group = tracks.groups.getOrNull(candidate.groupIndex) ?: return@forEach
                builder.setTrackTypeDisabled(trackType, false)
                    .clearOverridesOfType(trackType)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, candidate.trackIndex))
                changed = true
            }
        }
    }
    if (changed) {
        selector.setParameters(builder)
    }
}

@UnstableApi
private fun trackPreferenceCandidates(
    groups: List<Tracks.Group>,
    trackType: Int
): List<PlayerTrackPreferenceCandidate> {
    return groups.flatMapIndexed { groupIndex, group ->
        if (group.type != trackType) {
            emptyList()
        } else {
            (0 until group.length).map { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                PlayerTrackPreferenceCandidate(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    language = format.language,
                    label = format.label,
                    supported = group.isTrackSupported(trackIndex)
                )
            }
        }
    }
}

@UnstableApi
private fun formatTrackLabel(format: Format, trackType: Int, trackIndex: Int): String {
    val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
    val label = format.label?.takeIf { it.isNotBlank() }
    val codec = format.codecs?.takeIf { it.isNotBlank() }
    val details = when (trackType) {
        C.TRACK_TYPE_VIDEO -> buildList {
            if (format.width > 0 && format.height > 0) add("${format.width}x${format.height}")
            if (format.bitrate > 0) add("${format.bitrate / 1000} kbps")
            codec?.let { add(it) }
        }
        C.TRACK_TYPE_AUDIO -> buildList {
            language?.let { add(it.uppercase(Locale.ROOT)) }
            if (format.channelCount > 0) add("${format.channelCount}ch")
            if (format.sampleRate > 0) add("${format.sampleRate / 1000} kHz")
            codec?.let { add(it) }
        }
        C.TRACK_TYPE_TEXT -> buildList {
            language?.let { add(it.uppercase(Locale.ROOT)) }
            codec?.let { add(it) }
        }
        else -> emptyList()
    }
    return listOfNotNull(label, details.joinToString(" | ").takeIf { it.isNotBlank() })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" | ")
        ?: "Дорожка ${trackIndex + 1}"
}

@UnstableApi
private fun inferMediaMimeType(url: String): String? {
    val lowered = url.substringBefore('|').lowercase(Locale.ROOT)
    val path = lowered.substringBefore('?').substringBefore('#')
    val query = lowered.substringAfter('?', "")
    return when {
        path.endsWith(".m3u8") ||
            path.endsWith(".m3u") ||
            query.contains("type=m3u8") ||
            query.contains("format=m3u8") ||
            query.contains("extension=m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") || query.contains("type=mpd") -> MimeTypes.APPLICATION_MPD
        (path.contains("/manifest") && path.contains("ism")) ||
            path.endsWith(".ism") ||
            path.endsWith(".isml") -> MimeTypes.APPLICATION_SS
        lowered.startsWith("rtsp://") -> MimeTypes.APPLICATION_RTSP
        path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts") -> MimeTypes.VIDEO_MP2T
        path.endsWith(".flv") -> MimeTypes.VIDEO_FLV
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> MimeTypes.VIDEO_MP4
        path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        path.endsWith(".avi") -> MimeTypes.VIDEO_AVI
        path.endsWith(".mpg") || path.endsWith(".mpeg") || path.endsWith(".vob") -> MimeTypes.VIDEO_PS
        path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
        path.endsWith(".aac") -> MimeTypes.AUDIO_AAC
        path.endsWith(".ac3") -> MimeTypes.AUDIO_AC3
        path.endsWith(".ec3") || path.endsWith(".eac3") -> MimeTypes.AUDIO_E_AC3
        path.endsWith(".ogg") || path.endsWith(".oga") -> MimeTypes.AUDIO_OGG
        path.endsWith(".wav") -> MimeTypes.AUDIO_WAV
        path.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
        else -> null
    }
}

private fun compatibleHttpHeaders(headers: Map<String, String>): Map<String, String> {
    return buildMap {
        put("User-Agent", IPTV_USER_AGENT)
        put("Accept", "*/*")
        put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                put(name, value)
            }
        }
    }
}

private fun healthPriority(health: ChannelHealth): Int {
    return when (health) {
        ChannelHealth.AVAILABLE -> 0
        ChannelHealth.UNSTABLE -> 1
        ChannelHealth.UNKNOWN -> 2
        ChannelHealth.UNAVAILABLE -> 3
    }
}

private fun channelMatchesGroup(
    channel: Channel,
    selectedGroup: String?,
    selectedSubGroup: String?
): Boolean {
    val groupRaw = channel.group?.trim().orEmpty()
    if (groupRaw.isEmpty()) {
        return selectedGroup == null && selectedSubGroup == null
    }
    val parts = groupRaw.split(Regex("\\s*(?:\\||/|>|::|\\\\\\\\)\\s*"), limit = 2)
    val group = parts.firstOrNull()?.trim().orEmpty()
    val subGroup = parts.getOrNull(1)?.trim().orEmpty()
    if (selectedGroup != null && group != selectedGroup) return false
    if (selectedSubGroup != null && subGroup != selectedSubGroup) return false
    return true
}

private fun formatPlaybackException(error: PlaybackException): String {
    val cause = error.cause
    val causeInfo = if (cause == null) {
        "-"
    } else {
        "${cause.javaClass.simpleName}:${cause.message.orEmpty().take(180)}"
    }
    return buildString {
        append(error.errorCodeName)
        val msg = error.message.orEmpty().trim()
        if (msg.isNotBlank()) {
            append(" | ")
            append(msg.take(220))
        }
        append(" | cause=")
        append(causeInfo)
    }
}

private fun formatEpgTime(epochMs: Long): String {
    return runCatching {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.timeZone = PLAYER_EPG_TIME_ZONE
        formatter.format(Date(epochMs))
    }.getOrDefault("--:--")
}

private fun formatPlayerLocalTime(epochMs: Long): String {
    return runCatching {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.timeZone = PLAYER_EPG_TIME_ZONE
        formatter.format(Date(epochMs))
    }.getOrDefault("--:--")
}

private val PLAYER_EPG_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Oral")
