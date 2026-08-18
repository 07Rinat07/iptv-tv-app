package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AceLiveStartupDhtProbePolicyTest {
    @Test
    fun `startup DHT probe releases first alternative candidate immediately`() {
        assertEquals(1, ACE_LIVE_STARTUP_DHT_PROBE_RETURN_AFTER_PEERS)
    }

    @Test
    fun `startup DHT probe runs once before the bounded full expansion`() {
        assertFalse(aceLiveStartupDhtProbeShouldContinue(completedRounds = 1))
        assertEquals(1, ACE_LIVE_STARTUP_DHT_PROBE_MAX_ROUNDS)
        assertEquals(7_000L, ACE_LIVE_STARTUP_DHT_PROBE_BUDGET_MILLIS)
    }
}
