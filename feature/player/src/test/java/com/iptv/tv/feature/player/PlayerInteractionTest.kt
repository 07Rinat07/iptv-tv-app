package com.iptv.tv.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerInteractionTest {

    @Test
    fun channelKeys_workInAnyPlayerMode() {
        assertEquals(
            PlayerRemoteAction.PREVIOUS_CHANNEL,
            playerRemoteActionForKeyCode(KeyEvent.KEYCODE_CHANNEL_UP, fullscreen = false)
        )
        assertEquals(
            PlayerRemoteAction.NEXT_CHANNEL,
            playerRemoteActionForKeyCode(KeyEvent.KEYCODE_CHANNEL_DOWN, fullscreen = false)
        )
    }

    @Test
    fun dpadChangesChannelsOnlyInFullscreen() {
        assertNull(playerRemoteActionForKeyCode(KeyEvent.KEYCODE_DPAD_UP, fullscreen = false))
        assertEquals(
            PlayerRemoteAction.PREVIOUS_CHANNEL,
            playerRemoteActionForKeyCode(KeyEvent.KEYCODE_DPAD_UP, fullscreen = true)
        )
    }

    @Test
    fun menuAndGuideKeepChannelListAction() {
        assertEquals(
            PlayerRemoteAction.SHOW_CHANNELS,
            playerRemoteActionForKeyCode(KeyEvent.KEYCODE_MENU, fullscreen = false)
        )
        assertEquals(
            PlayerRemoteAction.SHOW_CHANNELS,
            playerRemoteActionForKeyCode(KeyEvent.KEYCODE_GUIDE, fullscreen = true)
        )
    }

    @Test
    fun adjacentChannel_wrapsAtBothEnds() {
        val ids = listOf(10L, 20L, 30L)

        assertEquals(10L, adjacentChannelId(ids, selectedChannelId = 30L, step = 1))
        assertEquals(30L, adjacentChannelId(ids, selectedChannelId = 10L, step = -1))
    }

    @Test
    fun adjacentChannel_usesFirstWhenSelectionIsMissing() {
        assertEquals(10L, adjacentChannelId(listOf(10L, 20L), selectedChannelId = 99L, step = 1))
    }
}
