package com.iptv.tv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadyPlaylistPresetsTest {

    @Test
    fun readyCatalogContainsExactlyThreeRequestedLiveSources() {
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/Dimonovich/TV/Dimonovich/FREE/TV",
                "https://dl.dropboxusercontent.com/scl/fi/ur595ef4cqmfst951kboh/.NET_2.m3u?rlkey=0cw1ficfrq0m6yg2udh16qn78&dl=0",
                "https://iptv.org.ua/iptv/provayder.m3u"
            ),
            READY_PLAYLIST_PRESETS.map { preset -> preset.url }
        )
    }

    @Test
    fun readyPlaylistUrlsRemainUnique() {
        val duplicateUrl = READY_PLAYLIST_PRESETS
            .groupingBy { preset -> preset.url.lowercase() }
            .eachCount()
            .entries
            .firstOrNull { (_, count) -> count > 1 }

        assertEquals(3, READY_PLAYLIST_PRESETS.size)
        assertNull(duplicateUrl)
    }
}
