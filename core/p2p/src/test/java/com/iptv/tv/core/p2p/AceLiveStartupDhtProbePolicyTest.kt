package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveStartupDhtProbePolicyTest {
    @Test
    fun `startup DHT probe keeps the bounded window open for alternative candidates`() {
        // Field evidence repeatedly showed one tracker candidate plus at most one DHT candidate,
        // with both endpoints often failing TCP qualification. Each startup probe therefore keeps
        // its bounded window open for a small alternative batch instead of terminating on one peer.
        assertEquals(4, ACE_LIVE_STARTUP_DHT_PROBE_RETURN_AFTER_PEERS)
    }

    @Test
    fun `startup DHT diversity uses two bounded independent rounds`() {
        assertTrue(aceLiveStartupDhtProbeShouldContinue(completedRounds = 1))
        assertFalse(aceLiveStartupDhtProbeShouldContinue(completedRounds = 2))
        assertEquals(2, ACE_LIVE_STARTUP_DHT_PROBE_MAX_ROUNDS)
        assertEquals(7_000L, ACE_LIVE_STARTUP_DHT_PROBE_BUDGET_MILLIS)
    }
}
