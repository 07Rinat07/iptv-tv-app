package com.iptv.tv.core.p2p

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class AceLiveTransportCandidateConnector(
    val name: String,
    val connect: suspend (
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ) -> AceLiveTcpTransport
) {
    init {
        require(name.isNotBlank()) { "transport candidate name must not be blank" }
    }
}

/**
 * Races physical TCP/uTP acquisition but delays transport ownership until a candidate returns a
 * swarm-valid public Ace handshake. The first local write is the existing Ace handshake emitted by
 * [AceLiveTcpConnectionPool]; it is mirrored to every live candidate and also to a candidate that
 * finishes connecting while qualification is already in progress.
 */
internal class AceLiveTcpUtpRacingTransportFactory(
    tcpConnect: suspend (
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ) -> AceLiveTcpTransport,
    utpConnect: suspend (
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ) -> AceLiveTcpTransport,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val handshakeCodec: AceLivePeerHandshakeCodec = AceLivePeerHandshakeCodec()
) : AceLiveTcpTransportFactory {
    private val candidates = listOf(
        AceLiveTransportCandidateConnector("tcp", tcpConnect),
        AceLiveTransportCandidateConnector("utp", utpConnect)
    )

    override suspend fun connect(
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ): AceLiveTcpTransport {
        val transport = AceLiveHandshakeQualifiedTransportRace(
            endpoint = endpoint,
            policy = policy,
            candidateConnectors = candidates,
            ioDispatcher = ioDispatcher,
            handshakeCodec = handshakeCodec
        )
        return try {
            transport.awaitFirstPhysicalConnection()
            transport
        } catch (cancelled: CancellationException) {
            transport.close()
            throw cancelled
        } catch (error: Throwable) {
            transport.close()
            throw error
        }
    }
}

/** Outbound uTP candidate using the same discovered peer host/port as the TCP candidate. */
internal class JvmAceLiveUtpTransportFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val addressResolver: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val connectAddress: suspend (
        address: InetAddress,
        port: Int
    ) -> AceLiveTcpTransport? = { address, port ->
        AceLiveUtpSocketConnector(ioDispatcher = ioDispatcher)
            .connect(AceLiveUtpSocketEndpoint(address, port))
            ?.let(::AceLiveUtpByteStreamTransport)
    }
) : AceLiveTcpTransportFactory {
    override suspend fun connect(
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ): AceLiveTcpTransport {
        val addresses = aceLiveHappyEyeballsOrder(addressResolver(endpoint.host))
            .take(MAX_RESOLVED_ADDRESSES)
        if (addresses.isEmpty()) {
            throw UnknownHostException("No addresses resolved for ${endpoint.host}")
        }
        return withTimeoutOrNull(policy.connectTimeoutMillis.toLong()) {
            var lastFailure: Throwable? = null
            for (address in addresses) {
                try {
                    val transport = connectAddress(address, endpoint.port)
                    if (transport != null) return@withTimeoutOrNull transport
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastFailure = error
                }
            }
            throw lastFailure ?: IOException("uTP peer did not accept the connection")
        } ?: throw IOException("uTP connect exceeded ${policy.connectTimeoutMillis} ms")
    }

    private companion object {
        const val MAX_RESOLVED_ADDRESSES = 2
    }
}

