package com.iptv.tv.feature.player

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram

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
internal fun StableFullscreenPlayerReplacement(
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
