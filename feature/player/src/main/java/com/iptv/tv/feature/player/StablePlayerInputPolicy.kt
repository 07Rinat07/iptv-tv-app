package com.iptv.tv.feature.player

import kotlin.math.abs

internal const val STABLE_CHANNEL_ZAP_COOLDOWN_MS = 350L

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
 * Bounds remote/channel-key zapping without delaying an explicit channel-list click.
 *
 * The input handler is the only caller: browser selection bypasses this gate and remains immediate.
 * A monotonic clock rollback is treated as a new epoch so one bad sample cannot suppress input.
 */
internal class StableChannelZapThrottle(
    private val cooldownMillis: Long = STABLE_CHANNEL_ZAP_COOLDOWN_MS
) {
    private var lastChannelActionAtMillis: Long? = null

    init {
        require(cooldownMillis >= 0L) { "cooldownMillis must be non-negative" }
    }

    fun shouldDispatch(action: StableRemoteAction, nowMillis: Long): Boolean {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
        if (
            action != StableRemoteAction.NEXT_CHANNEL &&
            action != StableRemoteAction.PREVIOUS_CHANNEL
        ) {
            return true
        }

        val previous = lastChannelActionAtMillis
        if (
            previous != null &&
            nowMillis >= previous &&
            nowMillis - previous < cooldownMillis
        ) {
            return false
        }

        lastChannelActionAtMillis = nowMillis
        return true
    }
}

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
