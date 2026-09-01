package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Bounded concurrent tracker acquisition based on the same strategy used by mature BitTorrent
 * clients: independent tracker announces must not be serialized behind a dead endpoint.
 *
 * The existing [AceLiveUdpTrackerDiscovery] remains the protocol implementation for UDP/HTTP
 * trackers, Ace metatrackers and startup nodes. This class only schedules independent descriptor
 * sources concurrently and merges their bounded results.
 */
internal class AceLiveParallelTrackerDiscovery(
    private val maxConcurrentSources: Int = DEFAULT_MAX_CONCURRENT_SOURCES,
    private val fastPathMinPeers: Int = DEFAULT_FAST_PATH_MIN_PEERS,
    private val singleSourceDiscover: suspend (AceLiveUdpTrackerDiscoveryRequest) ->
        AceLiveUdpTrackerDiscoveryResult = { request ->
            AceLiveUdpTrackerDiscovery(
                policy = AceLiveUdpTrackerPolicy(
                    requestTimeoutMillis = 1_500,
                    maxRequestAttempts = 2,
                    retryBaseDelayMillis = 150,
                    discoveryBudgetMillis = 6_000
                )
            ).discover(request)
        }
) {
    init {
        require(maxConcurrentSources in 1..32) {
            "maxConcurrentSources must be in 1..32"
        }
        require(fastPathMinPeers in 1..2_048) {
            "fastPathMinPeers must be in 1..2048"
        }
    }

    suspend fun discover(
        request: AceLiveUdpTrackerDiscoveryRequest
    ): AceLiveUdpTrackerDiscoveryResult = supervisorScope {
        val sources = request.trackers
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()

        if (sources.isEmpty()) {
            return@supervisorScope AceLiveUdpTrackerDiscoveryResult(
                peers = emptyList(),
                attemptedTrackers = 0,
                failedTrackers = 0,
                rejectedTrackers = 0
            )
        }
        if (sources.size == 1) {
            return@supervisorScope singleSourceDiscover(requestForSource(request, sources.single()))
        }

        val concurrencyGate = Semaphore(minOf(maxConcurrentSources, sources.size))
        val completedResults = Channel<AceLiveUdpTrackerDiscoveryResult>(Channel.UNLIMITED)
        val jobs = sources.map { source ->
            launch {
                val result = try {
                    concurrencyGate.withPermit {
                        singleSourceDiscover(requestForSource(request, source))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    AceLiveUdpTrackerDiscoveryResult(
                        peers = emptyList(),
                        attemptedTrackers = 1,
                        failedTrackers = 1,
                        rejectedTrackers = 0
                    )
                }
                completedResults.send(result)
            }
        }

        val peers = LinkedHashMap<String, AceLiveTcpPeerEndpoint>()
        var attemptedTrackers = 0
        var failedTrackers = 0
        var rejectedTrackers = 0
        var completedSources = 0

        while (completedSources < jobs.size) {
            val result = completedResults.receive()
            completedSources += 1
            attemptedTrackers += result.attemptedTrackers
            failedTrackers += result.failedTrackers
            rejectedTrackers += result.rejectedTrackers

            for (peer in result.peers) {
                peers.putIfAbsent(endpointKey(peer), peer)
            }

            if (peers.size >= fastPathMinPeers) {
                jobs.forEach { job ->
                    if (job.isActive) job.cancel()
                }
                break
            }
        }

        completedResults.cancel()
        AceLiveUdpTrackerDiscoveryResult(
            peers = peers.values.toList(),
            attemptedTrackers = attemptedTrackers,
            failedTrackers = failedTrackers,
            rejectedTrackers = rejectedTrackers
        )
    }

    private fun requestForSource(
        request: AceLiveUdpTrackerDiscoveryRequest,
        source: String
    ): AceLiveUdpTrackerDiscoveryRequest = AceLiveUdpTrackerDiscoveryRequest(
        swarmKey = request.swarmKey,
        trackers = listOf(source),
        peerId = request.peerId,
        announcePort = request.announcePort
    )

    private fun endpointKey(peer: AceLiveTcpPeerEndpoint): String =
        "${peer.host.lowercase()}:${peer.port}"

    private companion object {
        const val DEFAULT_MAX_CONCURRENT_SOURCES = 8
        const val DEFAULT_FAST_PATH_MIN_PEERS = 24
    }
}
