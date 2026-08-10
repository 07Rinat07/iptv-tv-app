package com.iptv.tv.core.model

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * Transitional adapter from the current Playlist/Channel storage model into the canonical catalog
 * hierarchy. It deliberately keeps legacy Room row ids only as payload lookups; canonical node ids
 * are derived from source provenance and stable channel identity instead.
 *
 * Source keys are opaque SHA-256 fingerprints. Password/token-like query values and URI user-info
 * are excluded before hashing so credential-bearing URLs never become [CatalogProvenance.sourceKey].
 */
object LegacyPlaylistCatalogAdapter {
    fun build(playlist: Playlist, channels: List<Channel>): CanonicalPlaylistTree {
        val sourceMaterial = canonicalSourceMaterial(playlist.source)
        val provenance = CatalogProvenance(
            origin = playlist.catalogOrigin,
            sourceKey = "legacy:${digest("${playlist.catalogOrigin.name}|${playlist.sourceType.name}|$sourceMaterial")}",
            sourceType = playlist.sourceType
        )
        val sourceNodeId = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.SOURCE,
            provenance = provenance,
            stableKey = "source"
        )
        val playlistNodeId = CatalogNodeIdFactory.child(
            kind = CatalogNodeKind.PLAYLIST,
            parentId = sourceNodeId,
            stableKey = playlistStableKey(playlist, channels, sourceMaterial)
        )

        val nodes = linkedMapOf<CatalogNodeId, CanonicalCatalogNode>()
        nodes[sourceNodeId] = CanonicalCatalogNode(
            id = sourceNodeId,
            kind = CatalogNodeKind.SOURCE,
            name = sourceLabel(playlist.catalogOrigin),
            parentId = null,
            order = 0,
            provenance = provenance
        )
        nodes[playlistNodeId] = CanonicalCatalogNode(
            id = playlistNodeId,
            kind = CatalogNodeKind.PLAYLIST,
            name = playlist.name.ifBlank { "Playlist" },
            parentId = sourceNodeId,
            order = 0,
            provenance = provenance
        )

        val channelNodeIdByChannelId = linkedMapOf<Long, CatalogNodeId>()
        val channelVariantIdsByNodeId = linkedMapOf<CatalogNodeId, MutableList<Long>>()
        val grouped = channels
            .sortedWith(compareBy<Channel> { it.orderIndex }.thenBy { it.id })
            .groupBy { it.group?.trim().orEmpty() }

        grouped.forEach { (groupName, groupChannels) ->
            val parentId = if (groupName.isBlank()) {
                playlistNodeId
            } else {
                val groupNodeId = CatalogNodeIdFactory.child(
                    kind = CatalogNodeKind.GROUP,
                    parentId = playlistNodeId,
                    stableKey = normalizeHierarchyKey(groupName)
                )
                nodes.putIfAbsent(
                    groupNodeId,
                    CanonicalCatalogNode(
                        id = groupNodeId,
                        kind = CatalogNodeKind.GROUP,
                        name = groupName,
                        parentId = playlistNodeId,
                        order = groupChannels.minOfOrNull { it.orderIndex.coerceAtLeast(0) } ?: 0,
                        provenance = provenance
                    )
                )
                groupNodeId
            }

            groupChannels.forEach { channel ->
                val channelNodeId = CatalogNodeIdFactory.child(
                    kind = CatalogNodeKind.CHANNEL,
                    parentId = parentId,
                    stableKey = ChannelStableIdentity.key(
                        tvgId = channel.tvgId,
                        name = channel.name,
                        streamUrl = channel.streamUrl
                    )
                )
                channelNodeIdByChannelId[channel.id] = channelNodeId
                channelVariantIdsByNodeId.getOrPut(channelNodeId) { mutableListOf() }.add(channel.id)
                nodes.putIfAbsent(
                    channelNodeId,
                    CanonicalCatalogNode(
                        id = channelNodeId,
                        kind = CatalogNodeKind.CHANNEL,
                        name = channel.name.ifBlank { "Channel" },
                        parentId = parentId,
                        order = channel.orderIndex.coerceAtLeast(0),
                        provenance = provenance
                    )
                )
            }
        }

