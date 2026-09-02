package com.iptv.tv.core.p2p

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerRefillWakeupTest {
    @Test
    fun `only acquisition relevant pool events request refill wakeup`() {
        val pexPeer = endpoint("192.0.2.10", 8621)

        assertTrue(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.Ingress(
                    peerId = 1L,
                    result = AceLivePeerIngressResult(peerExchangePeers = listOf(pexPeer))
                )
            )
        )
        assertFalse(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.Ingress(
                    peerId = 1L,
                    result = AceLivePeerIngressResult()
                )
            )
        )
        assertTrue(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.ConnectFailed(peerId = 1L, retrying = false)
            )
        )
        assertFalse(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.ConnectFailed(peerId = 1L, retrying = true)
            )
        )
        assertTrue(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.Disconnected(
                    peerId = 1L,
                    reason = AceLiveTcpDisconnectReason.REMOTE_CLOSED,
                    requeuedPieces = emptyList(),
                    retrying = false
                )
            )
        )
        assertFalse(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.Disconnected(
                    peerId = 1L,
                    reason = AceLiveTcpDisconnectReason.REMOTE_CLOSED,
                    requeuedPieces = emptyList(),
                    retrying = true
                )
            )
        )
    }

    @Test
    fun `pex wakeup interrupts periodic wait and starts known candidate`() = runBlocking {
        val known = endpoint("192.0.2.20", 8621)
        val coordinator = coordinator(target = 2, max = 2, maxStarts = 1)
        val active = linkedSetOf(40L)
        val firstDiscovery = CompletableDeferred<Unit>()
        val started = CompletableDeferred<AceLiveTcpPeerEndpoint>()
        var discoveryCalls = 0
        var nextPeerId = 100L
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discoveryCalls += 1
                firstDiscovery.complete(Unit)
                emptyDiscovery()
            },
            activePeerIds = { active.toSet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { nextPeerId++ },
            startPeer = { peerId, peer ->
                active += peerId
                started.complete(peer)
            },
            clockMillis = { 1_000L }
        )

        val runner = launch { loop.run() }
        try {
            firstDiscovery.await()
            coordinator.onPoolEvent(
                AceLiveTcpPoolEvent.Ingress(
                    peerId = 40L,
                    result = AceLivePeerIngressResult(peerExchangePeers = listOf(known))
                ),
                nowMillis = 1_000L
            )
            loop.requestWakeup()

            assertEquals(known, withTimeout(1_000L) { started.await() })
            assertEquals(1, discoveryCalls)
        } finally {
            runner.cancelAndJoin()
        }
    }

    @Test
    fun `wakeups coalesce while a refill cycle is busy`() = runBlocking {
        val coordinator = coordinator(target = 1, max = 1, maxStarts = 1)
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        var discoveryCalls = 0
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discoveryCalls += 1
                when (discoveryCalls) {
                    1 -> {
                        firstEntered.complete(Unit)
                        firstRelease.await()
                    }
                    2 -> {
                        secondEntered.complete(Unit)
                        secondRelease.await()
                    }
                }
                emptyDiscovery()
            },
            activePeerIds = { emptySet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { 1L },
            startPeer = { _, _ -> Unit },
            clockMillis = { 2_000L }
        )

        val runner = launch { loop.run() }
        try {
            firstEntered.await()
            repeat(8) { loop.requestWakeup() }
            firstRelease.complete(Unit)

            withTimeout(1_000L) { secondEntered.await() }
            secondRelease.complete(Unit)
            delay(100L)

            assertEquals(2, discoveryCalls)
        } finally {
            runner.cancelAndJoin()
        }
    }

    @Test
    fun `direct and background refill cycles are serialized`() = runBlocking {
        val coordinator = coordinator(target = 1, max = 1, maxStarts = 1)
        val firstEntered = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        var discoveryCalls = 0
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discoveryCalls += 1
                if (discoveryCalls == 1) {
                    firstEntered.complete(Unit)
                    firstRelease.await()
                } else {
                    secondEntered.complete(Unit)
                }
                emptyDiscovery()
            },
            activePeerIds = { emptySet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { 1L },
            startPeer = { _, _ -> Unit },
            clockMillis = { 3_000L }
        )

        val runner = launch { loop.run() }
        try {
            firstEntered.await()
            val direct = launch { loop.runOneCycle() }
            yield()

            assertFalse(secondEntered.isCompleted)
            assertEquals(1, discoveryCalls)

            firstRelease.complete(Unit)
            withTimeout(1_000L) {
                secondEntered.await()
                direct.join()
            }
            assertEquals(2, discoveryCalls)
        } finally {
            runner.cancelAndJoin()
        }
    }

    @Test
    fun `terminal outbound loss is emitted only after pool capacity is released`() {
        val body = sourceFile("AceLiveTcpConnectionPool.kt")
            .substringAfter("private suspend fun runPeer(runtime: PeerRuntime)")
            .substringBefore("private suspend fun runInboundPeer(")

        val terminalEvent = body.indexOf("terminalEvent = event")
        val removal = body.lastIndexOf("peers.remove(runtime.peerId)")
        val intentionalStopGuard = body.indexOf(
            "removed && !runtime.stopRequested && !closed.get()"
        )
        val terminalEmit = body.indexOf("terminalEvent?.let(::emit)")

        assertTrue("terminal event must be captured before cleanup", terminalEvent >= 0)
        assertTrue("outbound capacity must be released after terminal outcome", removal > terminalEvent)
        assertTrue(
            "intentional stop and shutdown must suppress terminal failure publication",
            intentionalStopGuard > removal
        )
        assertTrue(
            "terminal loss must be published only after guarded capacity release",
            terminalEmit > intentionalStopGuard
        )
    }

    @Test
    fun `embedded runtime wires qualifying pool events into refill wakeup`() {
        val source = sourceFile("AceLiveEmbeddedEngine.kt")
        val body = source
            .substringAfter("private fun onPoolEvent(event: AceLiveTcpPoolEvent)")
            .substringBefore("private fun logProgress(event: AceLiveTcpPoolEvent.Ingress)")

        val coordinatorUpdate = body.indexOf("refillCoordinator.onPoolEvent(event, now)")
        val eventFilter = body.indexOf("aceLivePeerRefillEventShouldWake(event)")
        val wakeup = body.indexOf("refillLoop.requestWakeup()")

        assertTrue(coordinatorUpdate >= 0)
        assertTrue(eventFilter > coordinatorUpdate)
        assertTrue(wakeup > eventFilter)
    }

    private fun coordinator(
        target: Int,
        max: Int,
        maxStarts: Int
    ): AceLivePeerRefillCoordinator = AceLivePeerRefillCoordinator(
        policy = AceLivePeerRefillPolicy(
            targetActivePeers = target,
            maxActivePeers = max,
            staleProbePeers = 0,
            maxStartsPerCycle = maxStarts,
            refreshIntervalMillis = 60_000L,
            candidateTtlMillis = 120_000L,
            failureBackoffBaseMillis = 1_000L,
            failureBackoffMaxMillis = 8_000L
        )
    )

    private fun emptyDiscovery(): AceLivePeerDiscoveryOrchestrationResult =
        AceLivePeerDiscoveryOrchestrationResult(
            peers = emptyList(),
            dht = AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
                returnedPeerCount = 0
            ),
            tracker = AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
                returnedPeerCount = 0
            )
        )

    private fun sourceFile(fileName: String): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = cursor ?: return@repeat
            val candidates = listOf(
                File(directory, "src/main/java/com/iptv/tv/core/p2p/$fileName"),
                File(directory, "core/p2p/src/main/java/com/iptv/tv/core/p2p/$fileName")
            )
            candidates.firstOrNull(File::isFile)?.let { return it.readText() }
            cursor = directory.parentFile
        }
        error("$fileName was not found from Gradle test working directory")
    }

    private fun endpoint(host: String, port: Int): AceLiveTcpPeerEndpoint =
        AceLiveTcpPeerEndpoint(host = host, port = port)
}
