package com.iptv.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import kotlinx.coroutines.delay

private const val DASHBOARD_CONTROLS_HIDE_DELAY_MS = 5_000L

/**
 * Wide TV-first Player presentation aligned with the repository README preview.
 *
 * The supplied [videoSurfaceContent] is the same movable production subtree used by fullscreen,
 * so this layout changes presentation only. It does not create a second Media3/LibVLC/P2P runtime.
 */
@Composable
@UnstableApi
internal fun StablePlayerReadmeDashboard(
    session: InternalPlaybackSession?,
    selectedChannel: Channel?,
    programs: List<EpgProgram>,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    query: String,
    favoritesOnly: Boolean,
    scale: PlayerVideoScale,
    volume: Float,
    isStartingPlayback: Boolean,
    videoSurfaceContent: StablePlayerVideoSurfaceContent,
    onBack: (() -> Unit)?,
    onLive: () -> Unit,
    onPlaylists: () -> Unit,
    onGroups: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
    onQueryChange: (String) -> Unit,
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
    onToggleFavorite: (Long) -> Unit
) {
    var controlsVisible by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(controlsVisible, session?.sessionId) {
        if (!controlsVisible || session == null) return@LaunchedEffect
        delay(DASHBOARD_CONTROLS_HIDE_DELAY_MS)
        controlsVisible = false
    }

    Row(
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StableReadmePlayerRail(
            modifier = Modifier
                .width(182.dp)
                .fillMaxHeight(),
            favoritesOnly = favoritesOnly,
            onBack = onBack,
            onLive = onLive,
            onPlaylists = onPlaylists,
            onGroups = onGroups,
            onFavorites = onFavorites,
            onSettings = onSettings
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            StableReadmePlayerHeader(
                channel = selectedChannel,
                programs = programs,
                isFavorite = selectedChannel?.id?.let { it in favoriteIds } == true,
                onToggleFavorite = {
                    selectedChannel?.id?.let(onToggleFavorite)
                }
            )

            StableReadmeVideoPane(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                session = session,
                selectedChannel = selectedChannel,
                scale = scale,
                volume = volume,
                controlsVisible = controlsVisible,
                isStartingPlayback = isStartingPlayback,
                videoSurfaceContent = videoSurfaceContent,
                onToggleControls = { controlsVisible = !controlsVisible },
                onVolumeChange = onVolumeChange,
                onToggleMute = onToggleMute,
                onReady = onReady,
                onP2pBoundaryTelemetry = onP2pBoundaryTelemetry,
                onError = onError,
                onPlaySelected = onPlaySelected,
                onToggleFullscreen = onToggleFullscreen,
                onPreviousChannel = onPreviousChannel,
                onNextChannel = onNextChannel
            )

            StableReadmeTransportBar(
                volume = volume,
                onVolumeChange = onVolumeChange,
                onToggleMute = onToggleMute,
                onPreviousChannel = onPreviousChannel,
                onNextChannel = onNextChannel,
                onToggleFullscreen = onToggleFullscreen
            )

            StableNearbyChannelsReplacement(
                channels = channels,
                epgByChannel = epgByChannel,
                onSelectChannel = onSelectChannel,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 112.dp, max = 154.dp)
            )
        }

        StableChannelBrowserReplacement(
            modifier = Modifier
                .width(326.dp)
                .fillMaxHeight(),
            query = query,
            onQueryChange = onQueryChange,
            channels = channels,
            selectedChannelId = selectedChannel?.id,
            favoriteIds = favoriteIds,
            epgByChannel = epgByChannel,
            onSelect = onSelectChannel,
            onToggleFavorite = onToggleFavorite,
            onOpenGroups = onGroups
        )
    }
}

