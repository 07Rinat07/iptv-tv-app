package com.iptv.tv.core.p2p

sealed interface P2pSource {
    data class Magnet(val uri: String) : P2pSource
    data class InfoHash(val value: String) : P2pSource
    data class TorrentUrl(val url: String) : P2pSource
    data class LocalTorrentUri(val uri: String) : P2pSource
    data class AceContentId(val contentId: String) : P2pSource
}

data class P2pTorrentFile(
    val index: Int,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val mediaCandidate: Boolean
)

data class P2pTorrentMetadata(
    val name: String,
    val infoHashV1: String?,
    val totalSizeBytes: Long,
    val pieceLengthBytes: Int,
    val files: List<P2pTorrentFile>,
    val preferredFileIndex: Int?
)

data class P2pStreamDescriptor(
    val url: String,
    val file: P2pTorrentFile,
    val torrent: P2pTorrentMetadata
)

data class P2pEngineSnapshot(
    val running: Boolean,
    val downloadRateBytesPerSecond: Long,
    val uploadRateBytesPerSecond: Long,
    val dhtNodes: Long
)

sealed interface P2pResult<out T> {
    data class Success<T>(val data: T) : P2pResult<T>
    data class Error(val message: String, val cause: Throwable? = null) : P2pResult<Nothing>
}
