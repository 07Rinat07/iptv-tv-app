package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Public fields required to join and validate one Ace Live swarm. */
data class AceResolvedLiveTransport(
    val name: String,
    val geometry: AceLiveTransportGeometry,
    val swarmKey: AceLiveSwarmKey,
    val trackers: List<String>,
    val publicKeyDer: ByteArray?,
    val authMethod: String
)

internal object AceTransportDescriptorDecoder {
    fun decodeLive(bytes: ByteArray): P2pResult<AceResolvedLiveTransport> = runCatching {
        require(bytes.size in MIN_TRANSPORT_BYTES..MAX_TRANSPORT_BYTES) {
            "Ace transport file has an invalid size"
        }
        require(bytes.copyOfRange(0, TRANSPORT_MAGIC.size).contentEquals(TRANSPORT_MAGIC)) {
            "Ace transport magic is invalid"
        }
        require(bytes[18] == 0.toByte() && bytes[19] == 2.toByte()) {
            "Ace transport version is unsupported"
        }

        val encrypted = bytes.copyOfRange(TRANSPORT_HEADER_BYTES, bytes.size)
        require(encrypted.isNotEmpty() && encrypted.size % AES_BLOCK_BYTES == 0) {
            "Ace transport body has an invalid size"
        }

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(TRANSPORT_KEY, "AES"),
            IvParameterSpec(TRANSPORT_IV)
        )
        val plaintext = cipher.doFinal(encrypted)
        val descriptor = AceBoundedBencodeParser(
            data = plaintext,
            maxDepth = 12,
            maxContainerEntries = 512,
            maxStringBytes = MAX_DESCRIPTOR_STRING_BYTES,
            maxTotalNodes = 2_048
        ).parseRootDictionary()

        require(descriptor.values["pieces"] == null) {
            "Ace transport describes VOD rather than a live channel"
        }

        val pieceLength = descriptor.positiveInt("piece_length")
        val chunkLength = descriptor.positiveInt("chunk_length")
        val bitrate = descriptor.positiveInt("bitrate")
        require(pieceLength <= MAX_PIECE_BYTES) { "Ace live piece length exceeds the safety limit" }
        require(chunkLength == EXPECTED_CHUNK_BYTES.toLong()) {
            "Ace live chunk length is unsupported"
        }
        require(pieceLength % chunkLength == 0L) {
            "Ace live piece geometry is not chunk-aligned"
        }
        require(pieceLength / chunkLength <= MAX_CHUNKS_PER_PIECE) {
            "Ace live piece has too many chunks"
        }

        val authMethod = descriptor.requiredBytes("authmethod")
            .toString(StandardCharsets.US_ASCII)
        require(authMethod.equals("RSA", ignoreCase = true)) {
            "Ace live authentication method is unsupported"
        }
        val publicKey = descriptor.requiredBytes("pubkey")
        require(publicKey.size in MIN_PUBLIC_KEY_BYTES..MAX_PUBLIC_KEY_BYTES) {
            "Ace live public key has an invalid size"
        }

        val trackers = (descriptor.values["trackers"] as? AceBencodeValue.ListValue)
            ?.values
            .orEmpty()
            .asSequence()
            .mapNotNull { value -> (value as? AceBencodeValue.Bytes)?.value }
            .map { value -> value.toString(StandardCharsets.UTF_8).trim() }
            .filter(String::isNotBlank)
            .filter { value -> value.length <= MAX_TRACKER_LENGTH }
            .distinct()
            .take(MAX_TRACKERS)
            .toList()

        val infoHashInput = AceBencodeValue.ListValue(
            INFO_HASH_FIELDS.map { field ->
                val value = descriptor.values[field]
                    ?: error("Ace transport is missing the $field field")
                AceBencodeValue.ListValue(
                    listOf(
                        AceBencodeValue.Bytes(field.toByteArray(StandardCharsets.US_ASCII)),
                        value
                    )
                )
            }
        )
        val swarmKey = AceLiveSwarmKey.fromBytes(
            MessageDigest.getInstance("SHA-1").digest(AceBencodeEncoder.encode(infoHashInput))
        )

