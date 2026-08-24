package com.iptv.tv.feature.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StablePlayerShellLayoutGuardTest {
    @Test
    fun wideLayoutRequiresBothProductionThresholds() {
        assertTrue(stableUseWidePlayerLayout(1080f, 560f))
        assertFalse(stableUseWidePlayerLayout(1079.99f, 560f))
        assertFalse(stableUseWidePlayerLayout(1080f, 559.99f))
    }

    @Test
    fun compactControlsStopAt760Dp() {
        assertTrue(stableUseCompactPlayerControls(759.99f))
        assertFalse(stableUseCompactPlayerControls(760f))
    }
}
