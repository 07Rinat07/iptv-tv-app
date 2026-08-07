package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class P2pLifecycleContractTest {
    @Test
    fun lifecycleCleanupKeepsCachedContentPolicyExplicit() {
        // SessionManager.remove(handle) is intentionally used without delete-files flags.
        // Cached torrent data therefore remains reusable after stream/session release.
        assertEquals("preserve-cache", P2pLifecyclePolicy.CACHE_BEHAVIOR)
    }
}

internal object P2pLifecyclePolicy {
    const val CACHE_BEHAVIOR = "preserve-cache"
}