        AceResolvedLiveTransport(
            name = descriptor.optionalText("name", MAX_NAME_BYTES).orEmpty(),
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = pieceLength.toInt(),
                chunkLengthBytes = chunkLength.toInt(),
                bitrate = bitrate
            ),
            swarmKey = swarmKey,
            trackers = trackers,
            publicKeyDer = publicKey.copyOf(),
            authMethod = authMethod
        )
    }.fold(
        onSuccess = { P2pResult.Success(it) },
        onFailure = { error ->
            P2pResult.Error(
                error.message ?: "Ace transport descriptor could not be decoded",
                error
            )
        }
    )

    private fun AceBencodeValue.Dictionary.positiveInt(name: String): Long {
        val value = (values[name] as? AceBencodeValue.Integer)?.value
        require(value != null && value > 0) { "Ace transport field $name is missing or invalid" }
        return value
    }

    private fun AceBencodeValue.Dictionary.requiredBytes(name: String): ByteArray {
        val value = (values[name] as? AceBencodeValue.Bytes)?.value
        require(value != null && value.isNotEmpty()) { "Ace transport field $name is missing or invalid" }
        return value
    }

    private fun AceBencodeValue.Dictionary.optionalText(name: String, maxBytes: Int): String? {
        val bytes = (values[name] as? AceBencodeValue.Bytes)?.value ?: return null
        if (bytes.isEmpty()) return null
        return bytes.copyOf(minOf(bytes.size, maxBytes))
            .toString(StandardCharsets.UTF_8)
            .trim()
            .takeIf(String::isNotBlank)
    }

    private val TRANSPORT_MAGIC = "AceStreamTransport".toByteArray(StandardCharsets.US_ASCII)
    private val TRANSPORT_KEY = byteArrayOf(
        0xa5.toByte(), 0x0c, 0x4e, 0x33, 0xa2.toByte(), 0xf4.toByte(), 0x8c.toByte(), 0xc5.toByte(),
        0x0c, 0xe2.toByte(), 0x75, 0xc9.toByte(), 0xff.toByte(), 0x3a, 0x31, 0xbf.toByte()
    )
    private val TRANSPORT_IV = byteArrayOf(
        0x74, 0xe9.toByte(), 0xcd.toByte(), 0xd6.toByte(), 0x39, 0x1b, 0xcb.toByte(), 0xd5.toByte(),
        0x65, 0xf9.toByte(), 0x95.toByte(), 0x03, 0x31, 0x33, 0x29, 0xa3.toByte()
    )
    private val INFO_HASH_FIELDS = listOf(
        "name",
        "authmethod",
        "pubkey",
        "piece_length",
        "chunk_length",
        "bitrate"
    )

    private const val TRANSPORT_HEADER_BYTES = 20
    private const val AES_BLOCK_BYTES = 16
    private const val MIN_TRANSPORT_BYTES = TRANSPORT_HEADER_BYTES + AES_BLOCK_BYTES
    private const val MAX_TRANSPORT_BYTES = 1024 * 1024
    private const val MAX_DESCRIPTOR_STRING_BYTES = 512 * 1024
    private const val MAX_PIECE_BYTES = 8L * 1024L * 1024L
    private const val EXPECTED_CHUNK_BYTES = 16 * 1024
    private const val MAX_CHUNKS_PER_PIECE = 65_536L
    private const val MIN_PUBLIC_KEY_BYTES = 64
    private const val MAX_PUBLIC_KEY_BYTES = 4 * 1024
    private const val MAX_TRACKERS = 32
    private const val MAX_TRACKER_LENGTH = 256
    private const val MAX_NAME_BYTES = 256
}
