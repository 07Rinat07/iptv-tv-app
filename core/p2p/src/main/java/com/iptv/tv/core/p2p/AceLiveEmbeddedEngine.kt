package com.iptv.tv.core.p2p

import android.util.Log
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient

data class AceLivePreparedStream(
    val url: String,
    val name: String
)

internal fun aceLiveMediaIsStalled(
    startupComplete: Boolean,
    lastMediaAtMillis: Long,
    nowMillis: Long,
    timeoutMillis: Long
): Boolean {
    require(timeoutMillis > 0L) { "Ace Live stall timeout must be positive" }
    return startupComplete &&
        lastMediaAtMillis > 0L &&
        nowMillis - lastMediaAtMillis >= timeoutMillis
}

/** Keeps live scheduling active while a slow peer-discovery refill continues in the background. */
internal suspend fun runAceLiveSessionWithBackgroundPeerRefill(
    backgroundRefill: suspend () -> Unit,
    driveSession: suspend () -> Unit
) = coroutineScope {
    val refillJob = launch { backgroundRefill() }
    try {
        driveSession()
    } finally {
        refillJob.cancel()
    }
}

/** End-to-end autonomous playback for public Ace Live content IDs. */
class AceLiveEmbeddedEngine(
    okHttpClient: OkHttpClient,
    private val catalogResolver: AceContentCatalogResolver = AceContentCatalogResolver(okHttpClient),
    private val metadataPeerResolver: AceContentMetadataPeerResolver = AceContentMetadataPeerResolver(),
    private val bufferSettings: AceLiveBufferSettings = AceLiveBufferSettings(),
    private val eventObserver: (AceLiveTcpPoolEvent) -> Unit = {}
) {
    private val operationMutex = Mutex()
    private val generation = AtomicLong(0L)
    private var activeRuntime: Runtime? = null

    suspend fun prepareStream(contentId: String): P2pResult<AceLivePreparedStream> {
        val token = generation.incrementAndGet()
        val swarmKey = AceLiveSwarmKey.parseHex(contentId)
            ?: return P2pResult.Error("Ace content ID must contain exactly 40 hexadecimal characters")
        val totalStartedAt = System.currentTimeMillis()

        // Public live content IDs are sometimes directly usable as peer-wire swarm keys, but a dead
        // direct swarm must not serialize a 60-second wait in front of transport metadata. Resolve
        // metadata concurrently and use whichever path becomes actionable first.
        return raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = DIRECT_STARTUP_SOFT_TIMEOUT_MILLIS,
            directAttempt = {
                val startedAt = System.currentTimeMillis()
                prepareResolvedTransport(
                    token = token,
                    transport = directLiveTransport(swarmKey)
                ).also { result ->
                    Log.i(
                        LOG_TAG,
                        "event=content_direct_result elapsed_ms=${System.currentTimeMillis() - startedAt} " +
                            "success=${result is P2pResult.Success}"
                    )
                }
            },
            metadataResolve = {
                val startedAt = System.currentTimeMillis()
                resolveContentTransport(swarmKey.toHex()).also { result ->
                    Log.i(
                        LOG_TAG,
                        "event=content_metadata_result elapsed_ms=${System.currentTimeMillis() - startedAt} " +
                            "success=${result is P2pResult.Success}"
                    )
                }
            },
            metadataAttempt = { transport ->
                val startedAt = System.currentTimeMillis()
                prepareResolvedTransport(token, transport).also { result ->
                    Log.i(
                        LOG_TAG,
                        "event=content_metadata_startup_result elapsed_ms=${System.currentTimeMillis() - startedAt} " +
                            "total_ms=${System.currentTimeMillis() - totalStartedAt} " +
                            "success=${result is P2pResult.Success}"
                    )
                }
            },
            isCurrent = { generation.get() == token },
            superseded = ::superseded,
            combinedFailureMessage = { direct, metadata ->
                "The direct Ace Live swarm failed and transport metadata was unavailable: " +
                    "direct=${direct.message}; metadata=${metadata.message}"
            }
        )
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
    ): P2pResult<AceResolvedLiveTransport> {
        val resolvers: List<suspend () -> P2pResult<AceResolvedLiveTransport>> = listOf(
            { catalogResolver.resolve(contentId) },
            { metadataPeerResolver.resolve(contentId) }
        )
        return firstSuccessfulP2p(
            items = resolvers,
            maxConcurrency = resolvers.size,
            failureMessage = "Ace transport metadata was unavailable from both catalog and metadata swarm"
        ) { resolver ->
            resolver()
        }
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
                Runtime(transport, bufferSettings, eventObserver).also { created -> activeRuntime = created }
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
            withTimeout(runtime.startupTimeoutMillis) {
                runtime.startup.await()
            }
            if (generation.get() != token) return superseded()
            P2pResult.Success(
                AceLivePreparedStream(
                    url = runtime.server.url,
                    name = transport.name.ifBlank { "Ace Live" }
                )
            )
        } catch (cancelled: CancellationException) {
            // Startup races and fast channel switching cancel speculative runtimes intentionally.
            // Cleanup must complete even though the caller's coroutine is already cancelled.
            withContext(NonCancellable) {
                operationMutex.withLock {
                    if (activeRuntime === runtime) activeRuntime = null
                }
                runtime.close()
            }
            throw cancelled
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
        bufferSettings: AceLiveBufferSettings,
        private val eventObserver: (AceLiveTcpPoolEvent) -> Unit
    ) : Closeable {
        private val startupBufferPolicy = AceLiveStartupBufferPolicy(bufferSettings)
        val startup = CompletableDeferred<Unit>()
        val startupTimeoutMillis = startupBufferPolicy.startupTimeoutMillis()
        val mediaBuffer = AceLiveMediaBuffer(maxBufferedBytes = startupBufferPolicy.outputBufferBytes())
        val server = LoopbackHttpLiveServer(mediaBuffer)

        private val closed = AtomicBoolean(false)
        private val connectedAtLeastOnce = AtomicBoolean(false)
        private val latestHead = AtomicLong(-1L)
        private val peerIds = AtomicLong(1L)
        private val emittedBytes = AtomicLong(0L)
        private val startupStartedAtMillis = AtomicLong(0L)
        private val firstPeerStartAtMillis = AtomicLong(0L)
        private val initialPeerDiscovery = AtomicBoolean(true)
        private val immediateStartupDhtOnlyRefill = AtomicBoolean(false)
        private val lastMediaAppendAt = AtomicLong(0L)
        private val lastProgressLogAt = AtomicLong(0L)
        private val lastWindowLogAt = AtomicLong(0L)
        private val loggedUnknownMessageIds = ConcurrentHashMap.newKeySet<Int>()
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
                targetActivePeers = TARGET_ACTIVE_PEERS,
                maxActivePeers = MAX_ACTIVE_PEERS,
                staleProbePeers = STALE_PROBE_PEERS,
                maxStartsPerCycle = MAX_PEER_STARTS_PER_CYCLE,
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
                firstPeerStartAtMillis.compareAndSet(0L, System.currentTimeMillis())
            }
        )
        private var runner: Job? = null

        fun start() {
            check(runner == null) { "Ace Live runtime is already started" }
            startupStartedAtMillis.set(System.currentTimeMillis())
            runner = scope.launch {
                try {
                    val initialRefill = refillLoop.runOneCycle()
                    if (initialRefill.startedPeers == 0) {
                        error("Ace Live peer discovery returned no reachable candidates")
                    }
                    runAceLiveSessionWithBackgroundPeerRefill(
                        backgroundRefill = {
                            if (immediateStartupDhtOnlyRefill.get()) {
                                // A single tracker peer is enough to start live playback immediately.
                                // Expand that weak candidate set through DHT in this background job:
                                // waiting for the full bounded DHT walk here used to hold driveSession()
                                // behind a repeatable ~15-second startup gate even when the tracker peer
                                // was already connected and ready to publish media.
                                val expandedRefill = refillLoop.runOneCycle()
                                if (!connectedAtLeastOnce.get()) {
                                    // The no-connected-peer budget still belongs to the expanded peer
                                    // set. Rearm it after DHT completes without blocking scheduling and
                                    // media ingestion from the original tracker fast-path peer.
                                    firstPeerStartAtMillis.set(System.currentTimeMillis())
                                }
                                Log.i(
                                    LOG_TAG,
                                    "event=startup_dht_expansion " +
                                        "started_peers=${expandedRefill.startedPeers}"
                                )
                            }
                            refillLoop.run()
                        },
                        driveSession = ::driveSession
                    )
                } catch (error: Throwable) {
                    if (!closed.get()) {
                        Log.e(LOG_TAG, "event=runtime_failed reason=${error.javaClass.simpleName}", error)
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
            val isInitialDiscovery = initialPeerDiscovery.compareAndSet(true, false)
            val useImmediateDhtOnlyRefill = !isInitialDiscovery &&
                immediateStartupDhtOnlyRefill.compareAndSet(true, false)
            val discoveryPolicy = if (isInitialDiscovery) {
                AceLivePeerDiscoveryOrchestrationPolicy(trackerFastPathMinPeers = 1)
            } else {
                AceLivePeerDiscoveryOrchestrationPolicy()
            }
            val result = AceLivePeerDiscoveryOrchestrator(policy = discoveryPolicy).discover(
                AceLivePeerDiscoveryOrchestrationRequest(
                    dhtRequest = dhtRequest,
                    trackerRequest = trackerRequest.takeUnless { useImmediateDhtOnlyRefill }
                )
            )
            if (isInitialDiscovery) {
                immediateStartupDhtOnlyRefill.set(
                    aceLiveStartupNeedsImmediateDhtOnlyRefill(result)
                )
            }
            return result
        }

        private suspend fun driveSession() {
            while (currentCoroutineContext().isActive) {
                val now = System.currentTimeMillis()
                if (
                    aceLiveStartupHasNoConnectedPeerTooLong(
                        startupComplete = startup.isCompleted,
                        anyTransportConnected = connectedAtLeastOnce.get(),
                        elapsedSinceFirstPeerStartMillis =
                            elapsedSinceFirstPeerStartMillis(now),
                        timeoutMillis = NO_CONNECTED_PEER_TIMEOUT_MILLIS
                    )
                ) {
                    error(
                        "Ace Live did not connect to any peer within " +
                            "$NO_CONNECTED_PEER_TIMEOUT_MILLIS ms"
                    )
                }
                val stallTimeoutMillis = startupBufferPolicy.mediaStallTimeoutMillis()
                if (
                    aceLiveMediaIsStalled(
                        startupComplete = startup.isCompleted,
                        lastMediaAtMillis = lastMediaAppendAt.get(),
                        nowMillis = now,
                        timeoutMillis = stallTimeoutMillis
                    )
                ) {
                    error(
                        "Ace Live swarm stopped publishing media for " +
                            "$stallTimeoutMillis ms"
                    )
                }
                val head = latestHead.get()
                if (head >= 0L) {
                    pool.scheduleAndDispatch(head)
                    val recovery = pool.evaluateRecovery()
                    recovery.cursorAdvance?.let { advance ->
                        val applied = pool.applyRecoveryAdvance(advance)
                        Log.w(
                            LOG_TAG,
                            "event=recovery_advance from=${advance.fromPiece} to=${advance.toPiece} " +
                                "gap_beyond_limit=${recovery.gapBeyondAdvanceLimit}"
                        )
                        if (applied.outputDiscontinuity != null) resynchronizer.reset()
                        emitPieces(applied.emittedPieces)
                    }
                }
                delay(SCHEDULER_TICK_MILLIS)
            }
        }

        private fun onPoolEvent(event: AceLiveTcpPoolEvent) {
            refillCoordinator.onPoolEvent(event, System.currentTimeMillis())
            runCatching { eventObserver(event) }
            if (closed.get()) return
            if (event !is AceLiveTcpPoolEvent.Ingress) {
                when (event) {
                    is AceLiveTcpPoolEvent.TransportConnected -> {
                        connectedAtLeastOnce.set(true)
                        Log.i(
                            LOG_TAG,
                            "event=peer_connected peer=${event.peerId} reconnect=${event.reconnectAttempt} " +
                                "elapsed_ms=${startupElapsedMillis()}"
                        )
                    }
                    is AceLiveTcpPoolEvent.HandshakeAccepted -> Log.i(
                        LOG_TAG,
                        "event=peer_ready peer=${event.peerId} elapsed_ms=${startupElapsedMillis()}"
                    )
                    is AceLiveTcpPoolEvent.HandshakeRejected -> Log.w(
                        LOG_TAG,
                        "event=peer_handshake_rejected peer=${event.peerId} reason=${event.reason}"
                    )
                    is AceLiveTcpPoolEvent.ConnectFailed -> Log.w(
                        LOG_TAG,
                        "event=peer_connect_failed peer=${event.peerId} retrying=${event.retrying}"
                    )
                    is AceLiveTcpPoolEvent.Disconnected -> Log.w(
                        LOG_TAG,
                        "event=peer_disconnected peer=${event.peerId} reason=${event.reason} " +
                            "retrying=${event.retrying} requeued=${event.requeuedPieces.size}"
                    )
                    is AceLiveTcpPoolEvent.Ingress -> Unit
                }
                return
            }

            event.result.metadataUpdates.forEach { window ->
                latestHead.accumulateAndGet(window.maxPiece) { current, update -> maxOf(current, update) }
            }
            if (
                event.result.metadataUpdates.isNotEmpty() &&
                claimThrottledLog(lastWindowLogAt)
            ) {
                val window = event.result.metadataUpdates.last()
                Log.i(
                    LOG_TAG,
                    "event=window_update peer=${event.peerId} min=${window.minPiece} " +
                        "max=${window.maxPiece} position=${window.position} " +
                        "elapsed_ms=${startupElapsedMillis()}"
                )
            }
            val hasNewUnknownMessageId = event.result.unknownMessageIds
                .any(loggedUnknownMessageIds::add)
            if (hasNewUnknownMessageId) {
                Log.i(
                    LOG_TAG,
                    "event=unknown_messages peer=${event.peerId} " +
                        "details=${event.result.unknownMessages.distinctBy { item -> item.id }
                            .joinToString(",") { item ->
                                "${item.id}:${item.payloadBytes}:${item.payloadPrefixHex}:" +
                                    (item.bencodeSummary ?: "-")
                            }}"
                )
            }
            logProgress(event)
            emitPieces(event.result.emittedPieces)
        }

        private fun logProgress(event: AceLiveTcpPoolEvent.Ingress) {
            val pieces = event.result.emittedPieces
            if (pieces.isEmpty()) return
            val totalBytes = emittedBytes.addAndGet(pieces.sumOf { piece -> piece.data.size.toLong() })
            val now = System.currentTimeMillis()
            val previous = lastProgressLogAt.get()
            if (previous != 0L && now - previous < PROGRESS_LOG_INTERVAL_MILLIS) return
            if (!lastProgressLogAt.compareAndSet(previous, now)) return
            Log.i(
                LOG_TAG,
                "event=media_progress peer=${event.peerId} pieces=${pieces.size} " +
                    "piece_first=${pieces.first().piece} piece_last=${pieces.last().piece} " +
                    "total_bytes=$totalBytes retained_bytes=${mediaBuffer.retainedBytes()} " +
                    "advertised_head=${latestHead.get()} elapsed_ms=${startupElapsedMillis(now)}"
            )
        }

        private fun claimThrottledLog(lastLogAt: AtomicLong): Boolean {
            val now = System.currentTimeMillis()
            val previous = lastLogAt.get()
            if (previous != 0L && now - previous < PROGRESS_LOG_INTERVAL_MILLIS) return false
            return lastLogAt.compareAndSet(previous, now)
        }

        private fun startupElapsedMillis(nowMillis: Long = System.currentTimeMillis()): Long =
            (nowMillis - startupStartedAtMillis.get()).coerceAtLeast(0L)

        private fun elapsedSinceFirstPeerStartMillis(nowMillis: Long): Long? {
            val startedAt = firstPeerStartAtMillis.get()
            if (startedAt <= 0L) return null
            return (nowMillis - startedAt).coerceAtLeast(0L)
        }

        private fun emitPieces(pieces: List<AceLiveReassembledPiece>) {
            pieces.forEach { piece ->
                when (val verified = authenticator.verifyAndStrip(piece.data)) {
                    is P2pResult.Success -> {
                        val media = resynchronizer.consume(verified.data)
                        if (media.isNotEmpty()) {
                            mediaBuffer.append(media)
                            val now = System.currentTimeMillis()
                            lastMediaAppendAt.set(now)
                            if (!startup.isCompleted) {
                                val decision = startupBufferPolicy.evaluate(
                                    bufferedBytes = mediaBuffer.retainedBytes().toLong(),
                                    elapsedMillis = (now - startupStartedAtMillis.get()).coerceAtLeast(1L)
                                )
                                if (decision.ready) {
                                    Log.i(
                                        LOG_TAG,
                                        "event=startup_buffer_ready buffered_bytes=${mediaBuffer.retainedBytes()} " +
                                            "target_bytes=${decision.targetBytes} " +
                                            "rate_bps=${decision.observedBytesPerSecond} forced=${decision.forced} " +
                                            "elapsed_ms=${startupElapsedMillis(now)}"
                                    )
                                    startup.complete(Unit)
                                }
                            }
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
        const val DIRECT_STARTUP_SOFT_TIMEOUT_MILLIS = 8_000L
        const val NO_CONNECTED_PEER_TIMEOUT_MILLIS = 30_000L
        const val TARGET_ACTIVE_PEERS = 6
        const val MAX_ACTIVE_PEERS = 10
        const val STALE_PROBE_PEERS = 2
        const val MAX_PEER_STARTS_PER_CYCLE = 4
        const val MAX_IN_FLIGHT_PER_PEER = 2
        const val REQUEST_TIMEOUT_MILLIS = 6_000L
        const val STALE_UPSTREAM_TIMEOUT_MILLIS = 18_000L
        const val REQUEST_CHECK_INTERVAL_MILLIS = 1_000L
        const val MAX_REASSEMBLY_PIECES = 12L
        const val MAX_REASSEMBLY_BYTES = 12L * 1024L * 1024L
        const val SCHEDULER_TICK_MILLIS = 200L
        const val PEER_REFRESH_INTERVAL_MILLIS = 10_000L
        const val PROGRESS_LOG_INTERVAL_MILLIS = 5_000L
        const val LOG_TAG = "P2P/AceLive"
    }
}
