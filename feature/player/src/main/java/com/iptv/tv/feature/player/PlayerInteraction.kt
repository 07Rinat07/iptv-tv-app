package com.iptv.tv.feature.player

import android.view.KeyEvent

enum class PlayerRemoteAction {
    PREVIOUS_CHANNEL,
    NEXT_CHANNEL,
    TOGGLE_FULLSCREEN,
    SHOW_CHANNELS
}

/**
 * Преобразует клавиши пульта/клавиатуры в действия плеера.
 *
 * Стрелки вверх/вниз переключают каналы только в полноэкранном режиме, чтобы в обычном
 * интерфейсе не ломать стандартное перемещение фокуса по кнопкам и спискам.
 */
fun playerRemoteActionForKeyCode(
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

fun adjacentChannelId(
    channelIds: List<Long>,
    selectedChannelId: Long?,
    step: Int
): Long? {
    if (channelIds.isEmpty()) return null
    val normalizedStep = if (step < 0) -1 else 1
    val selectedIndex = channelIds.indexOf(selectedChannelId)
    if (selectedIndex < 0) {
        return if (normalizedStep < 0) channelIds.last() else channelIds.first()
    }
    val target = (selectedIndex + normalizedStep + channelIds.size) % channelIds.size
    return channelIds[target]
}
