package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveStartupDhtProbePolicyTest {
    @Test
    fun `startup DHT probe releases first alternative candidate immediately`() {
        assertEquals(1, ACE_LIVE_STARTUP_DHT_PROBE_RETURN_AFTER_PEERS)
    }

    @Test
    fun `startup DHT probe remains bounded to two independent rounds`() {
        assertTrue(aceLiveStartupDhtProbeShouldContinue(completedRounds = 1))
        assertFalse(aceLiveStartupDhtProbeShouldContinue(completedRounds = 2))
        assertEquals(2, ACE_LIVE_STARTUP_DHT_PROBE_MAX_ROUNDS)
        assertEquals(7_000L, ACE_LIVE_STARTUP_DHT_PROBE_BUDGET_MILLIS)
    }
}
