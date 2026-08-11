package com.iptv.tv.core.p2p

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

data class AceLivePreparedStream(
    val url: String,
    val name: String
)

/** End-to-end autonomous playback for public Ace Live content IDs. */
class AceLiveEmbeddedEngine(
    okHttpClient: OkHttpClient,
    private val catalogResolver: AceContentCatalogResolver = AceContentCatalogResolver(okHttpClient),
    private val metadataPeerResolver: AceContentMetadataPeerResolver = AceContentMetadataPeerResolver(),
    private val eventObserver: (AceLiveTcpPoolEvent) -> Unit = {}
) {
    private val operationMutex = Mutex()
    private val generation = AtomicLong(0L)
    private var activeRuntime: Runtime? = null

    suspend fun prepareStream(contentId: String): P2pResult<AceLivePreparedStream> {
        val token = generation.incrementAndGet()
        val swarmKey = AceLiveSwarmKey.parseHex(contentId)
            ?: return P2pResult.Error("Ace content ID must contain exactly 40 hexadecimal characters")

        // Current public live channels may publish their content ID directly as the Ace peer-wire
        // swarm key. This stays inside Ace Live and is never handed to ordinary BitTorrent.
        val direct = prepareResolvedTransport(
            token = token,
            transport = directLiveTransport(swarmKey)
        )
        if (direct is P2pResult.Success) return direct
        val directFailure = direct as P2pResult.Error
        if (generation.get() != token) return superseded()

        return when (val metadata = resolveContentTransport(swarmKey.toHex())) {
            is P2pResult.Success -> prepareResolvedTransport(token, metadata.data)
            is P2pResult.Error -> P2pResult.Error(
                message = "The direct Ace Live swarm failed and transport metadata was unavailable: " +
                    directFailure.message,
                cause = directFailure.cause ?: metadata.cause
            )
        }
    }

    suspend fun prepareInfoHash(infoHash: String): P2pResult<AceLivePreparedStream> {
        val token = generation.incrementAndGet()
        val swarmKey = AceLiveSwarmKey.parseHex(infoHash)
            ?: return P2pResult.Error("Ace Live infohash must contain exactly 40 hexadecimal characters")
        return prepareResolvedTransport(token, directLiveTransport(swarmKey))
    }

    suspend fun prepareTransportFile(bytes: ByteArray): P2pResult<AceLivePreparedStream> {
        val token = generation.incrementAndGet()
        val transport = when (val decoded = AceTransportDescriptorDecoder.decodeLive(bytes)) {
            is P2pResult.Success -> decoded.data
            is P2pResult.Error -> return decoded
        }
        return prepareResolvedTransport(token, transport)
    }

    private suspend fun resolveContentTransport(
        contentId: String
    ): P2pResult<AceResolvedLiveTransport> = supervisorScope {
        val attempts = listOf(
            async { catalogResolver.resolve(contentId) },
            async { metadataPeerResolver.resolve(contentId) }
        ).awaitAll()
        attempts.filterIsInstance<P2pResult.Success<AceResolvedLiveTransport>>()
            .firstOrNull()
            ?: P2pResult.Error(
                message = "Ace transport metadata was unavailable from both catalog and metadata swarm",
                cause = attempts.filterIsInstance<P2pResult.Error>()
                    .mapNotNull(P2pResult.Error::cause)
                    .lastOrNull()
            )
    }

    private suspend fun prepareResolvedTransport(
        token: Long,
        transport: AceResolvedLiveTransport
    ): P2pResult<AceLivePreparedStream> {
        if (generation.get() != token) return superseded()

        val runtimeCreation = operationMutex.withLock {
            if (generation.get() != token) return@withLock null
            closeActiveLocked()
            runCatching {
                Runtime(transport, eventObserver).also { created -> activeRuntime = created }
            }
        } ?: return superseded()
        val runtime = runtimeCreation.getOrElse { error ->
            return P2pResult.Error(
                error.message ?: "Ace Live runtime could not be initialized",
                error
            )
        }

        runtime.start()
        return try {
            withTimeout(STARTUP_TIMEOUT_MILLIS) {
                runtime.startup.await()
            }
            if (generation.get() != token) return superseded()
            P2pResult.Success(
                AceLivePreparedStream(
                    url = runtime.server.url,
                    name = transport.name.ifBlank { "Ace Live" }
                )
            )
        } catch (error: Throwable) {
            operationMutex.withLock {
                if (activeRuntime === runtime) activeRuntime = null
            }
            runtime.close()
            if (generation.get() != token) {
                superseded()
            } else {
                P2pResult.Error(
                    error.message ?: "Ace Live did not produce media before the startup timeout",
                    error
                )
            }
        }
    }

    suspend fun stopStream(): P2pResult<Unit> {
        generation.incrementAndGet()
        return operationMutex.withLock {
            runCatching { closeActiveLocked() }.fold(
                onSuccess = { P2pResult.Success(Unit) },
                onFailure = { error ->
                    P2pResult.Error(error.message ?: "Ace Live stream could not be stopped", error)
                }
            )
        }
    }

    private fun superseded(): P2pResult.Error =
        P2pResult.Error("Ace Live preparation was superseded by a newer player action")

    private fun directLiveTransport(swarmKey: AceLiveSwarmKey) = AceResolvedLiveTransport(
        name = "Ace Live",
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = DEFAULT_DIRECT_PIECE_BYTES,
            chunkLengthBytes = DEFAULT_DIRECT_CHUNK_BYTES,
            bitrate = 1L
        ),
        swarmKey = swarmKey,
        trackers = listOf(AceLiveNetworkDefaults.publicTracker),
        publicKeyDer = null,
        authMethod = "RSA"
    )

    private fun closeActiveLocked() {
        activeRuntime?.close()
        activeRuntime = null
    }

    private class Runtime(
        private val transport: AceResolvedLiveTransport,
        private val eventObserver: (AceLiveTcpPoolEvent) -> Unit
    ) : Closeable {
        val startup = CompletableDeferred<Unit>()
        val mediaBuffer = AceLiveMediaBuffer()
        val server = LoopbackHttpLiveServer(mediaBuffer)

        private val closed = AtomicBoolean(false)
        private val latestHead = AtomicLong(-1L)
        private val peerIds = AtomicLong(1L)
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val authenticator = AceLiveMediaAuthenticator(transport.publicKeyDer)
        private val resynchronizer = AceLiveMpegTsResynchronizer()
        private val announceLease = AceLiveAnnouncePortLease()
        private val localPeerId = AceLiveNodeIdentity.peerId()
        private val session = AceLivePeerSessionCoordinator(
            geometry = transport.geometry,
            initialNextNeededPiece = 0L,
            maxInFlightPerPeer = MAX_IN_FLIGHT_PER_PEER,
            recoveryPolicy = AceLiveRecoveryPolicy(
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS,
                staleUpstreamTimeoutMillis = STALE_UPSTREAM_TIMEOUT_MILLIS,
                requestCheckIntervalMillis = REQUEST_CHECK_INTERVAL_MILLIS
            ),
            requestedMaxAheadPieces = MAX_REASSEMBLY_PIECES,
            maxBufferedBytes = MAX_REASSEMBLY_BYTES
        )
        private val pool = AceLiveTcpConnectionPool(
            scope = scope,
            session = session,
            policy = AceLiveTcpConnectionPolicy(maxConcurrentPeers = MAX_ACTIVE_PEERS),
            onEvent = ::onPoolEvent
        )
        private val refillCoordinator = AceLivePeerRefillCoordinator(
            AceLivePeerRefillPolicy(
                targetActivePeers = MAX_ACTIVE_PEERS,
                maxActivePeers = MAX_ACTIVE_PEERS,
                staleProbePeers = 0,
                maxStartsPerCycle = MAX_ACTIVE_PEERS,
                refreshIntervalMillis = PEER_REFRESH_INTERVAL_MILLIS
            )
        )
        private val refillLoop = AceLivePeerRefillLoop(
            coordinator = refillCoordinator,
            discover = { discoverPeers() },
            activePeerIds = { pool.activePeerIds() },
            evaluateRecovery = { pool.evaluateRecovery() },
            nextNeededPiece = { pool.nextNeededPiece() },
            allocatePeerId = { peerIds.getAndIncrement() },
            startPeer = { peerId, endpoint ->
                pool.startPeer(
                    peerId = peerId,
                    endpoint = endpoint,
                    swarmKey = transport.swarmKey.toByteArray(),
                    localPeerId = localPeerId
                )
            }
        )
        private var runner: Job? = null

        fun start() {
            check(runner == null) { "Ace Live runtime is already started" }
            runner = scope.launch {
                try {
                    val initialRefill = refillLoop.runOneCycle()
                    if (initialRefill.startedPeers == 0) {
                        error("Ace Live peer discovery returned no reachable candidates")
                    }
                    val refillJob = launch { refillLoop.run() }
                    try {
                        driveSession()
                    } finally {
                        refillJob.cancel()
                    }
                } catch (error: Throwable) {
                    if (!closed.get()) {
                        startup.completeExceptionally(error)
                        mediaBuffer.fail(error)
                    }
                }
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            startup.cancel()
            mediaBuffer.close()
            runCatching { server.close() }
            runCatching { announceLease.close() }
            runCatching { runBlocking(Dispatchers.IO) { pool.close() } }
            scope.cancel()
        }

        private suspend fun discoverPeers(): AceLivePeerDiscoveryOrchestrationResult {
            val dhtRequest = AceLiveDhtDiscoveryRequest(
                swarmKey = transport.swarmKey,
                bootstrapNodes = DEFAULT_DHT_BOOTSTRAP_NODES
            )
            val trackers = (transport.trackers + DEFAULT_ACE_TRACKER).distinct()
            val trackerRequest = AceLiveUdpTrackerDiscoveryRequest(
                swarmKey = transport.swarmKey,
                trackers = trackers,
                peerId = localPeerId,
                announcePort = announceLease.port
            )
            return AceLivePeerDiscoveryOrchestrator().discover(
                AceLivePeerDiscoveryOrchestrationRequest(
                    dhtRequest = dhtRequest,
                    trackerRequest = trackerRequest
                )
            )
        }

        private suspend fun driveSession() {
            while (currentCoroutineContext().isActive) {
                val head = latestHead.get()
                if (head >= 0L) {
                    pool.scheduleAndDispatch(head)
                    val recovery = pool.evaluateRecovery()
                    recovery.cursorAdvance?.let { advance ->
                        val applied = pool.applyRecoveryAdvance(advance)
                        if (applied.outputDiscontinuity != null) resynchronizer.reset()
                        emitPieces(applied.emittedPieces)
                    }
                    if (recovery.gapBeyondAdvanceLimit) {
                        error("Ace Live peer window moved beyond the safe recovery limit")
                    }
                }
                delay(SCHEDULER_TICK_MILLIS)
            }
        }

        private fun onPoolEvent(event: AceLiveTcpPoolEvent) {
            refillCoordinator.onPoolEvent(event, System.currentTimeMillis())
            runCatching { eventObserver(event) }
            if (closed.get()) return
            if (event !is AceLiveTcpPoolEvent.Ingress) return

            event.result.metadataUpdates.forEach { window ->
                latestHead.accumulateAndGet(window.maxPiece) { current, update -> maxOf(current, update) }
            }
            emitPieces(event.result.emittedPieces)
        }

        private fun emitPieces(pieces: List<AceLiveReassembledPiece>) {
            pieces.forEach { piece ->
                when (val verified = authenticator.verifyAndStrip(piece.data)) {
                    is P2pResult.Success -> {
                        val media = resynchronizer.consume(verified.data)
                        if (media.isNotEmpty()) {
                            mediaBuffer.append(media)
                            startup.complete(Unit)
                        }
                    }

                    is P2pResult.Error -> {
                        startup.completeExceptionally(
                            verified.cause ?: IllegalStateException(verified.message)
                        )
                        mediaBuffer.fail(
                            verified.cause ?: IllegalStateException(verified.message)
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val DEFAULT_DHT_BOOTSTRAP_NODES = AceLiveNetworkDefaults.dhtBootstrapNodes
        const val DEFAULT_ACE_TRACKER = AceLiveNetworkDefaults.publicTracker
        const val DEFAULT_DIRECT_PIECE_BYTES = 512 * 1024
        const val DEFAULT_DIRECT_CHUNK_BYTES = 16 * 1024
        const val MAX_ACTIVE_PEERS = 6
        const val MAX_IN_FLIGHT_PER_PEER = 1
        const val REQUEST_TIMEOUT_MILLIS = 15_000L
        const val STALE_UPSTREAM_TIMEOUT_MILLIS = 45_000L
        const val REQUEST_CHECK_INTERVAL_MILLIS = 1_000L
        const val MAX_REASSEMBLY_PIECES = 12L
        const val MAX_REASSEMBLY_BYTES = 12L * 1024L * 1024L
        const val SCHEDULER_TICK_MILLIS = 200L
        const val STARTUP_TIMEOUT_MILLIS = 60_000L
        const val PEER_REFRESH_INTERVAL_MILLIS = 10_000L
    }
}
