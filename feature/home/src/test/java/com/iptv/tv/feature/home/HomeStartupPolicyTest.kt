package com.iptv.tv.feature.home

import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeStartupPolicyTest {
    @Test
    fun findsPreviouslyImportedReadyPlaylistByExactSourceUrl() {
        val playlists = listOf(
            playlist(id = 7L, source = "https://example.org/one.m3u"),
            playlist(id = 9L, source = "https://example.org/two.m3u")
        )

        assertEquals(
            9L,
            findImportedReadyPlaylist(playlists, "https://example.org/two.m3u")?.id
        )
    }

    @Test
    fun sourceUrlMatchingTrimsSurroundingWhitespace() {
        val playlists = listOf(
            playlist(id = 3L, source = "  https://example.org/live.m3u  ")
        )

        assertEquals(
            3L,
            findImportedReadyPlaylist(playlists, "https://example.org/live.m3u")?.id
        )
    }

    @Test
    fun returnsNullWhenReadyPlaylistWasNotImported() {
        assertNull(
            findImportedReadyPlaylist(
                playlists = listOf(playlist(id = 1L, source = "https://example.org/one.m3u")),
                sourceUrl = "https://example.org/missing.m3u"
            )
        )
    }

    private fun playlist(id: Long, source: String) = Playlist(
        id = id,
        name = "Playlist $id",
        sourceType = PlaylistSourceType.URL,
        source = source,
        scheduleHours = 12,
        lastSyncedAt = null,
        channelCount = 10,
        isCustom = false
    )
}
