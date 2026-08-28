package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveDiscoveryHttpProtocolTest {
    @Test
    fun buildsBep3HttpAnnounceWithBinaryEscapingAndExistingQuery() {
        val swarm = AceLiveSwarmKey.parseHex("00112233445566778899aabbccddeeff00112233")!!
        val peerId = ByteArray(20) { index -> index.toByte() }

        val url = AceLiveDiscoveryHttpProtocol.buildHttpTrackerAnnounceUrl(
            rawUrl = "https://tracker.example/announce?passkey=abc",
            swarmKey = swarm,
            peerId = peerId,
            announcePort = 8621,
            key = 0x12345678,
            numWant = 50
        )

        assertNotNull(url)
        val value = requireNotNull(url)
        assertTrue(value.startsWith("https://tracker.example/announce?passkey=abc&"))
        assertTrue(
            value.contains(
                "info_hash=%00%11%22%33%44%55%66%77%88%99%AA%BB%CC%DD%EE%FF%00%11%22%33"
            )
        )
        assertTrue(value.contains("peer_id=%00%01%02%03%04%05%06%07%08%09%0A%0B%0C%0D%0E%0F%10%11%12%13"))
        assertTrue(value.contains("port=8621"))
        assertTrue(value.contains("left=${Long.MAX_VALUE}"))
        assertTrue(value.contains("compact=1"))
        assertTrue(value.contains("event=started"))
        assertTrue(value.contains("numwant=50"))
    }

    @Test
    fun decodesCompactAndExpandedHttpTrackerPeers() {
        val compactPeers = byteArrayOf(
            1, 2, 3, 4, 0x1A, 0xE1.toByte(),
            5, 6, 7, 8, 0x21, 0xAC.toByte()
        )
        val compactResponse = AceBencodeEncoder.encode(
            AceBencodeValue.Dictionary(
                mapOf(
                    "interval" to AceBencodeValue.Integer(1800),
                    "peers" to AceBencodeValue.Bytes(compactPeers)
                )
            )
        )

        assertEquals(
            listOf(
                AceLiveTcpPeerEndpoint("1.2.3.4", 6881),
                AceLiveTcpPeerEndpoint("5.6.7.8", 8620)
            ),
            AceLiveDiscoveryHttpProtocol.decodeHttpTrackerResponse(
                bytes = compactResponse,
                maxPeers = 8,
                maxResponseBytes = 4096
            )
        )

        val expandedResponse = AceBencodeEncoder.encode(
            AceBencodeValue.Dictionary(
                mapOf(
                    "interval" to AceBencodeValue.Integer(1800),
                    "peers" to AceBencodeValue.ListValue(
                        listOf(
                            AceBencodeValue.Dictionary(
                                mapOf(
                                    "ip" to bytes("9.9.9.9"),
                                    "port" to AceBencodeValue.Integer(9999)
                                )
                            )
                        )
                    )
                )
            )
        )
        assertEquals(
            listOf(AceLiveTcpPeerEndpoint("9.9.9.9", 9999)),
            AceLiveDiscoveryHttpProtocol.decodeHttpTrackerResponse(
                bytes = expandedResponse,
                maxPeers = 8,
                maxResponseBytes = 4096
            )
        )
    }

    @Test
    fun decodesAceMetatrackerContractAndDiscoveryHints() {
        val json = """
            {
              "trackers": ["udp:\/\/tracker.example.org:2710\/announce", "https:\/\/tracker2.example\/announce"],
              "startup_nodes": ["8.8.8.8:8621", "1.1.1.1:8632"],
              "interval": 3600,
              "ignored": {"enabled": true}
            }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val snapshot = AceLiveDiscoveryHttpProtocol.decodeMetatrackerResponse(json)

        assertEquals(
            listOf(
                "udp://tracker.example.org:2710/announce",
                "https://tracker2.example/announce"
            ),
            snapshot.trackers
        )
        assertEquals(listOf("8.8.8.8:8621", "1.1.1.1:8632"), snapshot.startupNodes)
        assertEquals(3600L, snapshot.intervalSeconds)

        val startupHint = AceLiveDiscoveryHttpProtocol.encodeStartupHint("8.8.8.8:8621")
        assertEquals("ace-startup:8.8.8.8:8621", startupHint)
        assertEquals(
            AceLiveTcpPeerEndpoint("8.8.8.8", 8621),
            AceLiveDiscoveryHttpProtocol.parseStartupHint(requireNotNull(startupHint))
        )
        assertEquals(
            "ace-metatracker:https://meta.example/discovery",
            AceLiveDiscoveryHttpProtocol.encodeMetatrackerHint("https://meta.example/discovery")
        )
        assertFalse(AceLiveDiscoveryHttpProtocol.isHttpTracker("udp://tracker.example:2710/announce"))
        assertTrue(AceLiveDiscoveryHttpProtocol.isHttpTracker("https://tracker.example/announce"))
    }

    @Test
    fun metatrackerRequestUsesHexInfohash() {
        val swarm = AceLiveSwarmKey.parseHex("00112233445566778899aabbccddeeff00112233")!!
        val url = AceLiveDiscoveryHttpProtocol.buildMetatrackerRequestUrl(
            "https://meta.example/api?token=x",
            swarm
        )
        assertEquals(
            "https://meta.example/api?token=x&infohash=00112233445566778899aabbccddeeff00112233",
            url
        )
    }

    private fun bytes(value: String) =
        AceBencodeValue.Bytes(value.toByteArray(StandardCharsets.US_ASCII))
}
