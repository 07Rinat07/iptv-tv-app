package com.iptv.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram

private const val MEDIUM_PLAYER_MIN_WIDTH_DP = 760f
private const val MEDIUM_PLAYER_MIN_HEIGHT_DP = 500f
private const val COMPACT_CENTER_MAX_WIDTH_DP = 540

internal enum class StableResponsivePlayerLayout {
    MEDIUM,
    COMPACT
}

internal fun stableResponsivePlayerLayout(widthDp: Float, heightDp: Float): StableResponsivePlayerLayout =
    if (widthDp >= MEDIUM_PLAYER_MIN_WIDTH_DP && heightDp >= MEDIUM_PLAYER_MIN_HEIGHT_DP) {
        StableResponsivePlayerLayout.MEDIUM
    } else {
        StableResponsivePlayerLayout.COMPACT
    }

private data class CompactPlayerAction(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit
)

@Composable
@UnstableApi
internal fun StableResponsiveCompactPlayer(
    modifier: Modifier = Modifier,
    compactHeight: Boolean,
    session: InternalPlaybackSession?,
    selectedChannel: Channel?,
    programs: List<EpgProgram>,
    channels: List<Channel>,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    scale: PlayerVideoScale,
    volume: Float,
    isStartingPlayback: Boolean,
    favoritesOnly: Boolean,
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
    onOpenChannels: () -> Unit,
    onBack: (() -> Unit)?,
    onLive: () -> Unit,
    onPlaylists: () -> Unit,
    onGroups: () -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit
) {
    BoxWithConstraints(modifier = modifier) {
        val availableHeightDp = maxHeight.value
        val layout = stableResponsivePlayerLayout(maxWidth.value, availableHeightDp)

        if (layout == StableResponsivePlayerLayout.MEDIUM) {
            Row(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StablePlayerRailReplacement(
                    modifier = Modifier
                        .width(if (compactHeight) 132.dp else 144.dp)
                        .fillMaxHeight(),
                    favoritesOnly = favoritesOnly,
                    onBack = onBack,
                    onLive = onLive,
                    onPlaylists = onPlaylists,
                    onGroups = onGroups,
                    onFavorites = onFavorites,
                    onSettings = onSettings
                )
                StableCenterPaneReplacement(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    compact = true,
                    session = session,
                    selectedChannel = selectedChannel,
                    programs = programs,
                    channels = channels,
                    favoriteIds = favoriteIds,
                    epgByChannel = epgByChannel,
                    scale = scale,
                    volume = volume,
                    isStartingPlayback = isStartingPlayback,
                    videoSurfaceContent = videoSurfaceContent,
                    onVolumeChange = onVolumeChange,
                    onToggleMute = onToggleMute,
                    onReady = onReady,
                    onP2pBoundaryTelemetry = onP2pBoundaryTelemetry,
                    onError = onError,
                    onPlaySelected = onPlaySelected,
                    onToggleFullscreen = onToggleFullscreen,
                    onPreviousChannel = onPreviousChannel,
                    onNextChannel = onNextChannel,
                    onSelectChannel = onSelectChannel,
                    onToggleFavorite = onToggleFavorite,
                    onOpenChannels = onOpenChannels
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StableCompactPlayerHeader(
                    selectedChannel = selectedChannel,
                    favoritesOnly = favoritesOnly,
                    onBack = onBack,
                    onLive = onLive,
                    onPlaylists = onPlaylists,
                    onGroups = onGroups,
                    onFavorites = onFavorites,
                    onOpenChannels = onOpenChannels,
                    onSettings = onSettings
                )

                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.TopCenter
                ) {
                    StableCenterPaneReplacement(
                        modifier = Modifier
                            .fillMaxHeight()
                            .then(
                                if (availableHeightDp < MEDIUM_PLAYER_MIN_HEIGHT_DP) {
                                    Modifier.widthIn(max = COMPACT_CENTER_MAX_WIDTH_DP.dp)
                                } else {
                                    Modifier.fillMaxWidth()
                                }
                            ),
                        compact = true,
                        session = session,
                        selectedChannel = selectedChannel,
                        programs = programs,
                        channels = channels,
                        favoriteIds = favoriteIds,
                        epgByChannel = epgByChannel,
                        scale = scale,
                        volume = volume,
                        isStartingPlayback = isStartingPlayback,
                        videoSurfaceContent = videoSurfaceContent,
                        onVolumeChange = onVolumeChange,
                        onToggleMute = onToggleMute,
                        onReady = onReady,
                        onP2pBoundaryTelemetry = onP2pBoundaryTelemetry,
                        onError = onError,
                        onPlaySelected = onPlaySelected,
                        onToggleFullscreen = onToggleFullscreen,
                        onPreviousChannel = onPreviousChannel,
                        onNextChannel = onNextChannel,
                        onSelectChannel = onSelectChannel,
                        onToggleFavorite = onToggleFavorite,
                        onOpenChannels = onOpenChannels
                    )
                }
            }
        }
    }
}

@Composable
private fun StableCompactPlayerHeader(
    selectedChannel: Channel?,
    favoritesOnly: Boolean,
    onBack: (() -> Unit)?,
    onLive: () -> Unit,
    onPlaylists: () -> Unit,
    onGroups: () -> Unit,
    onFavorites: () -> Unit,
    onOpenChannels: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
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
                    selectedChannel?.name ?: "Rinat IPTV",
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(
                    onClick = onOpenChannels,
                    modifier = Modifier.tvFocusOutline()
                ) { Text("Каналы") }
            }

            val actions = buildList {
                add(CompactPlayerAction("Эфир", onClick = onLive))
                add(CompactPlayerAction("Плейлисты", onClick = onPlaylists))
                add(CompactPlayerAction("Группы", onClick = onGroups))
                add(CompactPlayerAction(if (favoritesOnly) "★ Избранное" else "☆ Избранное", favoritesOnly, onFavorites))
                add(CompactPlayerAction("Настройки", onClick = onSettings))
                onBack?.let { add(CompactPlayerAction("Назад", onClick = it)) }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(actions) { action ->
                    if (action.selected) {
                        Button(
                            onClick = action.onClick,
                            modifier = Modifier.tvFocusOutline()
                        ) { Text(action.label, maxLines = 1) }
                    } else {
                        OutlinedButton(
                            onClick = action.onClick,
                            modifier = Modifier.tvFocusOutline()
                        ) { Text(action.label, maxLines = 1) }
                    }
                }
            }
        }
    }
}
