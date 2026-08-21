package com.iptv.tv.feature.playlists

import com.iptv.tv.core.model.CatalogNodeKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCatalogNavigationSessionTest {
    @Test
    fun startsAtPlaylistWithSourceBreadcrumbAndCanonicalChildren() {
        val session = PlaylistCatalogNavigationSession.create(
            playlist = playlist(),
            channels = listOf(
                channel(id = 1L, name = "No group", group = null, order = 20),
                channel(id = 2L, name = "Sport", group = "Sports", order = 10)
            )
        )

        val snapshot = session.snapshot()

        assertEquals(listOf(CatalogNodeKind.SOURCE, CatalogNodeKind.PLAYLIST), snapshot.breadcrumbs.map { it.kind })
        assertEquals(CatalogNodeKind.PLAYLIST, snapshot.breadcrumbs.last().kind)
        assertEquals(listOf(CatalogNodeKind.GROUP, CatalogNodeKind.CHANNEL), snapshot.entries.map { it.kind })
        assertTrue(snapshot.canGoBack)
    }

    @Test
    fun groupRoundTripRestoresGroupFocusOnPlaylist() {
        val session = PlaylistCatalogNavigationSession.create(
            playlist = playlist(),
            channels = listOf(channel(id = 10L, name = "News", group = "General"))
        )
        val group = session.snapshot().entries.single()

        assertTrue(session.enter(group.nodeId))
        assertEquals(CatalogNodeKind.GROUP, session.snapshot().breadcrumbs.last().kind)
        assertTrue(session.back())

        val returned = session.snapshot()
        assertEquals(CatalogNodeKind.PLAYLIST, returned.breadcrumbs.last().kind)
        assertEquals(group.nodeId, returned.restoredFocusId)
    }

    @Test
    fun focusedChannelSurvivesTreeRebuildAndKeepsConcretePlayerLookup() {
        val sourcePlaylist = playlist()
        val channels = listOf(channel(id = 42L, name = "News", group = "General", tvgId = "news"))
        val first = PlaylistCatalogNavigationSession.create(sourcePlaylist, channels)
        val group = first.snapshot().entries.single()
        first.enter(group.nodeId)
        val channel = first.snapshot().entries.single()
        first.focus(channel.nodeId)

        val rebuilt = PlaylistCatalogNavigationSession.create(
            playlist = sourcePlaylist.copy(name = "Renamed visible title"),
            channels = channels,
            previousCheckpoint = first.checkpoint()
        )
        val restored = rebuilt.snapshot()

        assertEquals(CatalogNodeKind.GROUP, restored.breadcrumbs.last().kind)
        assertEquals(channel.nodeId, restored.restoredFocusId)
        assertEquals(42L, restored.entries.single().channelId)
    }

    @Test
    fun removedGroupFallsBackToDeepestValidPlaylistPathAndDropsStaleFocus() {
        val sourcePlaylist = playlist()
        val first = PlaylistCatalogNavigationSession.create(
            playlist = sourcePlaylist,
            channels = listOf(channel(id = 7L, name = "Old", group = "Removed"))
        )
        val oldGroup = first.snapshot().entries.single()
        first.enter(oldGroup.nodeId)
        first.focus(first.snapshot().entries.single().nodeId)

        val rebuilt = PlaylistCatalogNavigationSession.create(
            playlist = sourcePlaylist,
            channels = listOf(channel(id = 8L, name = "New", group = "Current")),
            previousCheckpoint = first.checkpoint()
        )
        val restored = rebuilt.snapshot()
        val currentGroup = restored.entries.single()

        assertEquals(CatalogNodeKind.PLAYLIST, restored.breadcrumbs.last().kind)
        assertEquals("Current", currentGroup.name)
        assertEquals(currentGroup.nodeId, restored.restoredFocusId)
        assertNotEquals(oldGroup.nodeId, restored.restoredFocusId)
    }

    @Test
    fun channelLeafCannotBeEnteredAsContainer() {
        val session = PlaylistCatalogNavigationSession.create(
            playlist = playlist(),
            channels = listOf(channel(id = 3L, name = "Direct", group = null))
        )
        val channel = session.snapshot().entries.single()

        assertTrue(channel.isChannel)
        assertFalse(session.enter(channel.nodeId))
        assertEquals(CatalogNodeKind.PLAYLIST, session.snapshot().breadcrumbs.last().kind)
    }

    @Test
    fun sourceRootBackReturnsFalse() {
        val session = PlaylistCatalogNavigationSession.create(
            playlist = playlist(),
            channels = listOf(channel(id = 1L, name = "News", group = null))
        )

        assertTrue(session.back())
        assertEquals(CatalogNodeKind.SOURCE, session.snapshot().breadcrumbs.last().kind)
        assertFalse(session.back())
    }

    private fun playlist() = Playlist(
        id = 100L,
        name = "Playlist",
        sourceType = PlaylistSourceType.URL,
        source = "https://example.test/list.m3u",
        scheduleHours = 12,
        lastSyncedAt = null,
        channelCount = 2,
        isCustom = false
    )

    private fun channel(
        id: Long,
        name: String,
        group: String?,
        order: Int = id.toInt(),
        tvgId: String? = null
    ) = Channel(
        id = id,
        playlistId = 100L,
        tvgId = tvgId,
        name = name,
        group = group,
        logo = null,
        streamUrl = "https://example.test/live/$id",
        health = ChannelHealth.UNKNOWN,
        orderIndex = order,
        isHidden = false
    )
}
