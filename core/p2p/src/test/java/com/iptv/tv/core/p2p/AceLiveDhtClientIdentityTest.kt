package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AceLiveDhtClientIdentityTest {
    private val bootstrap = listOf(AceLiveDhtBootstrapNode("router.bittorrent.com", 6881))

    @Test
    fun repeatedLiveLookupRequestsReuseProcessIdentity() {
        val first = AceLiveDhtDiscoveryRequest(
            swarmKey = requireNotNull(AceLiveSwarmKey.parseHex("00112233445566778899aabbccddeeff00112233")),
            bootstrapNodes = bootstrap
        )
        val second = AceLiveDhtDiscoveryRequest(
            swarmKey = requireNotNull(AceLiveSwarmKey.parseHex("ffeeddccbbaa99887766554433221100ffeeddcc")),
            bootstrapNodes = bootstrap
        )

        assertEquals(first.localNodeId, second.localNodeId)
    }

    @Test
    fun liveAndMetadataLookupRequestsShareTheSameDefaultIdentity() {
        val live = AceLiveDhtDiscoveryRequest(
            swarmKey = requireNotNull(AceLiveSwarmKey.parseHex("00112233445566778899aabbccddeeff00112233")),
            bootstrapNodes = bootstrap
        )
        val metadata = AceContentIdDhtDiscoveryRequest(
            contentId = requireNotNull(AceContentIdDhtKey.parseHex("ffeeddccbbaa99887766554433221100ffeeddcc")),
            bootstrapNodes = bootstrap
        )

        assertEquals(live.localNodeId, metadata.localNodeId)
    }

    @Test
    fun explicitTestIdentityStillOverridesProcessDefault() {
        val explicit = AceLiveDhtNodeId.fromBytes(ByteArray(AceLiveDhtNodeId.BYTES) { 0x5a.toByte() })
        val request = AceLiveDhtDiscoveryRequest(
            swarmKey = requireNotNull(AceLiveSwarmKey.parseHex("00112233445566778899aabbccddeeff00112233")),
            bootstrapNodes = bootstrap,
            localNodeId = explicit
        )

        assertEquals(explicit, request.localNodeId)
        assertNotEquals(AceLiveDhtClientIdentity.current(), request.localNodeId)
    }
}
