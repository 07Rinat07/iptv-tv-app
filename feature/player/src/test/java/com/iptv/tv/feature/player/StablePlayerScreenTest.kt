package com.iptv.tv.feature.player

import android.view.KeyEvent
import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StablePlayerScreenTest {

    @Test
    fun `current and next EPG are selected by epoch time`() {
        val first = program("Новости", 1_000L, 2_000L)
        val second = program("Фильм", 2_000L, 3_000L)
        val third = program("Спорт", 3_000L, 4_000L)
        val programs = listOf(first, second, third)

        val current = stableCurrentProgram(programs, 2_500L)
        val next = stableNextProgram(programs, current, 2_500L)

        assertEquals(second, current)
        assertEquals(third, next)
    }

    @Test
    fun `EPG is empty when source has no matching program`() {
        assertNull(stableCurrentProgram(emptyList(), 5_000L))
        assertNull(stableNextProgram(emptyList(), null, 5_000L))
    }

    @Test
    fun `remote center and enter toggle controls`() {
        assertEquals(
            StableRemoteAction.TOGGLE_CONTROLS,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_CENTER)
        )
        assertEquals(
            StableRemoteAction.TOGGLE_CONTROLS,
            stableRemoteActionForKey(KeyEvent.KEYCODE_ENTER)
        )
    }

    @Test
    fun `menu and guide toggle production controls`() {
        assertEquals(
            StableRemoteAction.TOGGLE_CONTROLS,
            stableRemoteActionForKey(KeyEvent.KEYCODE_MENU)
        )
        assertEquals(
            StableRemoteAction.TOGGLE_CONTROLS,
            stableRemoteActionForKey(KeyEvent.KEYCODE_GUIDE, fullscreen = true)
        )
    }

    @Test
    fun `keyboard F toggles fullscreen`() {
        assertEquals(
            StableRemoteAction.TOGGLE_FULLSCREEN,
            stableRemoteActionForKey(KeyEvent.KEYCODE_F)
        )
    }

    @Test
    fun `fullscreen arrows control channels and volume`() {
        assertEquals(
            StableRemoteAction.PREVIOUS_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_LEFT, fullscreen = true)
        )
        assertEquals(
            StableRemoteAction.NEXT_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_RIGHT, fullscreen = true)
        )
        assertEquals(
            StableRemoteAction.VOLUME_UP,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_UP, fullscreen = true)
        )
        assertEquals(
            StableRemoteAction.VOLUME_DOWN,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_DOWN, fullscreen = true)
        )
        assertEquals(
            StableRemoteAction.NONE,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_LEFT, fullscreen = false)
        )
    }

    @Test
    fun `remote channel and media keys change channel`() {
        assertEquals(
            StableRemoteAction.NEXT_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_CHANNEL_UP)
        )
        assertEquals(
            StableRemoteAction.PREVIOUS_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_CHANNEL_DOWN)
        )
        assertEquals(
            StableRemoteAction.NEXT_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        )
        assertEquals(
            StableRemoteAction.PREVIOUS_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        )
        assertEquals(
            StableRemoteAction.TOGGLE_PLAYBACK,
            stableRemoteActionForKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        )
    }

    @Test
    fun `remote volume keys control player volume`() {
        assertEquals(
            StableRemoteAction.VOLUME_UP,
            stableRemoteActionForKey(KeyEvent.KEYCODE_VOLUME_UP)
        )
        assertEquals(
            StableRemoteAction.VOLUME_DOWN,
            stableRemoteActionForKey(KeyEvent.KEYCODE_VOLUME_DOWN)
        )
        assertEquals(
            StableRemoteAction.TOGGLE_MUTE,
            stableRemoteActionForKey(KeyEvent.KEYCODE_VOLUME_MUTE)
        )
    }

    @Test
    fun `mouse and touchpad scroll maps to channels and volume`() {
        assertEquals(StableRemoteAction.PREVIOUS_CHANNEL, stableScrollAction(1f, 0f))
        assertEquals(StableRemoteAction.NEXT_CHANNEL, stableScrollAction(-1f, 0f))
        assertEquals(StableRemoteAction.VOLUME_UP, stableScrollAction(0f, 1f))
        assertEquals(StableRemoteAction.VOLUME_DOWN, stableScrollAction(0f, -1f))
    }

    @Test
    fun `adjacent channel wraps in both directions`() {
        val ids = listOf(10L, 20L, 30L)

        assertEquals(20L, stableAdjacentChannelId(ids, 10L, 1))
        assertEquals(30L, stableAdjacentChannelId(ids, 10L, -1))
        assertEquals(10L, stableAdjacentChannelId(ids, 30L, 1))
        assertNull(stableAdjacentChannelId(emptyList(), null, 1))
    }

    @Test
    fun `720p TV layout uses compact controls without leaving the screen`() {
        assertEquals(true, stableUseWidePlayerLayout(widthDp = 1280f, heightDp = 720f))
        assertEquals(true, stableUseCompactPlayerControls(heightDp = 720f))
    }

    @Test
    fun `small screens avoid three-column player layout`() {
        assertEquals(false, stableUseWidePlayerLayout(widthDp = 960f, heightDp = 540f))
        assertEquals(true, stableUseCompactPlayerControls(heightDp = 540f))
    }

    @Test
    fun `default local Ace endpoint is resolved through Android service`() {
        assertEquals(true, isDefaultLocalAceEndpoint("http://127.0.0.1:6878"))
        assertEquals(true, isDefaultLocalAceEndpoint("localhost:6878/"))
        assertEquals(false, isDefaultLocalAceEndpoint("http://192.168.1.50:6878"))
    }

    @Test
    fun `audio without a confirmed video size is not treated as a visible picture`() {
        assertEquals(false, stablePictureConfirmed(firstFrameRendered = true, videoWidth = 0, videoHeight = 0))
        assertEquals(false, stablePictureConfirmed(firstFrameRendered = false, videoWidth = 1920, videoHeight = 1080))
        assertEquals(true, stablePictureConfirmed(firstFrameRendered = true, videoWidth = 1920, videoHeight = 1080))
    }

    private fun program(title: String, start: Long, end: Long): EpgProgram = EpgProgram(
        title = title,
        description = null,
        category = null,
        startEpochMs = start,
        endEpochMs = end
    )
}
