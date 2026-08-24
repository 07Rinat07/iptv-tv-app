package com.iptv.tv.feature.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.media3.common.util.UnstableApi

internal data class StablePlayerVideoSurfaceRequest(
    val session: InternalPlaybackSession,
    val scale: PlayerVideoScale,
    val expanded: Boolean,
    val volume: Float,
    val showControls: Boolean,
    val onToggleControls: () -> Unit,
    val onVolumeUp: () -> Unit,
    val onVolumeDown: () -> Unit,
    val onToggleMute: () -> Unit,
    val onReady: () -> Unit,
    val onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit,
    val onError: (String) -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onPreviousChannel: () -> Unit,
    val onNextChannel: () -> Unit,
    val modifier: Modifier
)

internal typealias StablePlayerVideoSurfaceContent =
    @Composable (StablePlayerVideoSurfaceRequest) -> Unit

/**
 * Creates one movable production video subtree for the lifetime of [StablePlayerScreen].
 * Dashboard/fullscreen transitions relocate this subtree instead of disposing and recreating
 * the Media3/LibVLC surface. Session/backend keys inside [StableVideoSurface] remain authoritative
 * when the selected playback session itself changes.
 */
@Composable
@UnstableApi
internal fun rememberStablePlayerVideoSurfaceContent(): StablePlayerVideoSurfaceContent =
    remember {
        movableContentOf { request: StablePlayerVideoSurfaceRequest ->
            StableVideoSurface(
                session = request.session,
                scale = request.scale,
                expanded = request.expanded,
                volume = request.volume,
                showControls = request.showControls,
                onToggleControls = request.onToggleControls,
                onVolumeUp = request.onVolumeUp,
                onVolumeDown = request.onVolumeDown,
                onToggleMute = request.onToggleMute,
                onReady = request.onReady,
                onP2pBoundaryTelemetry = request.onP2pBoundaryTelemetry,
                onError = request.onError,
                onToggleFullscreen = request.onToggleFullscreen,
                onPreviousChannel = request.onPreviousChannel,
                onNextChannel = request.onNextChannel,
                modifier = request.modifier
            )
        }
    }
