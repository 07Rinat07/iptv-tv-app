package com.iptv.tv.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPreparationEpochTest {
    @Test
    fun newerPreparationSupersedesOlderOne() {
        val epoch = P2pPreparationEpoch()
        val first = epoch.begin()
        val second = epoch.begin()

        assertFalse(epoch.isCurrent(first))
        assertTrue(epoch.isCurrent(second))
        assertThrows(P2pPreparationSupersededException::class.java) {
            epoch.requireCurrent(first)
        }
    }

    @Test
    fun stopCancelsInFlightPreparation() {
        val epoch = P2pPreparationEpoch()
        val preparing = epoch.begin()

        epoch.cancelAll()

        assertFalse(epoch.isCurrent(preparing))
        assertThrows(P2pPreparationSupersededException::class.java) {
            epoch.requireCurrent(preparing)
        }
    }

    @Test
    fun newPreparationAfterStopIsAllowed() {
        val epoch = P2pPreparationEpoch()
        val old = epoch.begin()
        epoch.cancelAll()
        val fresh = epoch.begin()

        assertFalse(epoch.isCurrent(old))
        assertTrue(epoch.isCurrent(fresh))
        epoch.requireCurrent(fresh)
    }
}
