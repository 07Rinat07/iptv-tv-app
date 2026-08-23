package com.iptv.tv.feature.player

import kotlin.math.abs

internal data class StablePlayerInputCallbacks(
    val onToggleControls: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onPreviousChannel: () -> Unit,
    val onNextChannel: () -> Unit,
    val onVolumeUp: () -> Unit,
    val onVolumeDown: () -> Unit,
    val onToggleMute: () -> Unit,
    val onTogglePlayback: () -> Unit
)

/**
 * Stateless policy used by the production [StablePlayerInputHandler].
 * Android View/gesture lifecycle stays in the handler; deterministic input decisions live here.
 */
internal object StablePlayerInputPolicy {
    fun scrollAction(
        horizontal: Float,
        vertical: Float
    ): StableRemoteAction = when {
        abs(horizontal) > abs(vertical) && horizontal > 0f -> StableRemoteAction.PREVIOUS_CHANNEL
        abs(horizontal) > abs(vertical) && horizontal < 0f -> StableRemoteAction.NEXT_CHANNEL
        vertical > 0f -> StableRemoteAction.VOLUME_UP
        vertical < 0f -> StableRemoteAction.VOLUME_DOWN
        else -> StableRemoteAction.NONE
    }

    fun revealsControls(action: StableRemoteAction): Boolean = when (action) {
        StableRemoteAction.NEXT_CHANNEL,
        StableRemoteAction.PREVIOUS_CHANNEL,
        StableRemoteAction.VOLUME_UP,
        StableRemoteAction.VOLUME_DOWN,
        StableRemoteAction.TOGGLE_MUTE,
        StableRemoteAction.TOGGLE_PLAYBACK -> true

        StableRemoteAction.TOGGLE_CONTROLS,
        StableRemoteAction.TOGGLE_FULLSCREEN,
        StableRemoteAction.NONE -> false
    }
}

internal fun stableScrollAction(
    horizontal: Float,
    vertical: Float
): StableRemoteAction = StablePlayerInputPolicy.scrollAction(horizontal, vertical)

internal fun stableActionRevealsControls(action: StableRemoteAction): Boolean =
    StablePlayerInputPolicy.revealsControls(action)
