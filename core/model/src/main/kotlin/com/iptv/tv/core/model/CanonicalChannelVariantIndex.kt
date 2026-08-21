package com.iptv.tv.core.model

/** One immutable legacy playlist snapshot used to build cross-source catalog views. */
data class CanonicalPlaylistSnapshot(
    val playlist: Playlist,
    val channels: List<Channel>
)

/**
 * One concrete source variant of a logical channel.
 *
 * The legacy row id is retained only as a lookup handle. Stable catalog/source/playlist identity
 * remains canonical and provenance is kept so aggregate views can always navigate back to origin.
 */
data class CanonicalChannelVariant(
    val legacyChannelId: Long,
    val channelNodeId: CatalogNodeId,
    val playlistNodeId: CatalogNodeId,
    val sourceNodeId: CatalogNodeId,
    val provenance: CatalogProvenance
)

/** All concrete source variants known for one provenance-agnostic logical channel identity. */
data class CanonicalLogicalChannelVariants(
    val logicalKey: String,
    val variants: List<CanonicalChannelVariant>
) {
    init {
        require(logicalKey.isNotBlank()) { "Logical channel key must not be blank" }
        require(variants.isNotEmpty()) { "Logical channel variants must not be empty" }
    }
}

/**
 * Pure cross-source aggregation for future Unified Favorites and virtual catalog views.
 *
 * Existing canonical node ids remain parent-scoped: the same logical channel under two sources is
 * still represented by two discoverable catalog nodes. This index only links those concrete nodes
 * through [ChannelStableIdentity], so aggregation never destroys their original provenance.
 */
object CanonicalChannelVariantIndex {
    fun build(snapshots: List<CanonicalPlaylistSnapshot>): List<CanonicalLogicalChannelVariants> {
        val byLogicalKey = linkedMapOf<String, MutableList<CanonicalChannelVariant>>()

        snapshots.forEach { snapshot ->
            val tree = LegacyPlaylistCatalogAdapter.build(
                playlist = snapshot.playlist,
                channels = snapshot.channels
            )
            val provenance = tree.nodes
                .first { node -> node.id == tree.sourceNodeId }
                .provenance

            snapshot.channels.forEach { channel ->
                val channelNodeId = tree.channelNodeIdByChannelId[channel.id]
                    ?: return@forEach
                val logicalKey = ChannelStableIdentity.key(
                    tvgId = channel.tvgId,
                    name = channel.name,
                    streamUrl = channel.streamUrl
                )
                byLogicalKey.getOrPut(logicalKey) { mutableListOf() }.add(
                    CanonicalChannelVariant(
                        legacyChannelId = channel.id,
                        channelNodeId = channelNodeId,
                        playlistNodeId = tree.playlistNodeId,
                        sourceNodeId = tree.sourceNodeId,
                        provenance = provenance
                    )
                )
            }
        }

        return byLogicalKey
            .map { (logicalKey, variants) ->
                CanonicalLogicalChannelVariants(
                    logicalKey = logicalKey,
                    variants = variants
                        .distinctBy { variant ->
                            VariantIdentity(
                                sourceNodeId = variant.sourceNodeId,
                                playlistNodeId = variant.playlistNodeId,
                                legacyChannelId = variant.legacyChannelId
                            )
                        }
                        .sortedWith(
                            compareBy<CanonicalChannelVariant> { it.provenance.origin.name }
                                .thenBy { it.provenance.sourceKey }
                                .thenBy { it.playlistNodeId.value }
                                .thenBy { it.channelNodeId.value }
                                .thenBy { it.legacyChannelId }
                        )
                )
            }
            .sortedBy(CanonicalLogicalChannelVariants::logicalKey)
    }

    private data class VariantIdentity(
        val sourceNodeId: CatalogNodeId,
        val playlistNodeId: CatalogNodeId,
        val legacyChannelId: Long
    )
}
