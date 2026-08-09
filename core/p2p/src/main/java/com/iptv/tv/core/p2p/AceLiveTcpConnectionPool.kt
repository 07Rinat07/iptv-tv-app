package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AceLiveTcpPoolEvent {
    val peerId: Long

    data class TransportConnected(
        override val peerId: Long,
        val reconnectAttempt: Int
    ) : AceLiveTcpPoolEvent

    data class HandshakeAccepted(
        override val peerId: Long
    ) : AceLiveTcpPoolEvent

    data class HandshakeRejected(
        override val peerId: Long,
        val reason: AceLivePeerHandshakeRejectReason
    ) : AceLiveTcpPoolEvent

    data class Ingress(
        override val peerId: Long,
        val result: AceLivePeerIngressResult
    ) : AceLiveTcpPoolEvent

    data class ConnectFailed(
        override val peerId: Long,
        val retrying: Boolean
    ) : AceLiveTcpPoolEvent

    data class Disconnected(
        override val peerId: Long,
        val reason: AceLiveTcpDisconnectReason,
        val requeuedPieces: List<Long>,
        val retrying: Boolean
    ) : AceLiveTcpPoolEvent
}

enum class AceLiveTcpDisconnectReason {
    REMOTE_CLOSED,
    IO_ERROR,
    HANDSHAKE_REJECTED,
    PROTOCOL_REJECTED
}

data class AceLiveTcpDispatchResult(
    val scheduledFrames: Int,
    val selectedFrames: Int,
    val sentFrames: Int,
    val failedPeerIds: Set<Long>
)

/**
 * Bounded TCP ownership layer for autonomous Ace Live peers.
 *
 * Responsibilities:
 * - own connect/read/write/close and bounded reconnect for discovered endpoints;
 * - perform the public Ace transport handshake before accepting peer frames;
 * - feed bounded peer reads into [AceLivePeerConnectionStateMachine];
 * - serialize all access to the shared [AceLivePeerSessionCoordinator];
 * - route only already-scheduled request frames to the connection that currently owns them.
 *
 * Non-responsibilities:
 * - tracker/DHT discovery and peer scoring;
 * - proprietary/signed identity generation;
 * - `.acelive` decryption or content-id metadata resolution;
 * - playback muxing/output.
 */
