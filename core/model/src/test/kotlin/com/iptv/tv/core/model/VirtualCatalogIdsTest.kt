package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualCatalogIdsTest {
    @Test
    fun systemAggregateIdsAreStableNegativeAndDistinct() {
        val ids = listOf(
            VIRTUAL_ALL_CHANNELS_PLAYLIST_ID,
            VIRTUAL_FAVORITES_PLAYLIST_ID,
            VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID
        )

        assertTrue(ids.all { it < 0 })
        assertEquals(ids.size, ids.distinct().size)
        assertEquals("virtual://all-channels", VIRTUAL_ALL_CHANNELS_SOURCE)
        assertEquals("virtual://favorites", VIRTUAL_FAVORITES_SOURCE)
        assertEquals("virtual://recent-channels", VIRTUAL_RECENT_CHANNELS_SOURCE)
    }

    @Test
    fun systemVirtualPredicateIncludesRecentAndRejectsUnreservedIds() {
        assertTrue(isSystemVirtualPlaylistId(VIRTUAL_ALL_CHANNELS_PLAYLIST_ID))
        assertTrue(isSystemVirtualPlaylistId(VIRTUAL_FAVORITES_PLAYLIST_ID))
        assertTrue(isSystemVirtualPlaylistId(VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID))
        assertFalse(isSystemVirtualPlaylistId(1L))
        assertFalse(isSystemVirtualPlaylistId(-1L))
    }
}
