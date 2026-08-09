package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Verified geometry fields carried by Ace Live transport descriptors.
 *
 * This model intentionally starts after descriptor decoding. It does not embed the proprietary
 * transport-file decryption secret and makes no attempt to guess encrypted `.acelive` bodies.
 * `bitrate` is kept as the raw positive protocol value until its exact unit semantics are verified
 * well enough to make scheduling calculations from it.
 */
data class AceLiveTransportGeometry(
    val pieceLengthBytes: Int,
    val chunkLengthBytes: Int,
    val bitrate: Long
) {
    init {
        require(pieceLengthBytes > 0) { "pieceLengthBytes must be positive" }
        require(chunkLengthBytes > 0) { "chunkLengthBytes must be positive" }
        require(bitrate > 0) { "bitrate must be positive" }
    }

    val chunksPerPiece: Int
        get() = ceil(pieceLengthBytes.toDouble() / chunkLengthBytes.toDouble()).toInt()
}

/**
 * Public/live descriptor metadata that can be consumed by future peer-wire and scheduling code.
 *
 * Public-key material is represented as base64 text so data-class equality remains value based.
 * Diagnostics expose only its presence, never the key or tracker values themselves.
 */
data class AceLiveTransportMetadata(
    val geometry: AceLiveTransportGeometry,
    val authMethod: String?,
    val publicKeyDerBase64: String?,
    val trackers: List<String>,
    val allowPublicTrackers: Boolean?,
    val permanent: Boolean?
) {
    fun diagnosticSummary(): String = buildString {
        append("Ace live descriptor: piece_bytes=").append(geometry.pieceLengthBytes)
        append(" chunk_bytes=").append(geometry.chunkLengthBytes)
        append(" chunks_per_piece=").append(geometry.chunksPerPiece)
        append(" bitrate=").append(geometry.bitrate)
        append(" auth=").append(authMethod?.takeIf { it.isNotBlank() } ?: "unknown")
        append(" pubkey=").append(if (publicKeyDerBase64.isNullOrBlank()) "absent" else "present")
        append(" trackers=").append(trackers.count { it.isNotBlank() })
        allowPublicTrackers?.let { append(" public_trackers=").append(it) }
        permanent?.let { append(" permanent=").append(it) }
    }
}

/**
 * Ace Live peer-wire piece header codec.
 *
 * The verified header is exactly eight bytes containing an IEEE-754 f64 Unix timestamp in
 * big-endian byte order. Every chunk of the same live piece carries the same timestamp.
 */
object AceLivePieceHeaderCodec {
    const val HEADER_SIZE_BYTES: Int = 8

    fun encodeUnixSeconds(unixSeconds: Double): ByteArray {
        require(unixSeconds.isFinite() && unixSeconds >= 0.0) {
            "Ace live piece timestamp must be a finite non-negative Unix time"
        }
        return ByteBuffer.allocate(HEADER_SIZE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putDouble(unixSeconds)
            .array()
    }

    fun decodeUnixSeconds(header: ByteArray): Double? {
        if (header.size != HEADER_SIZE_BYTES) return null
        val seconds = ByteBuffer.wrap(header)
            .order(ByteOrder.BIG_ENDIAN)
            .double
        return seconds.takeIf { it.isFinite() && it >= 0.0 }
    }
}
