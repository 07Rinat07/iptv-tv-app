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
        assertEquals(ACE_STREAM_TORRENT_SOURCE_KEY, matchingPresets.single().sourceKey)
        assertEquals(ACE_STREAM_TORRENT_M3U, matchingPresets.single().embeddedM3u)
    }

    @Test
    fun bundledAceStreamPlaylistContainsOnly279UniqueTorrentChannels() {
        val lines = ACE_STREAM_TORRENT_M3U.lineSequence().map(String::trim).toList()
        val metadata = lines.filter { line -> line.startsWith("#EXTINF") }
        val streamUrls = lines.filter { line -> line.isNotBlank() && !line.startsWith('#') }
        val contentIdRegex = Regex("(?:id|infohash)=([0-9a-f]{40})", RegexOption.IGNORE_CASE)

        assertEquals("#EXTM3U", lines.first())
        assertEquals(279, metadata.size)
        assertEquals(279, streamUrls.size)
        assertTrue(metadata.all { line -> line.contains("group-title=\"$ACE_STREAM_TORRENT_GROUP\"") })
        assertTrue(streamUrls.all { url -> url.startsWith("http://127.0.0.1:6878/ace/getstream?") })
        assertEquals(
            279,
            streamUrls.mapNotNull { url -> contentIdRegex.find(url)?.groupValues?.get(1) }
                .map(String::lowercase)
                .distinct()
                .size
        )
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
