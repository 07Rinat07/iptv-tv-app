package com.iptv.tv.core.model

import java.util.Locale

/**
 * Immutable UI-neutral navigation checkpoint for the canonical catalog hierarchy.
 *
 * [path] always stores the full canonical breadcrumb from a hierarchy root to the current
 * container. [focusedChildIdByParent] remembers the last focused direct child for every visited
 * container, so TV/D-pad UIs can restore focus after Back or after returning from Player without
 * coupling that behavior to a particular Compose screen.
 */
data class CatalogNavigationState(
    val path: List<CatalogNodeId>,
    val focusedChildIdByParent: Map<CatalogNodeId, CatalogNodeId> = emptyMap()
) {
    init {
        require(path.isNotEmpty()) { "Catalog navigation path must not be empty" }
    }

    val currentNodeId: CatalogNodeId
        get() = path.last()
}

/** Current hierarchy context prepared for a catalog/navigation UI. */
data class CatalogNavigationContext(
    val currentNode: CanonicalCatalogNode,
    val breadcrumbs: List<CanonicalCatalogNode>,
    val children: List<CanonicalCatalogNode>,
    val restoredFocusId: CatalogNodeId?
)

/**
 * Pure canonical hierarchy navigator.
 *
 * The navigator does not own Android navigation, Player launching or persistence. It only applies
 * deterministic hierarchy transitions and focus-memory rules on top of [CanonicalCatalogNode].
 * This keeps Back/focus behavior identical for playlists, ready catalogs, Scanner imports,
 * providers and future virtual aggregate views.
 */
class CanonicalCatalogNavigator(nodes: List<CanonicalCatalogNode>) {
    private val nodesById: Map<CatalogNodeId, CanonicalCatalogNode>
    private val childrenByParentId: Map<CatalogNodeId, List<CanonicalCatalogNode>>

    init {
        require(nodes.isNotEmpty()) { "Canonical catalog navigation requires at least one node" }
        require(nodes.map(CanonicalCatalogNode::id).distinct().size == nodes.size) {
            "Canonical catalog navigation requires unique node ids"
        }

        nodesById = nodes.associateBy(CanonicalCatalogNode::id)
        validateHierarchy()
        childrenByParentId = nodes
            .mapNotNull { node -> node.parentId?.let { parentId -> parentId to node } }
            .groupBy(
                keySelector = { (parentId, _) -> parentId },
                valueTransform = { (_, node) -> node }
            )
            .mapValues { (_, children) -> children.sortedWith(CHILD_ORDER) }
    }

    /**
     * Starts navigation at [startNodeId] while retaining its complete breadcrumb to the hierarchy
     * root. Channel nodes remain leaves and are focused/opened by feature code rather than becoming
     * catalog containers themselves.
     */
    fun initial(startNodeId: CatalogNodeId): CatalogNavigationState {
        val start = node(startNodeId)
        require(start.kind != CatalogNodeKind.CHANNEL) {
            "Channel nodes are leaves and cannot be navigation containers"
        }
        return CatalogNavigationState(path = ancestorPath(startNodeId))
    }

    /** Returns deterministic children, breadcrumb context and the focus target for the current UI. */
    fun context(state: CatalogNavigationState): CatalogNavigationContext {
        validateStatePath(state.path)
        val current = node(state.currentNodeId)
        val children = childrenByParentId[current.id].orEmpty()
        val rememberedFocus = state.focusedChildIdByParent[current.id]
        val restoredFocusId = rememberedFocus
            ?.takeIf { rememberedId -> children.any { child -> child.id == rememberedId } }
            ?: children.firstOrNull()?.id

        return CatalogNavigationContext(
            currentNode = current,
            breadcrumbs = state.path.map(::node),
            children = children,
            restoredFocusId = restoredFocusId
        )
    }

    /** Remembers one direct child as the focus target without changing hierarchy level. */
    fun focus(state: CatalogNavigationState, childNodeId: CatalogNodeId): CatalogNavigationState {
        validateStatePath(state.path)
        requireDirectChild(parentId = state.currentNodeId, childNodeId = childNodeId)
        return state.copy(
            focusedChildIdByParent = state.focusedChildIdByParent +
                (state.currentNodeId to childNodeId)
        )
    }

    /**
     * Enters a direct child container and remembers it as the parent's focus target. Channel leaves
     * must be handled by the feature (for example by opening Player) while keeping this state.
     */
    fun enter(state: CatalogNavigationState, childNodeId: CatalogNodeId): CatalogNavigationState {
        val focusedState = focus(state, childNodeId)
        val child = node(childNodeId)
        require(child.kind != CatalogNodeKind.CHANNEL) {
            "Channel nodes are leaves and cannot be entered as catalog containers"
        }
        return focusedState.copy(path = focusedState.path + childNodeId)
    }

