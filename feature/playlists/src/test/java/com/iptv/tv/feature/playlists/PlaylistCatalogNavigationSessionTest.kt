package com.iptv.tv.feature.playlists

import com.iptv.tv.core.model.CatalogNodeKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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
        first.focus(channel.nodeId, first.snapshot())

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
        val groupSnapshot = first.snapshot()
        first.focus(groupSnapshot.entries.single().nodeId, groupSnapshot)

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
    fun hiddenChannelsAreNotExposedAsLaunchableCatalogEntries() {
        val session = PlaylistCatalogNavigationSession.create(
            playlist = playlist(),
            channels = listOf(
                channel(id = 3L, name = "Visible", group = null),
                channel(id = 4L, name = "Hidden", group = null, isHidden = true)
            )
        )

        val entries = session.snapshot().entries

        assertEquals(listOf("Visible"), entries.map { it.name })
        assertEquals(listOf(3L), entries.map { it.channelId })
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

    @Test
    fun focusInLargeFlatCatalogReusesPreparedListsAndSurvivesRebuild() {
        val sourcePlaylist = playlist()
        val channels = (1L..10_000L).map { id ->
            channel(id = id, name = "Channel $id", group = null)
        }
        val session = PlaylistCatalogNavigationSession.create(sourcePlaylist, channels)
        val preparedSnapshot = session.snapshot()
        var focusedSnapshot = preparedSnapshot

        preparedSnapshot.entries.takeLast(100).forEach { entry ->
            focusedSnapshot = session.focus(entry.nodeId, focusedSnapshot)
            assertSame(preparedSnapshot.entries, focusedSnapshot.entries)
            assertSame(preparedSnapshot.breadcrumbs, focusedSnapshot.breadcrumbs)
        }

        val expectedFocusId = preparedSnapshot.entries.last().nodeId
        assertEquals(10_000, focusedSnapshot.entries.size)
        assertEquals(expectedFocusId, focusedSnapshot.restoredFocusId)
        assertEquals(
            expectedFocusId,
            session.checkpoint().focusedChildIdByParent[preparedSnapshot.currentNodeId]
        )
        assertEquals(channels.map { it.id }, focusedSnapshot.entries.map { it.channelId })

        val rebuilt = PlaylistCatalogNavigationSession.create(
            playlist = sourcePlaylist,
            channels = channels,
            previousCheckpoint = session.checkpoint()
        ).snapshot()

        assertEquals(expectedFocusId, rebuilt.restoredFocusId)
        assertEquals(10_000L, rebuilt.entries.last().channelId)
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
        tvgId: String? = null,
        isHidden: Boolean = false
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
        isHidden = isHidden
    )
}