private class AceLiveHandshakeQualifiedTransportRace(
    private val endpoint: AceLiveTcpPeerEndpoint,
    private val policy: AceLiveTcpConnectionPolicy,
    candidateConnectors: List<AceLiveTransportCandidateConnector>,
    private val ioDispatcher: CoroutineDispatcher,
    private val handshakeCodec: AceLivePeerHandshakeCodec
) : AceLiveTcpTransport {
    private class CandidateState(
        val connector: AceLiveTransportCandidateConnector
    ) {
        val transport = AtomicReference<AceLiveTcpTransport?>(null)
        val terminal = AtomicBoolean(false)
        val handshakeSent = AtomicBoolean(false)
        val qualifierStarted = AtomicBoolean(false)
        val connectJob = AtomicReference<Job?>(null)
    }

    private val candidateStates = candidateConnectors.map(::CandidateState)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val closed = AtomicBoolean(false)
    private val firstConnected = CompletableDeferred<Unit>()
    private val firstHandshakeSent = CompletableDeferred<Unit>()
    private val winnerReady = CompletableDeferred<CandidateState>()
    private val winner = AtomicReference<CandidateState?>(null)
    private val localHandshake = AtomicReference<ByteArray?>(null)
    private val expectedSwarmKey = AtomicReference<ByteArray?>(null)
    private val winnerPrebuffer = AtomicReference<ByteArray?>(null)
    private val terminalCount = AtomicInteger(0)
    private val lastFailure = AtomicReference<Throwable?>(null)
    private val writeMutex = Mutex()
    private val readMutex = Mutex()
    private var winnerPrebufferOffset = 0

    init {
        require(candidateStates.isNotEmpty()) { "at least one transport candidate is required" }
        candidateStates.forEach(::startCandidate)
    }

    suspend fun awaitFirstPhysicalConnection() {
        val connected = withTimeoutOrNull(policy.connectTimeoutMillis.toLong()) {
            firstConnected.await()
            true
        } ?: false
        if (!connected) {
            throw IOException("transport race exceeded ${policy.connectTimeoutMillis} ms")
        }
    }

    override suspend fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty()) { "read buffer must not be empty" }
        return readMutex.withLock {
            checkOpen()
            val selected = winnerReady.await()
            drainWinnerPrebuffer(buffer).takeIf { it > 0 }?.let { return@withLock it }
            selected.transport.get()?.read(buffer)
                ?: throw IOException("qualified transport disappeared")
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        writeMutex.withLock {
            checkOpen()
            if (winner.get() != null) {
                val selected = winnerReady.await()
                selected.transport.get()?.write(bytes)
                    ?: throw IOException("qualified transport disappeared")
                return@withLock
            }

            val existingHandshake = localHandshake.get()
            if (existingHandshake == null) {
                val decoded = handshakeCodec.decode(bytes)
                val handshake = decoded as? AceLivePeerHandshakeDecodeResult.Decoded
                    ?: throw IOException("first transport-race write must be a complete Ace handshake")
                if (handshake.consumedBytes != bytes.size) {
                    throw IOException("first transport-race write must contain only the Ace handshake")
                }
                localHandshake.set(bytes.copyOf())
                expectedSwarmKey.set(handshake.handshake.swarmKeyBytes())
                candidateStates.forEach(::sendHandshakeIfConnected)
            } else if (!existingHandshake.contentEquals(bytes)) {
                throw IOException("transport winner is not qualified yet")
            }

            firstHandshakeSent.await()
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        withContext(NonCancellable + ioDispatcher) {
            candidateStates.forEach { state ->
                state.connectJob.get()?.cancel()
                state.transport.getAndSet(null)?.let { transport ->
                    runCatching { transport.close() }
                }
            }
        }
    }

    private fun startCandidate(state: CandidateState) {
        val job = scope.launch {
            try {
                val transport = state.connector.connect(endpoint, policy)
                if (closed.get() || winner.get() != null) {
                    runCatching { transport.close() }
                    return@launch
                }
                state.transport.set(transport)
                firstConnected.complete(Unit)
                sendHandshakeIfConnected(state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                markTerminal(state, error)
            }
        }
        state.connectJob.set(job)
    }

    private fun sendHandshakeIfConnected(state: CandidateState) {
        val handshake = localHandshake.get() ?: return
        val transport = state.transport.get() ?: return
        if (!state.handshakeSent.compareAndSet(false, true)) return

        scope.launch {
            try {
                val sent = withTimeoutOrNull(policy.writeTimeoutMillis.toLong()) {
                    transport.write(handshake)
                    true
                } ?: false
                if (!sent) {
                    markTerminal(state, IOException("${state.connector.name} handshake write timed out"))
                    return@launch
                }
                firstHandshakeSent.complete(Unit)
                startQualifier(state)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                markTerminal(state, error)
            }
        }
    }

    private fun startQualifier(state: CandidateState) {
        if (!state.qualifierStarted.compareAndSet(false, true)) return
        scope.launch {
            val transport = state.transport.get() ?: return@launch
            val expected = expectedSwarmKey.get()
                ?: return@launch markTerminal(state, IOException("Ace swarm key is unavailable"))
            val readBuffer = ByteArray(policy.readBufferBytes)
            var accumulated = ByteArray(0)
            try {
                while (!closed.get() && winner.get() == null) {
                    val count = transport.read(readBuffer)
                    if (count < 0) {
                        markTerminal(state, IOException("${state.connector.name} closed during Ace handshake"))
                        return@launch
                    }
                    if (count == 0) continue
                    require(count <= readBuffer.size) { "transport returned more bytes than requested" }
                    if (accumulated.size > policy.readBufferBytes - count) {
                        markTerminal(state, IOException("Ace handshake prebuffer exceeded the read bound"))
                        return@launch
                    }
                    accumulated += readBuffer.copyOf(count)
                    when (val decoded = handshakeCodec.decode(accumulated, expected)) {
                        is AceLivePeerHandshakeDecodeResult.NeedMoreData -> Unit
                        is AceLivePeerHandshakeDecodeResult.Rejected -> {
                            markTerminal(
                                state,
                                IOException("${state.connector.name} rejected Ace handshake: ${decoded.reason}")
                            )
                            return@launch
                        }
                        is AceLivePeerHandshakeDecodeResult.Decoded -> {
                            selectWinner(state, accumulated)
                            return@launch
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                markTerminal(state, error)
            }
        }
    }

    private suspend fun selectWinner(state: CandidateState, prebuffer: ByteArray) {
        if (!winner.compareAndSet(null, state)) return
        winnerPrebuffer.set(prebuffer.copyOf())
        withContext(NonCancellable) {
            candidateStates
                .asSequence()
                .filter { candidate -> candidate !== state }
                .forEach { loser ->
                    loser.connectJob.get()?.cancel()
                    loser.transport.getAndSet(null)?.let { transport ->
                        runCatching { transport.close() }
                    }
                }
        }
        winnerReady.complete(state)
    }

    private fun markTerminal(state: CandidateState, error: Throwable) {
        if (!state.terminal.compareAndSet(false, true)) return
        lastFailure.set(error)
        scope.launch {
            state.transport.getAndSet(null)?.let { transport ->
                runCatching { transport.close() }
            }
        }
        if (terminalCount.incrementAndGet() == candidateStates.size && winner.get() == null) {
            val failure = lastFailure.get() ?: IOException("all transport candidates failed")
            firstConnected.completeExceptionally(failure)
            firstHandshakeSent.completeExceptionally(failure)
            winnerReady.completeExceptionally(failure)
        }
    }

    private fun drainWinnerPrebuffer(target: ByteArray): Int {
        val buffered = winnerPrebuffer.get() ?: return 0
        if (winnerPrebufferOffset >= buffered.size) {
            winnerPrebuffer.set(null)
            winnerPrebufferOffset = 0
            return 0
        }
        val count = minOf(target.size, buffered.size - winnerPrebufferOffset)
        buffered.copyInto(
            destination = target,
            startIndex = winnerPrebufferOffset,
            endIndex = winnerPrebufferOffset + count
        )
        winnerPrebufferOffset += count
        if (winnerPrebufferOffset == buffered.size) {
            winnerPrebuffer.set(null)
            winnerPrebufferOffset = 0
        }
        return count
    }

    private fun checkOpen() {
        if (closed.get()) throw IOException("transport race is closed")
    }
}
