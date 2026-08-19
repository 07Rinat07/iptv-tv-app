package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AceLiveStartupDhtProbePolicyTest {
    @Test
    fun `startup DHT probe keeps the bounded window open for alternative candidates`() {
        // Field evidence from issue #154 repeatedly showed one tracker candidate plus at most one
        // DHT candidate, with both endpoints often failing TCP qualification. The startup probe must
        // therefore use its existing bounded window to collect a small alternative batch instead of
        // terminating on the first DHT endpoint.
        assertEquals(4, ACE_LIVE_STARTUP_DHT_PROBE_RETURN_AFTER_PEERS)
    }

    @Test
    fun `startup DHT candidate batch does not widen time or round budgets`() {
        assertFalse(aceLiveStartupDhtProbeShouldContinue(completedRounds = 1))
        assertEquals(1, ACE_LIVE_STARTUP_DHT_PROBE_MAX_ROUNDS)
        assertEquals(7_000L, ACE_LIVE_STARTUP_DHT_PROBE_BUDGET_MILLIS)
    }
}
