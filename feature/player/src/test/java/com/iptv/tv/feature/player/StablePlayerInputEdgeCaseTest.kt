package com.iptv.tv.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StablePlayerInputEdgeCaseTest {

    @Test
    fun `zero scroll does not dispatch an action`() {
        assertEquals(StableRemoteAction.NONE, stableScrollAction(0f, 0f))
    }

    @Test
    fun `dominant scroll axis determines the action`() {
        assertEquals(
            StableRemoteAction.PREVIOUS_CHANNEL,
            stableScrollAction(horizontal = 2f, vertical = 1f)
        )
        assertEquals(
            StableRemoteAction.VOLUME_DOWN,
            stableScrollAction(horizontal = 1f, vertical = -2f)
        )
    }

    @Test
    fun `direction keys are ignored outside fullscreen`() {
        assertEquals(
            StableRemoteAction.NONE,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_UP, fullscreen = false)
        )
        assertEquals(
            StableRemoteAction.NONE,
            stableRemoteActionForKey(KeyEvent.KEYCODE_DPAD_RIGHT, fullscreen = false)
        )
    }

    @Test
    fun `headset media button toggles playback`() {
        assertEquals(
            StableRemoteAction.TOGGLE_PLAYBACK,
            stableRemoteActionForKey(KeyEvent.KEYCODE_HEADSETHOOK)
        )
    }

    @Test
    fun `unknown key is not consumed`() {
        assertEquals(
            StableRemoteAction.NONE,
            stableRemoteActionForKey(KeyEvent.KEYCODE_UNKNOWN, fullscreen = true)
        )
    }

    @Test
    fun `channel volume mute and playback actions reveal controls`() {
        assertTrue(stableActionRevealsControls(StableRemoteAction.NEXT_CHANNEL))
        assertTrue(stableActionRevealsControls(StableRemoteAction.PREVIOUS_CHANNEL))
        assertTrue(stableActionRevealsControls(StableRemoteAction.VOLUME_UP))
        assertTrue(stableActionRevealsControls(StableRemoteAction.VOLUME_DOWN))
        assertTrue(stableActionRevealsControls(StableRemoteAction.TOGGLE_MUTE))
        assertTrue(stableActionRevealsControls(StableRemoteAction.TOGGLE_PLAYBACK))
    }

    @Test
    fun `visibility and fullscreen actions keep their dedicated behavior`() {
        assertFalse(stableActionRevealsControls(StableRemoteAction.TOGGLE_CONTROLS))
        assertFalse(stableActionRevealsControls(StableRemoteAction.TOGGLE_FULLSCREEN))
        assertFalse(stableActionRevealsControls(StableRemoteAction.NONE))
    }

    @Test
    fun `rapid cross-channel zaps are bounded by one cooldown`() {
        val throttle = StableChannelZapThrottle(cooldownMillis = 350L)

        assertTrue(throttle.shouldDispatch(StableRemoteAction.NEXT_CHANNEL, nowMillis = 1_000L))
        assertFalse(throttle.shouldDispatch(StableRemoteAction.PREVIOUS_CHANNEL, nowMillis = 1_150L))
        assertFalse(throttle.shouldDispatch(StableRemoteAction.NEXT_CHANNEL, nowMillis = 1_349L))
        assertTrue(throttle.shouldDispatch(StableRemoteAction.PREVIOUS_CHANNEL, nowMillis = 1_350L))
    }

    @Test
    fun `channel zap throttle never delays non-channel input`() {
        val throttle = StableChannelZapThrottle(cooldownMillis = 350L)

        assertTrue(throttle.shouldDispatch(StableRemoteAction.NEXT_CHANNEL, nowMillis = 2_000L))
        assertTrue(throttle.shouldDispatch(StableRemoteAction.VOLUME_UP, nowMillis = 2_050L))
        assertTrue(throttle.shouldDispatch(StableRemoteAction.TOGGLE_PLAYBACK, nowMillis = 2_050L))
        assertTrue(throttle.shouldDispatch(StableRemoteAction.TOGGLE_CONTROLS, nowMillis = 2_050L))
    }

    @Test
    fun `clock rollback resets channel zap cooldown instead of suppressing input`() {
        val throttle = StableChannelZapThrottle(cooldownMillis = 350L)

        assertTrue(throttle.shouldDispatch(StableRemoteAction.NEXT_CHANNEL, nowMillis = 5_000L))
        assertTrue(throttle.shouldDispatch(StableRemoteAction.NEXT_CHANNEL, nowMillis = 4_000L))
        assertFalse(throttle.shouldDispatch(StableRemoteAction.NEXT_CHANNEL, nowMillis = 4_100L))
    }
}
