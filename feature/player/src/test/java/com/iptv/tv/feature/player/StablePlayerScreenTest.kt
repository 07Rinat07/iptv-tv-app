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
    fun `remote center and enter toggle fullscreen`() {
        assertEquals(
            StableRemoteAction.TOGGLE_FULLSCREEN,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_CENTER)
        )
        assertEquals(
            StableRemoteAction.TOGGLE_FULLSCREEN,
            stableRemoteActionForKey(KeyEvent.KEYCODE_ENTER)
        )
    }

    @Test
    fun `remote channel keys change channel`() {
        assertEquals(
            StableRemoteAction.NEXT_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_CHANNEL_UP)
        )
        assertEquals(
            StableRemoteAction.PREVIOUS_CHANNEL,
            stableRemoteActionForKey(KeyEvent.KEYCODE_CHANNEL_DOWN)
        )
    }

    @Test
    fun `adjacent channel wraps in both directions`() {
        val ids = listOf(10L, 20L, 30L)

        assertEquals(20L, stableAdjacentChannelId(ids, 10L, 1))
        assertEquals(30L, stableAdjacentChannelId(ids, 10L, -1))
        assertEquals(10L, stableAdjacentChannelId(ids, 30L, 1))
        assertNull(stableAdjacentChannelId(emptyList(), null, 1))
    }

    private fun program(title: String, start: Long, end: Long): EpgProgram = EpgProgram(
        title = title,
        description = null,
        category = null,
        startEpochMs = start,
        endEpochMs = end
    )
}
