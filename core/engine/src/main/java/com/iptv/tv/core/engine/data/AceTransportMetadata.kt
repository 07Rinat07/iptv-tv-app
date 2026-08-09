package com.iptv.tv.core.engine.data

/**
 * Structured transport metadata returned by Ace Engine `get_media_files`.
 *
 * Ace content ids, BitTorrent infohashes and live transport descriptors are distinct concepts.
 * Keeping the complete metadata prevents routing decisions from being based on a single recursively
 * discovered string in an otherwise richer response.
 */
data class AceTransportMetadata(
    val infoHash: String?,
    val mediaType: String?,
    val transportType: String?,
    val name: String?,
    val files: List<AceTransportFile>,
    val transportFileData: String?,
    val transportFileCacheKey: String?,
    val wrapperData: AceWrapperData?
) {
    val isLive: Boolean
        get() = mediaType == MEDIA_TYPE_LIVE || files.any { it.mediaType == MEDIA_TYPE_LIVE }

    /**
     * Verified 40-hex swarm identity returned for live metadata.
     *
     * A live file entry wins over the top-level hash so mixed VOD/live responses cannot route the
     * live peer session into another file's swarm. The top-level hash is used only when the response
     * itself is explicitly typed live and no live file entry carries a hash.
     *
     * This is intentionally separate from [embeddedBitTorrentInfoHash]: callers may convert it to
     * the Ace Live 20-byte peer/discovery swarm key, but must not route it through ordinary
     * libtorrent merely because both identities have the same hexadecimal width.
     */
    val liveSwarmInfoHash: String?
        get() {
            if (!isLive) return null
            val liveFileHash = files.firstOrNull {
                it.mediaType == MEDIA_TYPE_LIVE && it.infoHash != null
            }?.infoHash
            return liveFileHash ?: infoHash.takeIf { mediaType == MEDIA_TYPE_LIVE }
        }

    val embeddedBitTorrentInfoHash: String?
        get() {
            if (isLive) return null
            val effectiveTransport = transportType ?: files.firstNotNullOfOrNull { it.transportType }
            if (effectiveTransport != null && effectiveTransport != TRANSPORT_TYPE_BITTORRENT) return null
            return infoHash ?: files.firstNotNullOfOrNull { it.infoHash }
        }

    private companion object {
        const val MEDIA_TYPE_LIVE = "live"
        const val TRANSPORT_TYPE_BITTORRENT = "bt"
    }
}

data class AceTransportFile(
    val index: Int?,
    val infoHash: String?,
    val mediaType: String?,
    val transportType: String?,
    val filename: String?,
    val mime: String?,
    val size: Long?
)

data class AceWrapperData(
    val type: String?,
    val mime: String?,
    val data: String?
)
