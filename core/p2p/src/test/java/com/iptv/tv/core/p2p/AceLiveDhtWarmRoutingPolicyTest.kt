package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveDhtWarmRoutingPolicyTest {
    @Test
    fun `production default uses eight parallel DHT branches`() {
        assertEquals(8, AceLiveDhtPolicy().searchBranching)
    }

    @Test
    fun `eight branches keep one bootstrap lane and use four warm routing seeds`() {
        assertEquals(4, aceDhtWarmRoutingSeedLimit(AceLiveDhtPolicy().searchBranching))
    }
}
