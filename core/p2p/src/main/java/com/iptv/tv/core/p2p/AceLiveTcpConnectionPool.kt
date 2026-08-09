package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    HANDSHAKE_TIMEOUT,
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
    private val dispatchMutex = Mutex()
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
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runPeer(runtime)
        }
        runtime.job = job

        try {
            poolMutex.withLock {
                require(peerId !in peers) { "peerId $peerId is already active" }
                require(peers.size < policy.maxConcurrentPeers) {
                    "Ace Live TCP peer pool is full"
                }
                peers[peerId] = runtime
            }
        } catch (error: Throwable) {
            job.cancel()
            throw error
        }

        if (!job.start()) {
            poolMutex.withLock {
                if (peers[peerId] === runtime) peers.remove(peerId)
            }
        }
    }

    suspend fun stopPeer(peerId: Long) {
        val runtime = poolMutex.withLock { peers[peerId] } ?: return
        runtime.transport?.close()
        runtime.writeJob?.cancel()
        runtime.job?.cancelAndJoin()
        poolMutex.withLock {
            if (
                peers[peerId] === runtime &&
                runtime.connection.phase() == AceLivePeerConnectionPhase.DISCONNECTED
            ) {
                peers.remove(peerId)
            }
        }
    }

    suspend fun close() {
        val runtimes = poolMutex.withLock { peers.values.toList() }
        runtimes.forEach { runtime ->
            runtime.transport?.close()
            runtime.writeJob?.cancel()
            runtime.job?.cancelAndJoin()
        }
        poolMutex.withLock {
            runtimes.forEach { runtime ->
                if (
                    peers[runtime.peerId] === runtime &&
                    runtime.connection.phase() == AceLivePeerConnectionPhase.DISCONNECTED
                ) {
                    peers.remove(runtime.peerId)
                }
            }
        }
    }

    suspend fun activePeerIds(): Set<Long> =
        poolMutex.withLock { peers.keys.toSet() }

    /**
     * Runs one serialized scheduling tick and writes selected request frames to matching peers.
     *
     * Routes are dispatched concurrently. Each peer has at most one in-flight socket write and a
     * bounded write deadline; a stalled peer is closed without delaying healthy peers.
     */
    suspend fun scheduleAndDispatch(
        head: Long,
        nowMillis: Long = clockMillis()
    ): AceLiveTcpDispatchResult = dispatchMutex.withLock {
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
        val outcomes = coroutineScope {
            routes.map { (runtime, frames) ->
                async {
                    dispatchRoute(runtime, frames)
                }
            }.awaitAll()
        }

        AceLiveTcpDispatchResult(
            scheduledFrames = scheduled.size,
            selectedFrames = routes.sumOf { it.second.size },
            sentFrames = outcomes.sumOf { it.sentFrames },
            failedPeerIds = outcomes.filter { it.failed }.mapTo(linkedSetOf()) { it.peerId }
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

    private suspend fun dispatchRoute(
        runtime: PeerRuntime,
        frames: List<ByteArray>
    ): RouteDispatchResult {
        val transport = runtime.transport
            ?: return RouteDispatchResult(runtime.peerId, sentFrames = 0, failed = true)

        var sent = 0
        for (frame in frames) {
            if (!writeFrameBounded(runtime, transport, frame)) {
                return RouteDispatchResult(runtime.peerId, sentFrames = sent, failed = true)
            }
            sent += 1
        }
        return RouteDispatchResult(runtime.peerId, sentFrames = sent, failed = false)
    }

    private suspend fun writeFrameBounded(
        runtime: PeerRuntime,
        transport: AceLiveTcpTransport,
        bytes: ByteArray
    ): Boolean {
        val writer = runtime.writeStateMutex.withLock {
            val existing = runtime.writeJob
            if (existing != null && !existing.isCompleted) {
                null
            } else {
                scope.async {
                    try {
                        transport.write(bytes)
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        false
                    }
                }.also { runtime.writeJob = it }
            }
        }

        if (writer == null) {
            runCatching { transport.close() }
            return false
        }

        val success = try {
            withTimeoutOrNull(policy.writeTimeoutMillis.toLong()) {
                writer.await()
            } ?: false
        } catch (cancelled: CancellationException) {
            writer.cancel()
            runCatching { transport.close() }
            throw cancelled
        }

        if (!success) {
            runCatching { transport.close() }
            writer.cancel()
        }
        runtime.writeStateMutex.withLock {
            if (runtime.writeJob === writer && writer.isCompleted) {
                runtime.writeJob = null
            }
        }
        return success
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
            withContext(NonCancellable) {
                runtime.transport?.let { transport ->
                    runCatching { transport.close() }
                }
                runtime.transport = null
                runtime.writeJob?.cancel()
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

        val localHandshake = handshakeCodec.encode(
            swarmKey = runtime.swarmKey,
            peerId = runtime.localPeerId
        )
        if (!writeFrameBounded(runtime, transport, localHandshake)) {
            return ConnectionExit(AceLiveTcpDisconnectReason.IO_ERROR, retryable = true)
        }

        val readBuffer = ByteArray(policy.readBufferBytes)
        when (val handshake = awaitHandshake(runtime, transport, readBuffer)) {
            is HandshakeStageResult.Exit -> return handshake.exit
            HandshakeStageResult.Accepted -> Unit
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

    private suspend fun awaitHandshake(
        runtime: PeerRuntime,
        transport: AceLiveTcpTransport,
        readBuffer: ByteArray
    ): HandshakeStageResult =
        withTimeoutOrNull(policy.handshakeTimeoutMillis.toLong()) {
            var handshakeBuffer = byteArrayOf()

            while (currentCoroutineContext().isActive) {
                val count = transport.read(readBuffer)
                if (count < 0) {
                    return@withTimeoutOrNull HandshakeStageResult.Exit(
                        ConnectionExit(AceLiveTcpDisconnectReason.REMOTE_CLOSED, retryable = true)
                    )
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
                        return@withTimeoutOrNull HandshakeStageResult.Exit(
                            ConnectionExit(
                                AceLiveTcpDisconnectReason.HANDSHAKE_REJECTED,
                                retryable = false
                            )
                        )
                    }

                    is AceLivePeerHandshakeDecodeResult.Decoded -> {
                        val interested = sessionMutex.withLock {
                            runtime.connection.onHandshakeAccepted()
                        }
                        if (!writeFrameBounded(runtime, transport, interested)) {
                            return@withTimeoutOrNull HandshakeStageResult.Exit(
                                ConnectionExit(AceLiveTcpDisconnectReason.IO_ERROR, retryable = true)
                            )
                        }
                        emit(AceLiveTcpPoolEvent.HandshakeAccepted(runtime.peerId))

                        if (handshakeBuffer.size > decoded.consumedBytes) {
                            val remainder = handshakeBuffer.copyOfRange(
                                decoded.consumedBytes,
                                handshakeBuffer.size
                            )
                            if (!consumeIngress(runtime, remainder)) {
                                return@withTimeoutOrNull HandshakeStageResult.Exit(
                                    ConnectionExit(
                                        AceLiveTcpDisconnectReason.PROTOCOL_REJECTED,
                                        retryable = false
                                    )
                                )
                            }
                        }
                        return@withTimeoutOrNull HandshakeStageResult.Accepted
                    }
                }
            }

            HandshakeStageResult.Exit(
                ConnectionExit(AceLiveTcpDisconnectReason.REMOTE_CLOSED, retryable = false)
            )
        } ?: HandshakeStageResult.Exit(
            ConnectionExit(AceLiveTcpDisconnectReason.HANDSHAKE_TIMEOUT, retryable = true)
        )

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
        val writeStateMutex = Mutex()

        @Volatile
        var transport: AceLiveTcpTransport? = null

        @Volatile
        var job: Job? = null

        @Volatile
        var writeJob: Deferred<Boolean>? = null
    }

    private sealed interface HandshakeStageResult {
        data object Accepted : HandshakeStageResult
        data class Exit(val exit: ConnectionExit) : HandshakeStageResult
    }

    private data class RouteDispatchResult(
        val peerId: Long,
        val sentFrames: Int,
        val failed: Boolean
    )

    private data class ConnectionExit(
        val reason: AceLiveTcpDisconnectReason,
        val retryable: Boolean
    )
}
