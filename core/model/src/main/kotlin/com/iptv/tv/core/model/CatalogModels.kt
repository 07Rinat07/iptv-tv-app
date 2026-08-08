package com.iptv.tv.core.model

import java.security.MessageDigest

enum class CatalogNodeKind {
    SOURCE,
    CATALOG,
    SUBCATALOG,
    PLAYLIST,
    GROUP,
    SUBGROUP,
    CHANNEL,
    VIRTUAL_VIEW
}

enum class CatalogOriginKind {
    USER_IMPORT,
    READY_CATALOG,
    SCANNER_IMPORT,
    PROVIDER,
    LOCAL,
    P2P,
    SYSTEM
}

@JvmInline
value class CatalogNodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "Catalog node id must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Stable source provenance shared by canonical catalog nodes.
 *
 * [sourceKey] must be a canonical, non-secret identifier supplied by a source adapter.
 * Raw passwords, tokens and credential-bearing URLs must never be used as a source key.
 */
data class CatalogProvenance(
    val origin: CatalogOriginKind,
    val sourceKey: String,
    val sourceType: PlaylistSourceType? = null
) {
    init {
        require(sourceKey.isNotBlank()) { "Catalog provenance sourceKey must not be blank" }
    }
}

/**
 * Minimal canonical hierarchy contract. Feature modules may attach their own payloads while
 * retaining the same stable identity, parent relationship, ordering and source provenance.
 */
data class CanonicalCatalogNode(
    val id: CatalogNodeId,
    val kind: CatalogNodeKind,
    val name: String,
    val parentId: CatalogNodeId?,
    val order: Int,
    val provenance: CatalogProvenance
) {
    init {
        require(name.isNotBlank()) { "Catalog node name must not be blank" }
        require(order >= 0) { "Catalog node order must not be negative" }
    }
}

/**
 * Produces deterministic hierarchy ids without depending on Room auto-generated ids or labels.
 * Source adapters own normalization of [stableKey]; display-name changes therefore do not change
 * identity when the adapter keeps the same stable key.
 */
object CatalogNodeIdFactory {
    private const val SCHEMA_VERSION = "v1"

    fun root(
        kind: CatalogNodeKind,
        provenance: CatalogProvenance,
        stableKey: String
    ): CatalogNodeId {
        requireStableKey(stableKey)
        return create(
            kind = kind,
            segments = listOf(
                provenance.origin.name,
                provenance.sourceKey,
                provenance.sourceType?.name.orEmpty(),
                stableKey
            )
        )
    }

    fun child(
        kind: CatalogNodeKind,
        parentId: CatalogNodeId,
        stableKey: String
    ): CatalogNodeId {
        requireStableKey(stableKey)
        return create(
            kind = kind,
            segments = listOf(parentId.value, stableKey)
        )
    }

    private fun create(kind: CatalogNodeKind, segments: List<String>): CatalogNodeId {
        val encoded = buildString {
            segments.forEach { segment ->
                append(segment.length)
                append(':')
                append(segment)
                append('|')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(encoded.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return CatalogNodeId("catalog:$SCHEMA_VERSION:${kind.name.lowercase()}:$digest")
    }

    private fun requireStableKey(stableKey: String) {
        require(stableKey.isNotBlank()) { "Catalog stable key must not be blank" }
    }
}
