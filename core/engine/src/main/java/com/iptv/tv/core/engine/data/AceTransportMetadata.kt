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
