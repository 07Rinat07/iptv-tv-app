package com.iptv.tv.core.p2p

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLivePeerReputationStoreTest {
    @Test
    fun `producing peer survives store recreation and ranks first`() {
        var now = 100_000L
        val file = Files.createTempDirectory("ace-peer-reputation").resolve("peers.tsv").toFile()
        val swarm = swarm(0x11)
        val endpoint = AceLiveTcpPeerEndpoint("8.8.8.8", 6881)

        FileAceLivePeerReputationStore(file, clockMillis = { now })
            .recordMediaProduced(swarm, endpoint, now)

        val restored = FileAceLivePeerReputationStore(file, clockMillis = { now + 1L })
            .snapshot(swarm, endpoint, now + 1L)

        assertEquals(0, restored?.priorityRank(now + 1L))
        assertEquals(now, restored?.lastProducedAtMillis)
    }

    @Test
    fun `two recent final failures demote candidate without banning it`() {
        var now = 200_000L
        val file = Files.createTempDirectory("ace-peer-reputation").resolve("peers.tsv").toFile()
        val store = FileAceLivePeerReputationStore(file, clockMillis = { now })
        val swarm = swarm(0x22)
        val endpoint = AceLiveTcpPeerEndpoint("1.1.1.1", 6882)

        store.recordFinalFailure(swarm, endpoint, now)
        now += 1_000L
        store.recordFinalFailure(swarm, endpoint, now)

        val snapshot = store.snapshot(swarm, endpoint, now)
        assertEquals(2, snapshot?.consecutiveFailures)
        assertEquals(3, snapshot?.priorityRank(now))
    }

    @Test
    fun `new successful handshake clears failure demotion`() {
        var now = 300_000L
        val file = Files.createTempDirectory("ace-peer-reputation").resolve("peers.tsv").toFile()
        val store = FileAceLivePeerReputationStore(file, clockMillis = { now })
        val swarm = swarm(0x33)
        val endpoint = AceLiveTcpPeerEndpoint("9.9.9.9", 6883)

        store.recordFinalFailure(swarm, endpoint, now)
        now += 1_000L
        store.recordFinalFailure(swarm, endpoint, now)
        now += 1_000L
        store.recordHandshakeAccepted(swarm, endpoint, now)

        val snapshot = store.snapshot(swarm, endpoint, now)
        assertEquals(0, snapshot?.consecutiveFailures)
        assertEquals(1, snapshot?.priorityRank(now))
    }

    @Test
    fun `reputation is isolated by exact swarm key`() {
        val now = 400_000L
        val file = Files.createTempDirectory("ace-peer-reputation").resolve("peers.tsv").toFile()
        val store = FileAceLivePeerReputationStore(file, clockMillis = { now })
        val endpoint = AceLiveTcpPeerEndpoint("8.8.4.4", 6884)

        store.recordMediaProduced(swarm(0x44), endpoint, now)

        assertNull(store.snapshot(swarm(0x45), endpoint, now))
        assertEquals(0, store.snapshot(swarm(0x44), endpoint, now)?.priorityRank(now))
    }

    private fun swarm(value: Int): ByteArray = ByteArray(
        AceLivePeerHandshakeCodec.SWARM_KEY_BYTES
    ) { value.toByte() }
}