    /**
     * Moves exactly one hierarchy level up. The container being left becomes the restored focus on
     * its parent, which gives TV Back navigation deterministic round-trip behavior.
     */
    fun back(state: CatalogNavigationState): CatalogNavigationState {
        validateStatePath(state.path)
        if (state.path.size == 1) return state

        val leavingNodeId = state.path.last()
        val parentNodeId = state.path[state.path.lastIndex - 1]
        return state.copy(
            path = state.path.dropLast(1),
            focusedChildIdByParent = state.focusedChildIdByParent +
                (parentNodeId to leavingNodeId)
        )
    }

    /**
     * Reconciles a previously saved checkpoint against a rebuilt catalog tree.
     *
     * The deepest still-valid breadcrumb is retained. Missing/stale focus targets are dropped. If
     * the old root disappeared entirely, [fallbackNodeId] is resolved to its complete current path.
     * This is intentionally based on canonical ids rather than legacy Room row ids.
     */
    fun restore(
        state: CatalogNavigationState,
        fallbackNodeId: CatalogNodeId
    ): CatalogNavigationState {
        val fallback = initial(fallbackNodeId)
        val restoredPath = mutableListOf<CatalogNodeId>()

        for ((index, nodeId) in state.path.withIndex()) {
            val current = nodesById[nodeId] ?: break
            val expectedParentId = restoredPath.lastOrNull()
            val pathLinkIsValid = if (index == 0) {
                current.parentId == null
            } else {
                expectedParentId != null && current.parentId == expectedParentId
            }
            if (!pathLinkIsValid) break
            restoredPath += nodeId
        }

        val resolvedPath = restoredPath.ifEmpty { fallback.path }
        val validFocus = state.focusedChildIdByParent.filter { (parentId, childId) ->
            childrenByParentId[parentId].orEmpty().any { child -> child.id == childId }
        }

        return CatalogNavigationState(
            path = resolvedPath,
            focusedChildIdByParent = validFocus
        )
    }

    private fun validateHierarchy() {
        nodesById.values.forEach { node ->
            val parentId = node.parentId ?: return@forEach
            require(parentId != node.id) { "Canonical catalog node cannot be its own parent: ${node.id}" }
            require(nodesById.containsKey(parentId)) {
                "Canonical catalog parent is missing for ${node.id}: $parentId"
            }
        }

        nodesById.values.forEach { start ->
            val visited = mutableSetOf<CatalogNodeId>()
            var current: CanonicalCatalogNode? = start
            while (current != null) {
                require(visited.add(current.id)) {
                    "Canonical catalog hierarchy contains a cycle at ${current.id}"
                }
                current = current.parentId?.let(nodesById::get)
            }
        }
    }

    private fun validateStatePath(path: List<CatalogNodeId>) {
        require(path.isNotEmpty()) { "Catalog navigation path must not be empty" }
        path.forEachIndexed { index, nodeId ->
            val current = node(nodeId)
            if (index == 0) {
                require(current.parentId == null) {
                    "Catalog navigation path must begin at a hierarchy root"
                }
            } else {
                val expectedParentId = path[index - 1]
                require(current.parentId == expectedParentId) {
                    "Catalog navigation path is disconnected at $nodeId"
                }
            }
        }
    }

    private fun ancestorPath(nodeId: CatalogNodeId): List<CatalogNodeId> {
        val reversed = mutableListOf<CatalogNodeId>()
        var current: CanonicalCatalogNode? = node(nodeId)
        while (current != null) {
            reversed += current.id
            current = current.parentId?.let(::node)
        }
        return reversed.asReversed()
    }

    private fun requireDirectChild(parentId: CatalogNodeId, childNodeId: CatalogNodeId) {
        val child = node(childNodeId)
        require(child.parentId == parentId) {
            "$childNodeId is not a direct child of $parentId"
        }
    }

    private fun node(nodeId: CatalogNodeId): CanonicalCatalogNode =
        requireNotNull(nodesById[nodeId]) { "Unknown canonical catalog node: $nodeId" }

    private companion object {
        val CHILD_ORDER = compareBy<CanonicalCatalogNode> { node -> node.order }
            .thenBy { node -> node.name.lowercase(Locale.ROOT) }
            .thenBy { node -> node.id.value }
    }
}
