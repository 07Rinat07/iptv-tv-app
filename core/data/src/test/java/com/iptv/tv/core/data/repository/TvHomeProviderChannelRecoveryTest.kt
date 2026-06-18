package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class TvHomeProviderChannelRecoveryTest {

    @Test
    fun decide_keepsExistingWhenStoredChannelUpdates() {
        val action = TvHomeProviderChannelRecovery.decide(
            existingProviderChannelId = 10,
            existingUpdateRows = 1,
            discoveredProviderChannelId = null,
            discoveredUpdateRows = null
        )

        assertEquals(TvHomeProviderChannelAction.KEEP_EXISTING, action)
    }

    @Test
    fun decide_reusesDiscoveredWhenStoredChannelIsMissing() {
        val action = TvHomeProviderChannelRecovery.decide(
            existingProviderChannelId = 10,
            existingUpdateRows = 0,
            discoveredProviderChannelId = 20,
            discoveredUpdateRows = 1
        )

        assertEquals(TvHomeProviderChannelAction.REUSE_DISCOVERED, action)
    }

    @Test
    fun decide_insertsNewWhenNoStoredOrDiscoveredChannelCanBeUpdated() {
        val action = TvHomeProviderChannelRecovery.decide(
            existingProviderChannelId = 10,
            existingUpdateRows = 0,
            discoveredProviderChannelId = 20,
            discoveredUpdateRows = 0
        )

        assertEquals(TvHomeProviderChannelAction.INSERT_NEW, action)
    }
}