        return CanonicalPlaylistTree(
            sourceNodeId = sourceNodeId,
            playlistNodeId = playlistNodeId,
            nodes = nodes.values.toList(),
            channelNodeIdByChannelId = channelNodeIdByChannelId.toMap(),
            channelVariantIdsByNodeId = channelVariantIdsByNodeId.mapValues { (_, ids) -> ids.toList() }
        )
    }

    private fun playlistStableKey(
        playlist: Playlist,
        channels: List<Channel>,
        sourceMaterial: String
    ): String {
        val genericSource = playlist.source.isBlank() || playlist.source.trim().equals("inline", ignoreCase = true)
        val fallbackContentKey = if (genericSource) {
            channels
                .map { channel -> ChannelStableIdentity.key(channel.tvgId, channel.name, channel.streamUrl) }
                .distinct()
                .sorted()
                .joinToString(separator = "|")
        } else {
            ""
        }
        return "playlist:${digest("${playlist.sourceType.name}|$sourceMaterial|$fallbackContentKey")}" 
    }

    private fun sourceLabel(origin: CatalogOriginKind): String = when (origin) {
        CatalogOriginKind.USER_IMPORT -> "User imports"
        CatalogOriginKind.READY_CATALOG -> "Ready catalog"
        CatalogOriginKind.SCANNER_IMPORT -> "Scanner"
        CatalogOriginKind.PROVIDER -> "Provider"
        CatalogOriginKind.LOCAL -> "Local"
        CatalogOriginKind.P2P -> "P2P"
        CatalogOriginKind.SYSTEM -> "System"
    }

    private fun normalizeHierarchyKey(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""[\p{Punct}\s]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
        .ifBlank { "group" }

    private fun canonicalSourceMaterial(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "empty"

        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme.isNullOrBlank()) return@runCatching normalizePlainSource(trimmed)

            buildString {
                append(scheme)
                append(':')
                if (!uri.host.isNullOrBlank()) {
                    append("//")
                    append(uri.host.lowercase(Locale.ROOT))
                    if (uri.port >= 0) append(":${uri.port}")
                }
                append(uri.rawPath.orEmpty().trimEnd('/'))
                canonicalSafeQuery(uri.rawQuery).takeIf { it.isNotBlank() }?.let { query ->
                    append('?')
                    append(query)
                }
            }
        }.getOrElse {
            normalizePlainSource(trimmed)
        }
    }

    private fun canonicalSafeQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrBlank()) return ""
        return rawQuery
            .split('&')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=').trim()
                if (key.isBlank()) return@mapNotNull null
                val value = pair.substringAfter('=', missingDelimiterValue = "").trim()
                val normalizedKey = key.lowercase(Locale.ROOT)
                when {
                    normalizedKey in SECRET_QUERY_KEYS -> "$normalizedKey=<redacted>"
                    normalizedKey in SAFE_ID_QUERY_KEYS && value.isNotBlank() -> "$normalizedKey=$value"
                    else -> normalizedKey
                }
            }
            .sorted()
            .joinToString("&")
    }

    private fun normalizePlainSource(value: String): String = value
        .replace('\\', '/')
        .trim()
        .lowercase(Locale.ROOT)

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val SECRET_QUERY_KEYS = setOf(
        "password",
        "pass",
        "pwd",
        "token",
        "api_key",
        "apikey",
        "access_token",
        "auth",
        "authorization",
        "mac"
    )

    private val SAFE_ID_QUERY_KEYS = setOf(
        "username",
        "user",
        "id",
        "playlist",
        "repo",
        "repository",
        "path",
        "action"
    )
}

data class CanonicalPlaylistTree(
    val sourceNodeId: CatalogNodeId,
    val playlistNodeId: CatalogNodeId,
    val nodes: List<CanonicalCatalogNode>,
    /** Legacy Room row id -> canonical channel node id; row id is never part of canonical identity. */
    val channelNodeIdByChannelId: Map<Long, CatalogNodeId>,
    /** Keeps concrete source variants when several legacy rows resolve to one logical channel node. */
    val channelVariantIdsByNodeId: Map<CatalogNodeId, List<Long>>
)
