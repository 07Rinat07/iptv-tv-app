package com.iptv.tv.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class StableTvListNavigationTest {

    @Test
    fun `page and edge keys map to list actions`() {
        assertEquals(
            StableListRemoteAction.FIRST,
            stableListRemoteActionForKey(KeyEvent.KEYCODE_MOVE_HOME)
        )
        assertEquals(
            StableListRemoteAction.PAGE_UP,
            stableListRemoteActionForKey(KeyEvent.KEYCODE_PAGE_UP)
        )
        assertEquals(
            StableListRemoteAction.PAGE_DOWN,
            stableListRemoteActionForKey(KeyEvent.KEYCODE_PAGE_DOWN)
        )
        assertEquals(
            StableListRemoteAction.LAST,
            stableListRemoteActionForKey(KeyEvent.KEYCODE_MOVE_END)
        )
    }

    @Test
    fun `ordinary dpad keys remain available for focus traversal`() {
        assertEquals(
            StableListRemoteAction.NONE,
            stableListRemoteActionForKey(KeyEvent.KEYCODE_DPAD_UP)
        )
        assertEquals(
            StableListRemoteAction.NONE,
            stableListRemoteActionForKey(KeyEvent.KEYCODE_DPAD_DOWN)
        )
        assertEquals(
            StableListRemoteAction.NONE,
            stableListRemoteActionForKey(KeyEvent.KEYCODE_DPAD_CENTER)
        )
    }
}