@Composable
private fun StableReadmePlayerRail(
    modifier: Modifier,
    favoritesOnly: Boolean,
    onBack: (() -> Unit)?,
    onLive: () -> Unit,
    onPlaylists: () -> Unit,
    onGroups: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit
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
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "R",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                Text(
                    text = "Rinat IPTV",
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
                    text = "Эфир",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            ReadmeRailButton("LIVE TV", onLive)
            ReadmeRailButton("Плейлисты", onPlaylists)
            ReadmeRailButton("Группы", onGroups)
            if (favoritesOnly) {
                Button(
                    onClick = onFavorites,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tvFocusOutline()
                ) {
                    Text("★ Избранное")
                }
            } else {
                ReadmeRailButton("☆ Избранное", onFavorites)
            }
            ReadmeRailButton("Настройки", onSettings)

            Spacer(modifier = Modifier.weight(1f))
            onBack?.let { action ->
                ReadmeRailButton("Назад", action)
            }
            Text(
                text = "Media3 • LibVLC",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "IPTV • P2P",
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ReadmeRailButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusOutline()
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StableReadmePlayerHeader(
    channel: Channel?,
    programs: List<EpgProgram>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val current = stableCurrentProgram(programs, nowMs)
    val next = stableNextProgram(programs, current, nowMs)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "LIVE",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = channel?.name ?: "Выберите канал",
                    color = MaterialTheme.colorScheme.onBackground,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = current?.let { "Сейчас ${stableRange(it)} · ${it.title}" }
                    ?: "Сейчас: программа не найдена",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            next?.let { program ->
                Text(
                    text = "Далее ${stableRange(program)} · ${program.title}",
                    color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        OutlinedButton(
            onClick = onToggleFavorite,
            enabled = channel != null,
            modifier = Modifier.tvFocusOutline()
        ) {
            Text(if (isFavorite) "★" else "☆")
        }
    }
}

@Composable
@UnstableApi
private fun StableReadmeVideoPane(
    modifier: Modifier,
    session: InternalPlaybackSession?,
    selectedChannel: Channel?,
    scale: PlayerVideoScale,
    volume: Float,
    controlsVisible: Boolean,
    isStartingPlayback: Boolean,
    videoSurfaceContent: StablePlayerVideoSurfaceContent,
    onToggleControls: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onReady: (Long?) -> Unit,
    onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit,
    onError: (Long?, String) -> Unit,
    onPlaySelected: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit
) {
    Surface(
        modifier = modifier.tvFocusOutline(),
        shape = MaterialTheme.shapes.large,
        color = Color.Black,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (session != null) {
                videoSurfaceContent(
                    StablePlayerVideoSurfaceRequest(
                        session = session,
                        scale = scale,
                        expanded = false,
                        volume = volume,
                        showControls = controlsVisible,
                        onToggleControls = onToggleControls,
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
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = selectedChannel?.name ?: "Выберите канал",
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Button(
                        onClick = onPlaySelected,
                        enabled = selectedChannel != null && !isStartingPlayback,
                        modifier = Modifier.tvFocusOutline()
                    ) {
                        Text(if (isStartingPlayback) "Подключение…" else "Смотреть")
                    }
                }
            }

            if (controlsVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                    shape = MaterialTheme.shapes.small,
                    color = Color.Black.copy(alpha = 0.72f)
                ) {
                    OutlinedButton(
                        onClick = onToggleFullscreen,
                        modifier = Modifier.tvFocusOutline()
                    ) {
                        Text("Полный экран")
                    }
                }
            }
        }
    }
}

@Composable
private fun StableReadmeTransportBar(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onPreviousChannel: () -> Unit,
    onNextChannel: () -> Unit,
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
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onPreviousChannel, modifier = Modifier.tvFocusOutline()) {
                Text("◀")
            }
            OutlinedButton(onClick = onNextChannel, modifier = Modifier.tvFocusOutline()) {
                Text("▶")
            }
            OutlinedButton(onClick = onToggleMute, modifier = Modifier.tvFocusOutline()) {
                Text(if (volume <= 0f) "🔇" else "🔊")
            }
            OutlinedButton(
                onClick = { onVolumeChange(volume - VOLUME_STEP) },
                modifier = Modifier.tvFocusOutline()
            ) {
                Text("−")
            }
            Text(
                text = "${(volume * 100).toInt()}%",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = { onVolumeChange(volume + VOLUME_STEP) },
                modifier = Modifier.tvFocusOutline()
            ) {
                Text("+")
            }
            Spacer(modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onToggleFullscreen, modifier = Modifier.tvFocusOutline()) {
                Text("⛶")
            }
        }
    }
}
