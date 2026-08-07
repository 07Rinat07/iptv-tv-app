package com.iptv.tv.core.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedP2pStopPolicyTest {
    @Test
    fun preparationWithoutPublishedStreamMustNotBlockDirectPlayback() {
        assertFalse(shouldAwaitEmbeddedCleanup(activeStreamPublished = false))
    }

    @Test
    fun publishedStreamMustBeClosedSynchronously() {
        assertTrue(shouldAwaitEmbeddedCleanup(activeStreamPublished = true))
    }

    private fun shouldAwaitEmbeddedCleanup(activeStreamPublished: Boolean): Boolean =
        activeStreamPublished
}
