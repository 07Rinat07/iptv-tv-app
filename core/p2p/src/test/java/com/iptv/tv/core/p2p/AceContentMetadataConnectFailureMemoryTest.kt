package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentMetadataConnectFailureMemoryTest {
    @Test
    fun `metadata TCP connection clears stale direct connect failure before handshake`() = runBlocking {
        var nowMillis = 1_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { nowMillis },
            backoffMillis = 5_000L
        )
        val contentId = AceLiveSwarmKey.parseHex("50bc2f512793f1e745fb5bd5b5a6afca199c2d19")!!
        val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9000)
        memory.recordFinalPreHandshakeFailure(
            swarmKey = contentId.toByteArray(),
            endpoint = endpoint,
            nowMillis = nowMillis
        )
        assertTrue(!memory.isEligible(contentId.toByteArray(), endpoint, nowMillis))

        val resolver = AceContentMetadataPeerResolver(
            transportFactory = AceLiveTcpTransportFactory { _, _ -> ConnectedButClosedTransport() },
            connectFailureMemory = memory
        )
        val result = resolver.fetchFromPeer(
            endpoint = endpoint,
            contentId = contentId,
            peerId = AceLiveNodeIdentity.peerId(),
            identity = AceLiveNodeIdentity.generate()
        )

        assertTrue(result is P2pResult.Error)
        assertTrue(memory.isEligible(contentId.toByteArray(), endpoint, nowMillis))
    }

    private class ConnectedButClosedTransport : AceLiveTcpTransport {
        override suspend fun read(buffer: ByteArray): Int = -1
        override suspend fun write(bytes: ByteArray) = Unit
        override suspend fun close() = Unit
    }
}
