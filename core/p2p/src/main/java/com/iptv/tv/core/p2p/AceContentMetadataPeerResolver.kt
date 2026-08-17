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
            val handshakeCodec = AceContentMetadataPeerHandshakeCodec()
            socket.write(handshakeCodec.encode(contentId.toByteArray(), peerId))
            val peerHandshake = readExactly(socket, AceContentMetadataPeerHandshakeCodec.HANDSHAKE_BYTES)
            handshakeCodec.decode(peerHandshake, contentId.toByteArray())

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
            val size = (dictionary.values["metadata_size"] as? AceBencodeValue.Integer)
                ?.value
                ?.also { advertisedSize ->
                    require(advertisedSize in 1..MAX_METADATA_BYTES.toLong()) {
                        "Ace metadata peer advertised an invalid metadata size"
                    }
                }
                ?.toInt()
            return MetadataParameters(extensionId, size)
        }
        error("Ace metadata peer did not send an extended handshake")
    }

    private suspend fun fetchMetadata(
        frames: PeerFrameReader,
        transport: AceLiveTcpTransport,
        peerMetadataExtensionId: Int,
        metadataSize: Int?
    ): ByteArray {
        var resolvedSize = metadataSize
        var pieceCount = resolvedSize?.let { size ->
            (size + METADATA_BLOCK_BYTES - 1) / METADATA_BLOCK_BYTES
        }
        var output = resolvedSize?.let(::ByteArray)
        var received = pieceCount?.let(::BooleanArray)
        var remaining = pieceCount ?: -1

        if (pieceCount == null) {
            // Some deployed BEP-9 peers advertise ut_metadata but omit metadata_size from their
            // extension handshake. Probe piece 0 only; a data response carries mandatory
            // total_size, which lets us keep the same bounded allocation and piece validation.
            transport.write(encodeMetadataRequest(peerMetadataExtensionId, 0))
        } else {
            repeat(requireNotNull(pieceCount)) { piece ->
                transport.write(encodeMetadataRequest(peerMetadataExtensionId, piece))
            }
        }

        var framesRead = 0
        while (true) {
            if (remaining == 0) return requireNotNull(output)
            val currentFrameBudget = (pieceCount ?: 1) * MAX_FRAMES_PER_METADATA_PIECE + 32
            require(framesRead < currentFrameBudget) {
                if (resolvedSize == null) {
                    "Ace metadata peer did not report metadata total_size"
                } else {
                    "Ace metadata peer did not return every metadata piece"
                }
            }
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
            if (messageType != METADATA_DATA) continue

            val learnedSizeThisFrame = resolvedSize == null
            if (learnedSizeThisFrame && piece != 0) continue
            val totalSize = (header.values["total_size"] as? AceBencodeValue.Integer)?.value
            if (learnedSizeThisFrame) {
                val learnedSize = totalSize
                    ?: error("Ace metadata peer omitted total_size from the first metadata piece")
                require(learnedSize in 1..MAX_METADATA_BYTES.toLong()) {
                    "Ace metadata peer reported an invalid metadata total_size"
                }
                resolvedSize = learnedSize.toInt()
                pieceCount = (resolvedSize + METADATA_BLOCK_BYTES - 1) / METADATA_BLOCK_BYTES
                output = ByteArray(resolvedSize)
                received = BooleanArray(requireNotNull(pieceCount))
                remaining = requireNotNull(pieceCount)
            } else if (totalSize != null) {
                require(totalSize == requireNotNull(resolvedSize).toLong()) {
                    "Ace metadata peer changed metadata_size"
                }
            }

            val currentSize = requireNotNull(resolvedSize)
            val currentPieceCount = requireNotNull(pieceCount)
            val currentOutput = requireNotNull(output)
            val currentReceived = requireNotNull(received)
            if (piece !in 0 until currentPieceCount || currentReceived[piece]) continue

            val offset = piece * METADATA_BLOCK_BYTES
            val expected = minOf(METADATA_BLOCK_BYTES, currentSize - offset)
            val data = encodedAndData.copyOfRange(consumedBytes, encodedAndData.size)
            require(data.size == expected) { "Ace metadata peer returned a partial metadata piece" }
            data.copyInto(currentOutput, offset)
            currentReceived[piece] = true
            remaining -= 1

            if (learnedSizeThisFrame && currentPieceCount > 1) {
                for (nextPiece in 1 until currentPieceCount) {
                    transport.write(encodeMetadataRequest(peerMetadataExtensionId, nextPiece))
                }
            }
        }
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

    private data class MetadataParameters(val extensionId: Int, val sizeBytes: Int?)

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
    // Content metadata races direct live startup for the same public content id. Returning the first
    // DHT candidate lets metadata probing begin without owning the process-wide DHT gate for the
    // complete 15-second walk; the direct runtime performs its own non-blocking full expansion.
    val startupDhtDiscovery = AceLiveDhtDiscovery(
        policy = AceLiveDhtPolicy(
            returnAfterPeers = ACE_LIVE_STARTUP_DHT_RETURN_AFTER_PEERS
        ),
        reuseRecentResults = true
    )
    val result = AceLivePeerDiscoveryOrchestrator(
        dhtDiscover = startupDhtDiscovery::discover
    ).discover(
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
