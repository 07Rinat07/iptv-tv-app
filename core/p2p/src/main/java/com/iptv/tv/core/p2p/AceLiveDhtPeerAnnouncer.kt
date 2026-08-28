package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class AceLiveDhtAnnouncePolicy(
    val requestTimeoutMillis: Int = 1_500,
    val announceBudgetMillis: Long = 4_000,
    val maxTargets: Int = 8,
    val maxConcurrency: Int = 4
) {
    init {
        require(requestTimeoutMillis in 100..30_000)
        require(announceBudgetMillis in requestTimeoutMillis.toLong()..30_000L)
        require(maxTargets in 1..16)
        require(maxConcurrency in 1..maxTargets)
    }
}

internal data class AceLiveDhtAnnounceResult(
    val sent: Int,
    val succeeded: Int
) {
    init {
        require(sent >= 0)
        require(succeeded in 0..sent)
    }
}

/**
 * Bounded BEP-5 write-back for the app's already-owned TCP peer-listener port.
 *
 * Tokens are accepted only from the exact nodes queried during the preceding get_peers walk. No
 * retries are made, failures never invalidate discovery results, and the announcer stays read-only
 * at the DHT/KRPC layer (`ro=1`) because the app still does not accept inbound DHT UDP queries.
 */
internal class AceLiveDhtPeerAnnouncer(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveDhtAnnouncePolicy = AceLiveDhtAnnouncePolicy(),
    private val randomInt: () -> Int = DEFAULT_RANDOM_INT
) {
    suspend fun announce(
        swarmKey: AceLiveSwarmKey,
        localNodeId: AceLiveDhtNodeId,
        peerPort: Int,
        candidates: List<AceDhtWriteTokenCandidate>
    ): AceLiveDhtAnnounceResult = withContext(ioDispatcher) {
        require(peerPort in 1..65535)
        val targets = candidates.take(policy.maxTargets)
        if (targets.isEmpty()) return@withContext AceLiveDhtAnnounceResult(0, 0)

        val deadlineNanos = System.nanoTime() + policy.announceBudgetMillis * NANOS_PER_MILLI
        var sent = 0
        var succeeded = 0
        for (batch in targets.chunked(policy.maxConcurrency)) {
            currentCoroutineContext().ensureActive()
            if (remainingBudgetMillis(deadlineNanos) <= 0) break
            val outcomes = coroutineScope {
                batch.map { candidate ->
                    async {
                        announceOne(
                            swarmKey = swarmKey,
                            localNodeId = localNodeId,
                            peerPort = peerPort,
                            candidate = candidate,
                            deadlineNanos = deadlineNanos
                        )
                    }
                }.awaitAll()
            }
            sent += outcomes.count { it.attempted }
            succeeded += outcomes.count { it.succeeded }
        }
        AceLiveDhtAnnounceResult(sent, succeeded)
    }

    private suspend fun announceOne(
        swarmKey: AceLiveSwarmKey,
        localNodeId: AceLiveDhtNodeId,
        peerPort: Int,
        candidate: AceDhtWriteTokenCandidate,
        deadlineNanos: Long
    ): AttemptOutcome {
        val timeout = receiveTimeoutMillis(deadlineNanos) ?: return AttemptOutcome(false, false)
        currentCoroutineContext().ensureActive()
        val transactionId = nextTransactionId()
        val query = AceLiveDhtCodec.encodeAnnouncePeerQuery(
            transactionId = transactionId,
            nodeId = localNodeId,
            swarmKey = swarmKey,
            peerPort = peerPort,
            token = candidate.token
        )
        val address = InetSocketAddress(candidate.endpoint.host, candidate.endpoint.port)

        return try {
            DatagramSocket().use { socket ->
                socket.connect(address)
                socket.soTimeout = timeout
                socket.send(DatagramPacket(query, query.size))
                val responseBytes = receiveBounded(socket)
                val response = AceLiveDhtCodec.decodeAnnouncePeerResponse(
                    bytes = responseBytes,
                    expectedTransactionId = transactionId
                )
                AttemptOutcome(
                    attempted = true,
                    succeeded = response.remoteNodeId == candidate.nodeId
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AttemptOutcome(attempted = true, succeeded = false)
        }
    }

    private suspend fun receiveBounded(socket: DatagramSocket): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val buffer = ByteArray(AceLiveDhtCodec.DEFAULT_MAX_PACKET_BYTES + 1)
            val packet = DatagramPacket(buffer, buffer.size)
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            try {
                socket.receive(packet)
                if (packet.length > AceLiveDhtCodec.DEFAULT_MAX_PACKET_BYTES) {
                    throw AceLiveDhtProtocolException("KRPC response exceeds local packet cap")
                }
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                if (continuation.isActive) continuation.resume(bytes)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private fun receiveTimeoutMillis(deadlineNanos: Long): Int? {
        val remaining = remainingBudgetMillis(deadlineNanos)
        if (remaining <= 0) return null
        return min(policy.requestTimeoutMillis.toLong(), remaining).coerceAtLeast(1).toInt()
    }

    private fun remainingBudgetMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0)

    private fun nextTransactionId(): ByteArray {
        val value = randomInt()
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private data class AttemptOutcome(val attempted: Boolean, val succeeded: Boolean)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        val random = SecureRandom()
        val DEFAULT_RANDOM_INT: () -> Int = { random.nextInt() }
    }
}
