package com.iptv.tv.feature.playlists

import com.iptv.tv.core.model.CanonicalCatalogNavigator
import com.iptv.tv.core.model.CatalogNavigationState
import com.iptv.tv.core.model.CatalogNodeId
import com.iptv.tv.core.model.CatalogNodeKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.LegacyPlaylistCatalogAdapter
import com.iptv.tv.core.model.Playlist

/** One canonical breadcrumb rendered by the playlists feature. */
data class PlaylistCatalogBreadcrumb(
    val nodeId: CatalogNodeId,
    val kind: CatalogNodeKind,
    val name: String
)

/** One direct child of the current canonical catalog container. */
data class PlaylistCatalogEntry(
    val nodeId: CatalogNodeId,
    val kind: CatalogNodeKind,
    val name: String,
    /** Concrete legacy row used only when opening Player. Canonical identity remains [nodeId]. */
    val channelId: Long? = null
) {
    val isChannel: Boolean
        get() = kind == CatalogNodeKind.CHANNEL
}

/** UI-neutral snapshot consumed by [PlaylistsScreen]. */
data class PlaylistCatalogSnapshot(
    val playlistId: Long,
    val currentNodeId: CatalogNodeId,
    val currentTitle: String,
    val breadcrumbs: List<PlaylistCatalogBreadcrumb>,
    val entries: List<PlaylistCatalogEntry>,
    val restoredFocusId: CatalogNodeId?,
    val canGoBack: Boolean
)

/**
 * Feature-layer bridge between legacy Playlist/Channel storage and the canonical catalog navigator.
 *
 * The mutable part is intentionally tiny: only the canonical navigation checkpoint changes. A
 * rebuilt playlist tree can therefore restore the deepest still-valid path/focus by stable
 * canonical ids instead of by transient Room row ids or list indexes.
 */
class PlaylistCatalogNavigationSession private constructor(
    val playlistId: Long,
    private val playlistNodeId: CatalogNodeId,
    private val navigator: CanonicalCatalogNavigator,
    private val channelIdByNodeId: Map<CatalogNodeId, Long>,
    private var navigationState: CatalogNavigationState
) {
    fun checkpoint(): CatalogNavigationState = navigationState

    /** Restores a new unpublished session without rebuilding the canonical tree or lookup maps. */
    fun restored(checkpoint: CatalogNavigationState): PlaylistCatalogNavigationSession {
        val restoredState = runCatching {
            navigator.restore(checkpoint, fallbackNodeId = playlistNodeId)
        }.getOrNull() ?: navigator.initial(playlistNodeId)
        return PlaylistCatalogNavigationSession(
            playlistId = playlistId,
            playlistNodeId = playlistNodeId,
            navigator = navigator,
            channelIdByNodeId = channelIdByNodeId,
            navigationState = restoredState
        )
    }

    fun snapshot(): PlaylistCatalogSnapshot {
        val context = navigator.context(navigationState)
        return PlaylistCatalogSnapshot(
            playlistId = playlistId,
            currentNodeId = context.currentNode.id,
            currentTitle = context.currentNode.name,
            breadcrumbs = context.breadcrumbs.map { node ->
                PlaylistCatalogBreadcrumb(
                    nodeId = node.id,
                    kind = node.kind,
                    name = node.name
                )
            },
            entries = context.children.map { node ->
                PlaylistCatalogEntry(
                    nodeId = node.id,
                    kind = node.kind,
                    name = node.name,
                    channelId = channelIdByNodeId[node.id]
                )
            },
            restoredFocusId = context.restoredFocusId,
            canGoBack = navigationState.path.size > 1
        )
    }

    /**
     * Records focus while retaining the already prepared UI lists for the current hierarchy level.
     *
     * Focus changes only the navigation checkpoint. Rebuilding [PlaylistCatalogSnapshot.entries]
     * here would turn every D-pad move into an O(N) allocation for large flat catalogs.
     */
    fun focus(
        nodeId: CatalogNodeId,
        currentSnapshot: PlaylistCatalogSnapshot
    ): PlaylistCatalogSnapshot {
        require(currentSnapshot.playlistId == playlistId) {
            "Catalog snapshot belongs to another playlist"
        }
        require(currentSnapshot.currentNodeId == navigationState.currentNodeId) {
            "Catalog snapshot does not match the current navigation level"
        }
        navigationState = navigator.focus(navigationState, nodeId)
        return if (currentSnapshot.restoredFocusId == nodeId) {
            currentSnapshot
        } else {
            currentSnapshot.copy(restoredFocusId = nodeId)
        }
    }

    /** Enters a direct child container. Channel leaves are deliberately opened by Player instead. */
    fun enter(nodeId: CatalogNodeId): Boolean {
        val entry = snapshot().entries.firstOrNull { it.nodeId == nodeId } ?: return false
        if (entry.isChannel) return false
        navigationState = navigator.enter(navigationState, nodeId)
        return true
    }

    /** Moves exactly one canonical hierarchy level up; returns false only at the source root. */
    fun back(): Boolean {
        if (navigationState.path.size <= 1) return false
        navigationState = navigator.back(navigationState)
        return true
    }

    companion object {
        fun create(
            playlist: Playlist,
            channels: List<Channel>,
            previousCheckpoint: CatalogNavigationState? = null
        ): PlaylistCatalogNavigationSession {
            // Hidden rows remain available to summaries/editor flows, but exposing them in the
            // launchable catalog is unsafe: Player intentionally filters them and would otherwise
            // fall back to a different visible channel for the requested hidden id.
            val visibleChannels = channels.filterNot(Channel::isHidden)
            val tree = LegacyPlaylistCatalogAdapter.build(playlist = playlist, channels = visibleChannels)
            val navigator = CanonicalCatalogNavigator(tree.nodes)
            val channelIdByNodeId = tree.channelVariantIdsByNodeId.mapValues { (_, variants) ->
                variants.first()
            }

            val session = PlaylistCatalogNavigationSession(
                playlistId = playlist.id,
                playlistNodeId = tree.playlistNodeId,
                navigator = navigator,
                channelIdByNodeId = channelIdByNodeId,
                navigationState = navigator.initial(tree.playlistNodeId)
            )
            return previousCheckpoint?.let(session::restored) ?: session
        }
    }
}
