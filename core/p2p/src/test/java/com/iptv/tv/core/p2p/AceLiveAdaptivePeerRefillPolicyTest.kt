package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveAdaptivePeerRefillPolicyTest {
    @Test
    fun pressureMapsToBoundedExtraPeerProbes() {
        val policy = AceLiveAdaptivePeerRefillPolicy()

        assertEquals(2, policy.extraProbePeersFor(AceLiveBufferPressure.CRITICAL))
        assertEquals(1, policy.extraProbePeersFor(AceLiveBufferPressure.LOW))
        assertEquals(0, policy.extraProbePeersFor(AceLiveBufferPressure.TARGET))
        assertEquals(0, policy.extraProbePeersFor(AceLiveBufferPressure.HIGH))
        assertEquals(0, policy.extraProbePeersFor(null))
    }

    @Test
    fun rejectsNonMonotonicOrUnboundedSettings() {
        val nonMonotonic = runCatching {
            AceLiveAdaptivePeerRefillSettings(
                lowExtraPeers = 2,
                criticalExtraPeers = 1,
                hardMaxExtraPeers = 2
            )
        }
        val unbounded = runCatching {
            AceLiveAdaptivePeerRefillSettings(
                lowExtraPeers = 1,
                criticalExtraPeers = 3,
                hardMaxExtraPeers = 2
            )
        }

        assertTrue(nonMonotonic.isFailure)
        assertTrue(unbounded.isFailure)
    }
}
