package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CanonicalCatalogNavigationTest {
    @Test
    fun initialBuildsCompleteBreadcrumbAndDeterministicChildren() {
        val tree = fixtureTree()
        val navigator = CanonicalCatalogNavigator(tree.nodes)

        val state = navigator.initial(tree.playlistId)
        val context = navigator.context(state)

        assertEquals(listOf(tree.sourceId, tree.playlistId), state.path)
        assertEquals(listOf("Source", "Playlist"), context.breadcrumbs.map { it.name })
        assertEquals(listOf(tree.newsGroupId, tree.sportGroupId), context.children.map { it.id })
        assertEquals(tree.newsGroupId, context.restoredFocusId)
    }

    @Test
    fun enterAndBackRestoreTheContainerThatWasLeft() {
        val tree = fixtureTree()
        val navigator = CanonicalCatalogNavigator(tree.nodes)

        val playlistState = navigator.initial(tree.playlistId)
        val groupState = navigator.enter(playlistState, tree.sportGroupId)
        val afterBack = navigator.back(groupState)

        assertEquals(listOf(tree.sourceId, tree.playlistId, tree.sportGroupId), groupState.path)
        assertEquals(listOf(tree.sourceId, tree.playlistId), afterBack.path)
        assertEquals(tree.sportGroupId, navigator.context(afterBack).restoredFocusId)
    }

    @Test
    fun focusedChannelSurvivesPlayerRoundTripWithoutEnteringLeaf() {
        val tree = fixtureTree()
        val navigator = CanonicalCatalogNavigator(tree.nodes)
        val groupState = navigator.enter(navigator.initial(tree.playlistId), tree.newsGroupId)

        val focused = navigator.focus(groupState, tree.newsChannelId)

        assertEquals(groupState.path, focused.path)
        assertEquals(tree.newsChannelId, navigator.context(focused).restoredFocusId)
        assertThrows(IllegalArgumentException::class.java) {
            navigator.enter(focused, tree.newsChannelId)
        }
    }

    @Test
    fun restoreKeepsOnlyDeepestValidPrefixAfterTreeRebuild() {
        val original = fixtureTree()
        val navigator = CanonicalCatalogNavigator(original.nodes)
        val groupState = navigator.enter(navigator.initial(original.playlistId), original.newsGroupId)
        val focused = navigator.focus(groupState, original.newsChannelId)

        val rebuiltNodes = original.nodes.filterNot { node ->
            node.id == original.newsGroupId || node.id == original.newsChannelId
        }
        val rebuilt = CanonicalCatalogNavigator(rebuiltNodes)
        val restored = rebuilt.restore(focused, fallbackNodeId = original.playlistId)
        val context = rebuilt.context(restored)

        assertEquals(listOf(original.sourceId, original.playlistId), restored.path)
        assertEquals(original.sportGroupId, context.restoredFocusId)
        assertNull(restored.focusedChildIdByParent[original.newsGroupId])
    }

    @Test
    fun restoreFallsBackWhenSavedRootNoLongerExists() {
        val oldTree = fixtureTree(sourceSuffix = "old")
        val oldNavigator = CanonicalCatalogNavigator(oldTree.nodes)
        val oldState = oldNavigator.enter(oldNavigator.initial(oldTree.playlistId), oldTree.newsGroupId)

        val newTree = fixtureTree(sourceSuffix = "new")
        val newNavigator = CanonicalCatalogNavigator(newTree.nodes)
        val restored = newNavigator.restore(oldState, fallbackNodeId = newTree.playlistId)

        assertEquals(listOf(newTree.sourceId, newTree.playlistId), restored.path)
    }

    @Test
    fun childOrderUsesOrderThenNameThenStableId() {
        val tree = fixtureTree()
        val sameOrderA = node(
            id = "group-a",
            kind = CatalogNodeKind.GROUP,
            name = "Alpha",
            parentId = tree.playlistId,
            order = 50
        )
        val sameOrderB = node(
            id = "group-b",
            kind = CatalogNodeKind.GROUP,
            name = "Beta",
            parentId = tree.playlistId,
            order = 50
        )
        val navigator = CanonicalCatalogNavigator(tree.nodes + sameOrderB + sameOrderA)

        val children = navigator.context(navigator.initial(tree.playlistId)).children

        assertEquals(
            listOf(tree.newsGroupId, tree.sportGroupId, sameOrderA.id, sameOrderB.id),
            children.map { it.id }
        )
    }

    private fun fixtureTree(sourceSuffix: String = "main"): FixtureTree {
        val sourceId = CatalogNodeId("source-$sourceSuffix")
        val playlistId = CatalogNodeId("playlist-$sourceSuffix")
        val newsGroupId = CatalogNodeId("news-$sourceSuffix")
        val sportGroupId = CatalogNodeId("sport-$sourceSuffix")
        val newsChannelId = CatalogNodeId("channel-news-$sourceSuffix")
        val sportChannelId = CatalogNodeId("channel-sport-$sourceSuffix")

        return FixtureTree(
            sourceId = sourceId,
            playlistId = playlistId,
            newsGroupId = newsGroupId,
            sportGroupId = sportGroupId,
            newsChannelId = newsChannelId,
            nodes = listOf(
                node(sourceId.value, CatalogNodeKind.SOURCE, "Source", null, 0),
                node(playlistId.value, CatalogNodeKind.PLAYLIST, "Playlist", sourceId, 0),
                node(newsGroupId.value, CatalogNodeKind.GROUP, "News", playlistId, 10),
                node(sportGroupId.value, CatalogNodeKind.GROUP, "Sport", playlistId, 20),
                node(newsChannelId.value, CatalogNodeKind.CHANNEL, "News One", newsGroupId, 1),
                node(sportChannelId.value, CatalogNodeKind.CHANNEL, "Sport One", sportGroupId, 1)
            )
        )
    }

    private fun node(
        id: String,
        kind: CatalogNodeKind,
        name: String,
        parentId: CatalogNodeId?,
        order: Int
    ) = CanonicalCatalogNode(
        id = CatalogNodeId(id),
        kind = kind,
        name = name,
        parentId = parentId,
        order = order,
        provenance = CatalogProvenance(
            origin = CatalogOriginKind.SYSTEM,
            sourceKey = "test-source"
        )
    )

    private data class FixtureTree(
        val sourceId: CatalogNodeId,
        val playlistId: CatalogNodeId,
        val newsGroupId: CatalogNodeId,
        val sportGroupId: CatalogNodeId,
        val newsChannelId: CatalogNodeId,
        val nodes: List<CanonicalCatalogNode>
    )
}