class AceLiveTcpConnectionPool(
    private val scope: CoroutineScope,
    private val session: AceLivePeerSessionCoordinator,
    private val transportFactory: AceLiveTcpTransportFactory = JvmAceLiveTcpTransportFactory(),
    private val handshakeCodec: AceLivePeerHandshakeCodec = AceLivePeerHandshakeCodec(),
    private val policy: AceLiveTcpConnectionPolicy = AceLiveTcpConnectionPolicy(),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val onEvent: (AceLiveTcpPoolEvent) -> Unit = {}
) {
    private val poolMutex = Mutex()
    private val sessionMutex = Mutex()
    private val peers = LinkedHashMap<Long, PeerRuntime>()

    suspend fun startPeer(
        peerId: Long,
        endpoint: AceLiveTcpPeerEndpoint,
        swarmKey: ByteArray,
        localPeerId: ByteArray
    ) {
        require(peerId >= 0) { "peerId must be non-negative" }
        require(swarmKey.size == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) {
            "swarmKey must be ${AceLivePeerHandshakeCodec.SWARM_KEY_BYTES} bytes"
        }
        require(localPeerId.size == AceLivePeerHandshakeCodec.PEER_ID_BYTES) {
            "localPeerId must be ${AceLivePeerHandshakeCodec.PEER_ID_BYTES} bytes"
        }

        val runtime = PeerRuntime(
            peerId = peerId,
            endpoint = endpoint,
            swarmKey = swarmKey.copyOf(),
            localPeerId = localPeerId.copyOf(),
            connection = AceLivePeerConnectionStateMachine(peerId = peerId, session = session)
        )
        poolMutex.withLock {
            require(peerId !in peers) { "peerId $peerId is already active" }
            require(peers.size < policy.maxConcurrentPeers) {
                "Ace Live TCP peer pool is full"
            }
            peers[peerId] = runtime
        }

        runtime.job = scope.launch {
            runPeer(runtime)
        }
    }

    suspend fun stopPeer(peerId: Long) {
        val runtime = poolMutex.withLock { peers[peerId] } ?: return
        runtime.transport?.close()
        runtime.job?.cancelAndJoin()
    }

    suspend fun close() {
        val runtimes = poolMutex.withLock { peers.values.toList() }
        runtimes.forEach { runtime ->
            runtime.transport?.close()
            runtime.job?.cancelAndJoin()
        }
    }

    suspend fun activePeerIds(): Set<Long> =
        poolMutex.withLock { peers.keys.toSet() }

    /**
     * Runs one serialized scheduling tick and writes selected request frames to matching peers.
     */
    suspend fun scheduleAndDispatch(
        head: Long,
        nowMillis: Long = clockMillis()
    ): AceLiveTcpDispatchResult {
        val runtimes = poolMutex.withLock { peers.values.toList() }
        val scheduledAndRoutes = sessionMutex.withLock {
            val scheduled = session.schedule(head = head, nowMillis = nowMillis)
            val routes = runtimes.mapNotNull { runtime ->
                val selected = runtime.connection.selectOutboundRequestFrames(scheduled)
                if (selected.isEmpty()) null else runtime to selected
            }
            scheduled to routes
        }

        val scheduled = scheduledAndRoutes.first
        val routes = scheduledAndRoutes.second
        var sentFrames = 0
        val failedPeers = linkedSetOf<Long>()

        for ((runtime, frames) in routes) {
            val transport = runtime.transport
            if (transport == null) {
                failedPeers += runtime.peerId
                continue
            }
            val sent = runtime.writeMutex.withLock {
                var count = 0
                try {
                    for (frame in frames) {
                        transport.write(frame)
                        count += 1
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    failedPeers += runtime.peerId
                    runCatching { transport.close() }
                }
                count
            }
            sentFrames += sent
        }

        return AceLiveTcpDispatchResult(
            scheduledFrames = scheduled.size,
            selectedFrames = routes.sumOf { it.second.size },
            sentFrames = sentFrames,
            failedPeerIds = failedPeers
        )
    }

    /** Recovery calls share the same serialization boundary as network ingress and scheduling. */
    suspend fun evaluateRecovery(
        nowMillis: Long = clockMillis()
    ): AceLiveRecoveryPlan = sessionMutex.withLock {
        session.evaluateRecovery(nowMillis)
    }

    suspend fun applyRecoveryAdvance(
        advance: AceLiveCursorAdvance,
        nowMillis: Long = clockMillis()
    ): AceLiveRecoveryApplicationResult = sessionMutex.withLock {
        session.applyRecoveryAdvance(advance, nowMillis)
    }

    suspend fun nextNeededPiece(): Long? = sessionMutex.withLock {
        session.nextNeededPiece()
    }

    private suspend fun runPeer(runtime: PeerRuntime) {
        var reconnectAttempt = 0
        try {
            while (currentCoroutineContext().isActive) {
                val transport = try {
                    transportFactory.connect(runtime.endpoint, policy)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    val retrying = reconnectAttempt < policy.maxReconnectAttempts
                    emit(AceLiveTcpPoolEvent.ConnectFailed(runtime.peerId, retrying))
                    if (!retrying) break
                    reconnectAttempt += 1
                    delay(policy.reconnectDelayMillis)
                    continue
                }

                runtime.transport = transport
                val exit = try {
                    runConnectedTransport(runtime, transport, reconnectAttempt)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    ConnectionExit(
                        reason = AceLiveTcpDisconnectReason.IO_ERROR,
                        retryable = true
                    )
                } finally {
                    runtime.transport = null
                    runCatching { transport.close() }
                }

                val dropped = sessionMutex.withLock {
                    runtime.connection.onTransportDisconnected()
                }
                val retrying =
                    exit.retryable && reconnectAttempt < policy.maxReconnectAttempts &&
                        currentCoroutineContext().isActive
                emit(
                    AceLiveTcpPoolEvent.Disconnected(
                        peerId = runtime.peerId,
                        reason = exit.reason,
                        requeuedPieces = dropped.requeuedPieces,
                        retrying = retrying
                    )
                )

                if (!retrying) break
                reconnectAttempt += 1
                delay(policy.reconnectDelayMillis)
            }
        } finally {
            runtime.transport?.let { transport ->
                runCatching { transport.close() }
            }
            runtime.transport = null
            sessionMutex.withLock {
                if (runtime.connection.phase() != AceLivePeerConnectionPhase.DISCONNECTED) {
                    runtime.connection.onTransportDisconnected()
                }
            }
            poolMutex.withLock {
                if (peers[runtime.peerId] === runtime) {
                    peers.remove(runtime.peerId)
                }
            }
        }
    }

    private suspend fun runConnectedTransport(
        runtime: PeerRuntime,
        transport: AceLiveTcpTransport,
        reconnectAttempt: Int
    ): ConnectionExit {
        sessionMutex.withLock {
            runtime.connection.onTransportConnected()
        }
        emit(AceLiveTcpPoolEvent.TransportConnected(runtime.peerId, reconnectAttempt))

        transport.write(
            handshakeCodec.encode(
                swarmKey = runtime.swarmKey,
                peerId = runtime.localPeerId
            )
        )

        val readBuffer = ByteArray(policy.readBufferBytes)
        var handshakeBuffer = byteArrayOf()
        var handshakeAccepted = false

        while (currentCoroutineContext().isActive && !handshakeAccepted) {
            val count = transport.read(readBuffer)
            if (count < 0) {
                return ConnectionExit(AceLiveTcpDisconnectReason.REMOTE_CLOSED, retryable = true)
            }
            if (count == 0) continue
            require(count <= readBuffer.size) { "transport returned more bytes than requested" }

            handshakeBuffer += readBuffer.copyOf(count)
            when (
                val decoded = handshakeCodec.decode(
                    buffer = handshakeBuffer,
                    expectedSwarmKey = runtime.swarmKey
                )
            ) {
                is AceLivePeerHandshakeDecodeResult.NeedMoreData -> {
                    check(handshakeBuffer.size < AceLivePeerHandshakeCodec.HANDSHAKE_BYTES) {
                        "handshake decoder requested more data after complete handshake length"
                    }
                }

                is AceLivePeerHandshakeDecodeResult.Rejected -> {
                    emit(AceLiveTcpPoolEvent.HandshakeRejected(runtime.peerId, decoded.reason))
                    return ConnectionExit(
                        AceLiveTcpDisconnectReason.HANDSHAKE_REJECTED,
                        retryable = false
                    )
                }

                is AceLivePeerHandshakeDecodeResult.Decoded -> {
                    val interested = sessionMutex.withLock {
                        runtime.connection.onHandshakeAccepted()
                    }
                    transport.write(interested)
                    emit(AceLiveTcpPoolEvent.HandshakeAccepted(runtime.peerId))
                    handshakeAccepted = true

                    if (handshakeBuffer.size > decoded.consumedBytes) {
                        val remainder = handshakeBuffer.copyOfRange(
                            decoded.consumedBytes,
                            handshakeBuffer.size
                        )
                        if (!consumeIngress(runtime, remainder)) {
                            return ConnectionExit(
                                AceLiveTcpDisconnectReason.PROTOCOL_REJECTED,
                                retryable = false
                            )
                        }
                    }
                    handshakeBuffer = byteArrayOf()
                }
            }
        }

        while (currentCoroutineContext().isActive) {
            val count = transport.read(readBuffer)
            if (count < 0) {
                return ConnectionExit(AceLiveTcpDisconnectReason.REMOTE_CLOSED, retryable = true)
            }
            if (count == 0) continue
            require(count <= readBuffer.size) { "transport returned more bytes than requested" }

            if (!consumeIngress(runtime, readBuffer.copyOf(count))) {
                return ConnectionExit(
                    AceLiveTcpDisconnectReason.PROTOCOL_REJECTED,
                    retryable = false
                )
            }
        }

        return ConnectionExit(AceLiveTcpDisconnectReason.REMOTE_CLOSED, retryable = false)
    }

    private suspend fun consumeIngress(
        runtime: PeerRuntime,
        bytes: ByteArray
    ): Boolean {
        val result = sessionMutex.withLock {
            runtime.connection.consumePeerBytes(bytes, nowMillis = clockMillis())
        }
        if (
            result.decodedFrames > 0 ||
            result.metadataUpdates.isNotEmpty() ||
            result.metadataRejections.isNotEmpty() ||
            result.requeuedPieces.isNotEmpty() ||
            result.emittedPieces.isNotEmpty() ||
            result.disconnectRecommended
        ) {
            emit(AceLiveTcpPoolEvent.Ingress(runtime.peerId, result))
        }
        return !result.disconnectRecommended
    }

    private fun emit(event: AceLiveTcpPoolEvent) {
        runCatching { onEvent(event) }
    }

    private class PeerRuntime(
        val peerId: Long,
        val endpoint: AceLiveTcpPeerEndpoint,
        val swarmKey: ByteArray,
        val localPeerId: ByteArray,
        val connection: AceLivePeerConnectionStateMachine
    ) {
        val writeMutex = Mutex()

        @Volatile
        var transport: AceLiveTcpTransport? = null

        @Volatile
        var job: Job? = null
    }

    private data class ConnectionExit(
        val reason: AceLiveTcpDisconnectReason,
        val retryable: Boolean
    )
}
