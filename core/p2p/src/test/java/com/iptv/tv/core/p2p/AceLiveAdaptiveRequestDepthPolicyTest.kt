package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveAdaptiveRequestDepthPolicyTest {
    @Test
    fun mapsStablePressureToBoundedDepth() {
        val policy = AceLiveAdaptiveRequestDepthPolicy()

        assertEquals(4, policy.depthFor(AceLiveBufferPressure.CRITICAL))
        assertEquals(3, policy.depthFor(AceLiveBufferPressure.LOW))
        assertEquals(2, policy.depthFor(AceLiveBufferPressure.TARGET))
        assertEquals(1, policy.depthFor(AceLiveBufferPressure.HIGH))
    }

    @Test
    fun missingConsumerPressureKeepsHistoricalBaseline() {
        assertEquals(2, AceLiveAdaptiveRequestDepthPolicy().depthFor(null))
    }

    @Test
    fun rejectsNonMonotonicOrUnboundedSettings() {
        val nonMonotonic = runCatching {
            AceLiveAdaptiveRequestDepthSettings(
                criticalDepth = 2,
                lowDepth = 3,
                targetDepth = 2,
                highDepth = 1,
                hardMaxDepth = 4
            )
        }
        val aboveHardMax = runCatching {
            AceLiveAdaptiveRequestDepthSettings(
                criticalDepth = 5,
                lowDepth = 3,
                targetDepth = 2,
                highDepth = 1,
                hardMaxDepth = 4
            )
        }

        assertTrue(nonMonotonic.isFailure)
        assertTrue(aboveHardMax.isFailure)
    }
}
