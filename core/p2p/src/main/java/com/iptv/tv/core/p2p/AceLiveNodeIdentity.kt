package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec

/** Ephemeral Ed25519 identity used to sign the Ace BEP-10 application handshake. */
internal class AceLiveNodeIdentity private constructor(
    private val privateKey: EdDSAPrivateKey
) {
    val nodeId: ByteArray
        get() = privateKey.abyte.copyOf()

    fun signedExtendedHandshake(
        minPiece: Long,
        maxPiece: Long,
        timestamp: Long
    ): ByteArray {
        require(minPiece >= 0) { "minPiece must be non-negative" }
        require(maxPiece >= minPiece) { "maxPiece must cover minPiece" }
        require(timestamp >= 0) { "timestamp must be non-negative" }

        return signedExtendedHandshake(
            livePosition = livePosition(minPiece, maxPiece),
            timestamp = timestamp,
            includePeerExchange = true
        )
    }

    fun signedMetadataExtendedHandshake(timestamp: Long): ByteArray {
        require(timestamp >= 0) { "timestamp must be non-negative" }
        return signedExtendedHandshake(
            livePosition = null,
            timestamp = timestamp,
            includePeerExchange = false
        )
    }

    private fun signedExtendedHandshake(
        livePosition: AceBencodeValue.Dictionary?,
        timestamp: Long,
        includePeerExchange: Boolean
    ): ByteArray {
        val fields = linkedMapOf<String, AceBencodeValue>()
        fields["ace_metadata_version"] = AceBencodeValue.Integer(1)
        fields["asn"] = AceBencodeValue.Integer(0)
        fields["asn_country"] = AceBencodeValue.Bytes(byteArrayOf())
        fields["geoip_country"] = AceBencodeValue.Bytes(byteArrayOf())
        fields["lsp"] = AceBencodeValue.Integer(-1)
        fields["m"] = AceBencodeValue.Dictionary(
            buildMap {
                put("ut_metadata", AceBencodeValue.Integer(2))
                if (includePeerExchange) {
                    put("ut_pex", AceBencodeValue.Integer(ACE_LIVE_LOCAL_UT_PEX_MESSAGE_ID.toLong()))
                }
            }
        )
        livePosition?.let { fields["mi"] = it }
        fields["node_id"] = AceBencodeValue.Bytes(nodeId)
        fields["nt"] = AceBencodeValue.Integer(1)
        fields["p"] = AceBencodeValue.Integer(8621)
        fields["platform"] = AceBencodeValue.Integer(2)
        fields["pv"] = AceBencodeValue.Integer(2)
        fields["signature"] = AceBencodeValue.Bytes(ByteArray(SIGNATURE_BYTES))
        fields["stream_statuses"] = AceBencodeValue.Dictionary(emptyMap())
        fields["ts"] = AceBencodeValue.Integer(timestamp)
        fields["tt"] = AceBencodeValue.Bytes("bt".toByteArray(StandardCharsets.US_ASCII))
        fields["v"] = AceBencodeValue.Integer(3_021_100)

        val unsigned = AceBencodeValue.Dictionary(fields)
        val digest = MessageDigest.getInstance("SHA-256").digest(AceBencodeEncoder.encode(unsigned))
        val signer = EdDSAEngine(MessageDigest.getInstance("SHA-512"))
        signer.initSign(privateKey)
        signer.update(digest)
        val signature = signer.sign()
        require(signature.size == SIGNATURE_BYTES) { "Ed25519 signature has an invalid size" }
        fields["signature"] = AceBencodeValue.Bytes(signature)

        val payload = AceBencodeEncoder.encode(AceBencodeValue.Dictionary(fields))
        val bodyLength = 2 + payload.size
        return ByteBuffer.allocate(4 + bodyLength)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(bodyLength)
            .put(EXTENDED_MESSAGE_ID.toByte())
            .put(EXTENDED_HANDSHAKE_ID.toByte())
            .put(payload)
            .array()
    }

    private fun livePosition(minPiece: Long, maxPiece: Long): AceBencodeValue.Dictionary {
        val values = linkedMapOf<String, AceBencodeValue>()
        listOf(
            "distance_from_source" to 1L,
            "down_rate" to 0L,
            "download_window_end" to -1L,
            "is_accessible" to 0L,
            "live_window_size" to (maxPiece - minPiece + 1L),
            "lsp" to -1L,
            "mam" to -1L,
            "max_piece" to maxPiece,
            "min_piece" to minPiece,
            "peer_type" to 0L,
            "ping_from_source" to -1L,
            "position" to -1L,
            "time_from_source" to -1L,
            "top_session_up_rate" to 0L,
            "top_up_rate" to 0L,
            "up_rate" to 0L,
            "upload_rating" to 0L
        ).forEach { (name, value) -> values[name] = AceBencodeValue.Integer(value) }
        return AceBencodeValue.Dictionary(values)
    }

    companion object {
        private const val EXTENDED_MESSAGE_ID = 20
        private const val EXTENDED_HANDSHAKE_ID = 0
        private const val SIGNATURE_BYTES = 64
        private val RANDOM = SecureRandom()

        fun generate(): AceLiveNodeIdentity {
            val parameters = EdDSANamedCurveTable.ED_25519_CURVE_SPEC
            val seed = ByteArray(32).also(RANDOM::nextBytes)
            val privateKey = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, parameters))
            return AceLiveNodeIdentity(privateKey)
        }

        fun peerId(): ByteArray {
            val alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val suffix = CharArray(11) { alphabet[RANDOM.nextInt(alphabet.length)] }
            return ("R30------" + suffix.concatToString()).toByteArray(StandardCharsets.US_ASCII)
        }
    }
}
