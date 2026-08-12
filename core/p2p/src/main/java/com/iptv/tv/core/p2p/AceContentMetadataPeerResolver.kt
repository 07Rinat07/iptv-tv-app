package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Resolves an Ace content ID through its public metadata swarm and BEP-9 `ut_metadata`. */
class AceContentMetadataPeerResolver(
    private val transportFactory: AceLiveTcpTransportFactory = JvmAceLiveTcpTransportFactory(),
    private val discoverPeers: suspend (AceLiveSwarmKey, ByteArray, Int) -> List<AceLiveTcpPeerEndpoint> =
        ::discoverDefaultMetadataPeers
) {
    suspend fun resolve(contentId: String): P2pResult<AceResolvedLiveTransport> {
        val swarmKey = AceLiveSwarmKey.parseHex(contentId)
            ?: return P2pResult.Error("Ace content ID must contain exactly 40 hexadecimal characters")
        val peerId = AceLiveNodeIdentity.peerId()
        val identity = AceLiveNodeIdentity.generate()

        return try {
            AceLiveAnnouncePortLease().use { announceLease ->
                val peers = discoverPeers(swarmKey, peerId, announceLease.port)
                    .distinctBy { endpoint -> "${endpoint.host}:${endpoint.port}" }
                    .shuffled()
                    .take(MAX_METADATA_PEERS)
                if (peers.isEmpty()) {
                    return@use P2pResult.Error("Ace metadata swarm returned no peer candidates")
                }

                firstSuccessfulP2p(
                    items = peers,
                    maxConcurrency = MAX_CONCURRENT_METADATA_PEERS,
                    failureMessage = "No usable Ace metadata peer among ${peers.size} candidates"
                ) { endpoint ->
                    fetchFromPeer(endpoint, swarmKey, peerId, identity)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            P2pResult.Error(
                error.message ?: "Ace metadata swarm resolution failed",
                error
            )
        }
    }

    internal suspend fun fetchFromPeer(
        endpoint: AceLiveTcpPeerEndpoint,
        contentId: AceLiveSwarmKey,
        peerId: ByteArray,
        identity: AceLiveNodeIdentity
    ): P2pResult<AceResolvedLiveTransport> {
        var transport: AceLiveTcpTransport? = null
        return try {
            currentCoroutineContext().ensureActive()
            transport = transportFactory.connect(endpoint, METADATA_CONNECTION_POLICY)
            val socket = requireNotNull(transport)
            val handshakeCodec = AceLivePeerHandshakeCodec()
            socket.write(handshakeCodec.encode(contentId.toByteArray(), peerId))
            val peerHandshake = readExactly(socket, AceLivePeerHandshakeCodec.HANDSHAKE_BYTES)
            require(
                handshakeCodec.decode(peerHandshake, contentId.toByteArray())
                    is AceLivePeerHandshakeDecodeResult.Decoded
            ) { "Ace metadata peer rejected the outer handshake" }

            socket.write(
                identity.signedMetadataExtendedHandshake(System.currentTimeMillis() / 1000L)
            )
            val frames = PeerFrameReader(socket)
            val metadata = readMetadataParameters(frames)
            val transportBytes = fetchMetadata(
                frames = frames,
                transport = socket,
                peerMetadataExtensionId = metadata.extensionId,
                metadataSize = metadata.sizeBytes
            )
            AceTransportDescriptorDecoder.decodeLive(transportBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            P2pResult.Error(
                error.message ?: "Ace metadata peer did not return a transport descriptor",
                error
            )
        } finally {
            transport?.close()
        }
    }

    private suspend fun readMetadataParameters(frames: PeerFrameReader): MetadataParameters {
        repeat(MAX_HANDSHAKE_FRAMES) {
            val message = frames.next()
            if (message !is AceLivePeerWireMessage.Unknown || message.id != EXTENDED_MESSAGE_ID) {
                return@repeat
            }
            if (message.payload.firstOrNull()?.toInt() != EXTENDED_HANDSHAKE_ID) {
                return@repeat
            }
            val dictionary = AceBoundedBencodeParser(
                data = message.payload.copyOfRange(1, message.payload.size),
                maxStringBytes = MAX_METADATA_BYTES
            ).parseRootDictionary()
            val extensionMap = dictionary.values["m"] as? AceBencodeValue.Dictionary
                ?: error("Ace metadata peer did not advertise extension mappings")
            val extensionId = (extensionMap.values["ut_metadata"] as? AceBencodeValue.Integer)
                ?.value
                ?.toInt()
                ?: error("Ace metadata peer did not advertise ut_metadata")
            require(extensionId in 1..255) { "Ace metadata peer advertised an invalid extension id" }
            val size = (dictionary.values["metadata_size"] as? AceBencodeValue.Integer)?.value
                ?: error("Ace metadata peer did not advertise metadata_size")
            require(size in 1..MAX_METADATA_BYTES.toLong()) {
                "Ace metadata peer advertised an invalid metadata size"
            }
            return MetadataParameters(extensionId, size.toInt())
        }
        error("Ace metadata peer did not send an extended handshake")
    }

    private suspend fun fetchMetadata(
        frames: PeerFrameReader,
        transport: AceLiveTcpTransport,
        peerMetadataExtensionId: Int,
        metadataSize: Int
    ): ByteArray {
        val pieceCount = (metadataSize + METADATA_BLOCK_BYTES - 1) / METADATA_BLOCK_BYTES
        repeat(pieceCount) { piece ->
            transport.write(encodeMetadataRequest(peerMetadataExtensionId, piece))
        }

        val output = ByteArray(metadataSize)
        val received = BooleanArray(pieceCount)
        var remaining = pieceCount
        var framesRead = 0
        while (remaining > 0 && framesRead < pieceCount * MAX_FRAMES_PER_METADATA_PIECE + 32) {
            framesRead += 1
            val message = frames.next()
            if (message !is AceLivePeerWireMessage.Unknown || message.id != EXTENDED_MESSAGE_ID) {
                continue
            }
            if ((message.payload.firstOrNull()?.toInt() ?: -1) and 0xff != OUR_METADATA_EXTENSION_ID) {
                continue
            }

            val encodedAndData = message.payload.copyOfRange(1, message.payload.size)
            val (header, consumedBytes) = AceBoundedBencodeParser(
                data = encodedAndData,
                maxStringBytes = MAX_METADATA_BYTES
            ).parsePrefixRootDictionary()
            val messageType = (header.values["msg_type"] as? AceBencodeValue.Integer)?.value
                ?: continue
            val piece = (header.values["piece"] as? AceBencodeValue.Integer)?.value?.toInt()
                ?: continue
            if (messageType == METADATA_REJECT) error("Ace metadata peer rejected a metadata piece")
            if (messageType != METADATA_DATA || piece !in 0 until pieceCount || received[piece]) {
                continue
            }
            (header.values["total_size"] as? AceBencodeValue.Integer)?.value?.let { total ->
                require(total == metadataSize.toLong()) { "Ace metadata peer changed metadata_size" }
            }

            val offset = piece * METADATA_BLOCK_BYTES
            val expected = minOf(METADATA_BLOCK_BYTES, metadataSize - offset)
            val data = encodedAndData.copyOfRange(consumedBytes, encodedAndData.size)
            require(data.size == expected) { "Ace metadata peer returned a partial metadata piece" }
            data.copyInto(output, offset)
            received[piece] = true
            remaining -= 1
        }
        require(remaining == 0) { "Ace metadata peer did not return every metadata piece" }
        return output
    }

    private fun encodeMetadataRequest(extensionId: Int, piece: Int): ByteArray {
        val payload = AceBencodeEncoder.encode(
            AceBencodeValue.Dictionary(
                mapOf(
                    "msg_type" to AceBencodeValue.Integer(METADATA_REQUEST),
                    "piece" to AceBencodeValue.Integer(piece.toLong())
                )
            )
        )
        return ByteBuffer.allocate(6 + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(2 + payload.size)
            .put(EXTENDED_MESSAGE_ID.toByte())
            .put(extensionId.toByte())
            .put(payload)
            .array()
    }

    private suspend fun readExactly(transport: AceLiveTcpTransport, size: Int): ByteArray {
        val output = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val target = ByteArray(size - offset)
            val count = transport.read(target)
            require(count > 0) { "Ace metadata peer timed out during handshake" }
            target.copyInto(output, offset, 0, count)
            offset += count
        }
        return output
    }

    private class PeerFrameReader(private val transport: AceLiveTcpTransport) {
        private val codec = AceLivePeerWireCodec(MAX_METADATA_FRAME_BYTES)
        private var pending = byteArrayOf()

        suspend fun next(): AceLivePeerWireMessage {
            while (true) {
                when (val decoded = codec.decodeNext(pending)) {
                    is AceLivePeerFrameDecodeResult.Decoded -> {
                        pending = pending.copyOfRange(decoded.consumedBytes, pending.size)
                        return decoded.message
                    }
                    is AceLivePeerFrameDecodeResult.Rejected -> error("Ace metadata peer frame is too large")
                    is AceLivePeerFrameDecodeResult.NeedMoreData -> {
                        val chunk = ByteArray(READ_BUFFER_BYTES)
                        val count = transport.read(chunk)
                        require(count > 0) { "Ace metadata peer timed out while sending metadata" }
                        require(pending.size + count <= MAX_METADATA_FRAME_BYTES + 4) {
                            "Ace metadata peer buffered frame exceeds the size limit"
                        }
                        pending += chunk.copyOf(count)
                    }
                }
            }
        }
    }

    private data class MetadataParameters(val extensionId: Int, val sizeBytes: Int)

    private companion object {
        val METADATA_CONNECTION_POLICY = AceLiveTcpConnectionPolicy(
            connectTimeoutMillis = 4_000,
            readTimeoutMillis = 8_000,
            handshakeTimeoutMillis = 8_000,
            writeTimeoutMillis = 4_000,
            maxConcurrentPeers = 1,
            maxReconnectAttempts = 0
        )
        const val EXTENDED_MESSAGE_ID = 20
        const val EXTENDED_HANDSHAKE_ID = 0
        const val OUR_METADATA_EXTENSION_ID = 2
        const val METADATA_REQUEST = 0L
        const val METADATA_DATA = 1L
        const val METADATA_REJECT = 2L
        const val METADATA_BLOCK_BYTES = 16 * 1024
        const val MAX_METADATA_BYTES = 1024 * 1024
        const val MAX_METADATA_FRAME_BYTES = MAX_METADATA_BYTES + 64 * 1024
        const val MAX_METADATA_PEERS = 24
        const val MAX_CONCURRENT_METADATA_PEERS = 4
        const val MAX_HANDSHAKE_FRAMES = 32
        const val MAX_FRAMES_PER_METADATA_PIECE = 4
        const val READ_BUFFER_BYTES = 64 * 1024
    }
}

private suspend fun discoverDefaultMetadataPeers(
    swarmKey: AceLiveSwarmKey,
    peerId: ByteArray,
    announcePort: Int
): List<AceLiveTcpPeerEndpoint> = withContext(Dispatchers.IO) {
    val result = AceLivePeerDiscoveryOrchestrator().discover(
        AceLivePeerDiscoveryOrchestrationRequest(
            dhtRequest = AceLiveDhtDiscoveryRequest(
                swarmKey = swarmKey,
                bootstrapNodes = AceLiveNetworkDefaults.dhtBootstrapNodes
            ),
            trackerRequest = AceLiveUdpTrackerDiscoveryRequest(
                swarmKey = swarmKey,
                trackers = listOf(AceLiveNetworkDefaults.publicTracker),
                peerId = peerId,
                announcePort = announcePort
            )
        )
    )
    result.tcpEndpoints()
}
