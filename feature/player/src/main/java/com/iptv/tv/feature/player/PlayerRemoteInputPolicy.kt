package com.iptv.tv.feature.player

import android.view.KeyEvent

enum class PlayerRemoteAction {
    PREVIOUS_CHANNEL,
    NEXT_CHANNEL,
    TOGGLE_FULLSCREEN,
    SHOW_CHANNELS
}

/**
 * Maps remote-control/keyboard input to Player actions without owning UI state.
 *
 * DPAD up/down switch channels only in fullscreen mode so normal Player screens keep
 * their regular focus-navigation semantics.
 */
internal object PlayerRemoteInputPolicy {
    fun actionForKeyCode(
        keyCode: Int,
        fullscreen: Boolean
    ): PlayerRemoteAction? = when (keyCode) {
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlayerRemoteAction.PREVIOUS_CHANNEL

        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MEDIA_NEXT -> PlayerRemoteAction.NEXT_CHANNEL

        KeyEvent.KEYCODE_DPAD_UP -> if (fullscreen) PlayerRemoteAction.PREVIOUS_CHANNEL else null
        KeyEvent.KEYCODE_DPAD_DOWN -> if (fullscreen) PlayerRemoteAction.NEXT_CHANNEL else null

        KeyEvent.KEYCODE_F -> PlayerRemoteAction.TOGGLE_FULLSCREEN
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_GUIDE -> PlayerRemoteAction.SHOW_CHANNELS

        else -> null
    }
}

fun playerRemoteActionForKeyCode(
    keyCode: Int,
    fullscreen: Boolean
): PlayerRemoteAction? = PlayerRemoteInputPolicy.actionForKeyCode(
    keyCode = keyCode,
    fullscreen = fullscreen
)
