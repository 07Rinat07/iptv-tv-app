package com.iptv.tv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import kotlinx.coroutines.delay

private const val PLAYER_CONTROLS_AUTO_HIDE_MS = 5_000L

@Composable
internal fun StablePlayerRailReplacement(
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
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.padding(bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "R",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Text(
                    "Rinat",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    "Эфир",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight().focusGroup(),
                state = state,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                item { StableRailButton("LIVE TV", onLive) }
                item { StableRailButton("Плейлисты", onPlaylists) }
                item { StableRailButton("Группы", onGroups) }
                item {
                    StableRailButton(
                        label = if (favoritesOnly) "★ Избранное" else "☆ Избранное",
                        onClick = onFavorites,
                        selected = favoritesOnly
                    )
                }
                item { StableRailButton("Настройки", onSettings) }
                item { onBack?.let { StableRailButton("Назад", it) } }
            }
        }
    }
}

@Composable
private fun StableRailButton(
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().tvFocusOutline(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().tvFocusOutline(),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@UnstableApi
internal fun StableCenterPaneReplacement(
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
    videoSurfaceContent: StablePlayerVideoSurfaceContent,
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
    var programmeVisible by rememberSaveable(selectedChannel?.id) { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, session?.sessionId) {
        if (!controlsVisible || session == null) return@LaunchedEffect
        delay(PLAYER_CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    BoxWithConstraints(modifier = modifier) {
        val widePane = maxWidth >= 560.dp
        val nowMs = System.currentTimeMillis()
        val current = stableCurrentProgram(programs, nowMs)
        val next = stableNextProgram(programs, current, nowMs)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 9.dp)
        ) {
            if (widePane) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "LIVE",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelLarge
                            )
                            Text(
                                selectedChannel?.name ?: "Выберите канал",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            current?.let { "Сейчас ${stableRange(it)} · ${it.title}" }
                                ?: "Программа не найдена",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        next?.let {
                            Text(
                                "Далее ${stableRange(it)} · ${it.title}",
                                color = MaterialTheme.colorScheme.outline,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = { selectedChannel?.id?.let(onToggleFavorite) },
                        enabled = selectedChannel != null,
                        modifier = Modifier.tvFocusOutline(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(if (selectedChannel?.id?.let { it in favoriteIds } == true) "★" else "☆")
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = if (widePane) 250.dp else 180.dp)
                    .tvFocusOutline(),
                shape = MaterialTheme.shapes.large,
                color = Color.Black,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 0.dp
            ) {
                if (session != null) {
                    videoSurfaceContent(
                        StablePlayerVideoSurfaceRequest(
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
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Text(
                                selectedChannel?.name ?: "Выберите канал",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (isStartingPlayback) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(30.dp),
                                    strokeWidth = 3.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Подключение…",
                                    color = Color.White.copy(alpha = 0.88f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                                selectedChannel
                                    ?.takeIf { PlayerP2pDescriptor.detect(it.streamUrl) != null }
                                    ?.let { channel ->
                                        Text(
                                            p2pChannelAvailabilityLabel(
                                                P2pChannelAvailabilityUiCache.statuses[channel.id]
                                            ),
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                            } else if (selectedChannel != null) {
                                Button(
                                    onClick = onPlaySelected,
                                    modifier = Modifier.tvFocusOutline()
                                ) {
                                    Text("Смотреть")
                                }
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

            if (widePane) {
                StableDashboardTransportBar(
                    volume = volume,
                    onVolumeChange = onVolumeChange,
                    onToggleMute = onToggleMute,
                    onPrevious = onPreviousChannel,
                    onNext = onNextChannel,
                    onOpenProgramme = { programmeVisible = true },
                    onOpenChannels = onOpenChannels,
                    onToggleFullscreen = onToggleFullscreen
                )
                StableNearbyChannelsWithLogos(
                    channels = channels,
                    epgByChannel = epgByChannel,
                    onSelectChannel = onSelectChannel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 104.dp, max = if (compact) 132.dp else 158.dp)
                )
            } else {
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
                    onOpenProgramme = { programmeVisible = true },
                    onOpenChannels = onOpenChannels
                )
            }
        }
    }

    if (programmeVisible) {
        StableProgrammeDialog(
            channel = selectedChannel,
            programs = programs,
            onDismiss = { programmeVisible = false }
        )
    }
}

@Composable
private fun StableDashboardTransportBar(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenProgramme: () -> Unit,
    onOpenChannels: () -> Unit,
    onToggleFullscreen: () -> Unit
) {
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
                .padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = onPrevious, modifier = Modifier.tvFocusOutline()) { Text("◀") }
            OutlinedButton(onClick = onNext, modifier = Modifier.tvFocusOutline()) { Text("▶") }
            OutlinedButton(onClick = onToggleMute, modifier = Modifier.tvFocusOutline()) {
                Text(if (volume <= 0f) "🔇" else "🔊")
            }
            OutlinedButton(
                onClick = { onVolumeChange(volume - VOLUME_STEP) },
                modifier = Modifier.tvFocusOutline()
            ) { Text("−") }
            Text(
                "${(volume * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { onVolumeChange(volume + VOLUME_STEP) },
                modifier = Modifier.tvFocusOutline()
            ) { Text("+") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onOpenProgramme, modifier = Modifier.tvFocusOutline()) {
                Text("Программа")
            }
            OutlinedButton(onClick = onOpenChannels, modifier = Modifier.tvFocusOutline()) {
                Text("Каналы")
            }
            OutlinedButton(onClick = onToggleFullscreen, modifier = Modifier.tvFocusOutline()) {
                Text("⛶")
            }
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
    onOpenProgramme: () -> Unit,
    onOpenChannels: () -> Unit
) {
    val current = stableCurrentProgram(programs, System.currentTimeMillis())
    Surface(
        modifier = Modifier.fillMaxWidth().tvFocusOutline(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
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
                OutlinedButton(onClick = onPrevious, modifier = Modifier.weight(0.8f)) { Text("◀") }
                OutlinedButton(onClick = onOpenProgramme, modifier = Modifier.weight(1.5f)) {
                    Text("Программа")
                }
                OutlinedButton(onClick = onOpenChannels, modifier = Modifier.weight(1.3f)) {
                    Text("Каналы")
                }
                OutlinedButton(onClick = onNext, modifier = Modifier.weight(0.8f)) { Text("▶") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
@UnstableApi
internal fun StableFullscreenPlayerReplacement(
    session: InternalPlaybackSession?,
    channel: Channel?,
    programs: List<EpgProgram>,
    scale: PlayerVideoScale,
    volume: Float,
    showChannelBanner: Boolean,
    videoSurfaceContent: StablePlayerVideoSurfaceContent,
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
    var programmeVisible by rememberSaveable(channel?.id) { mutableStateOf(false) }

    LaunchedEffect(controlsVisible, session?.sessionId) {
        if (!controlsVisible || session == null) return@LaunchedEffect
        delay(PLAYER_CONTROLS_AUTO_HIDE_MS)
        controlsVisible = false
    }

    BackHandler(onBack = onToggleFullscreen)
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (session != null) {
            videoSurfaceContent(
                StablePlayerVideoSurfaceRequest(
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
                        val p2pLabel = p2pChannelOsdLabel(
                            streamUrl = channel?.streamUrl,
                            status = channel?.id?.let(P2pChannelAvailabilityUiCache.statuses::get)
                        )
                        if (p2pLabel != null) {
                            Text(
                                p2pLabel,
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    OutlinedButton(onClick = { programmeVisible = true }) { Text("Программа") }
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

    if (programmeVisible) {
        StableProgrammeDialog(
            channel = channel,
            programs = programs,
            onDismiss = { programmeVisible = false }
        )
    }
}
