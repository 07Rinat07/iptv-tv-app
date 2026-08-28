package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun threeMatchingObservationsFromDistinctResponderPrefixesRotateFutureIdentity() {
        val initial = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x11 })
        val manager = AceLiveDhtClientIdentityManager(
            initialNodeId = initial,
            randomNodeIdBytes = { ByteArray(20) { index -> (index + 1).toByte() } }
        )

        manager.observe("8.8.8.8", "1.1.1.1")
        manager.observe("8.8.8.8", "9.9.9.9")
        assertEquals(initial, manager.current())

        manager.observe("8.8.8.8", "4.4.4.4")
        val rotated = manager.current()

        assertNotEquals(initial, rotated)
        assertTrue(AceDhtNodeIdSecurity.isValidWriteTarget(rotated, "8.8.8.8"))
    }

    @Test
    fun repeatedVotesFromSameResponderPrefixDoNotReachConsensus() {
        val initial = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x22 })
        val manager = AceLiveDhtClientIdentityManager(
            initialNodeId = initial,
            randomNodeIdBytes = { ByteArray(20) { 0x33 } }
        )

        manager.observe("8.8.4.4", "1.2.3.4")
        manager.observe("8.8.4.4", "1.2.3.55")
        manager.observe("8.8.4.4", "1.2.3.200")

        assertEquals(initial, manager.current())
    }

    @Test
    fun privateOrDocumentationAddressCannotRotateIdentity() {
        val initial = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
        val manager = AceLiveDhtClientIdentityManager(
            initialNodeId = initial,
            randomNodeIdBytes = { ByteArray(20) { 0x55 } }
        )
        listOf("1.1.1.1", "9.9.9.9", "4.4.4.4").forEach { responder ->
            manager.observe("192.0.2.7", responder)
        }

        assertEquals(initial, manager.current())
    }
}
