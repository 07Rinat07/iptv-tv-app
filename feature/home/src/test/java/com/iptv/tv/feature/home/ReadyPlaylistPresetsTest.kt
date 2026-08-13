package com.iptv.tv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadyPlaylistPresetsTest {

    @Test
    fun includesAceStreamProviderPlaylistExactlyOnce() {
        val providerUrl = "https://iptv.org.ua/iptv/provayder.m3u"
        val matchingPresets = READY_PLAYLIST_PRESETS.filter { preset ->
            preset.url == providerUrl
        }

        assertEquals(1, matchingPresets.size)
        assertTrue(matchingPresets.single().name.contains("Ace Stream"))
    }

    @Test
    fun readyPlaylistUrlsRemainUnique() {
        val duplicateUrl = READY_PLAYLIST_PRESETS
            .groupingBy { preset -> preset.url.lowercase() }
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }

        assertNotNull(READY_PLAYLIST_PRESETS.firstOrNull())
        assertNull(duplicateUrl)
    }
}
