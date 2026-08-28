package com.iptv.tv.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceDhtNodeIdSecurityTest {
    @Test
    fun `BEP42 published IPv4 vectors are accepted`() {
        val vectors = listOf(
            "124.31.75.21" to "5fbfbff10c5d6a4ec8a88e4c6ab4c28b95eee401",
            "21.75.31.124" to "5a3ce9c14e7a08645677bbd1cfe7d8f956d53256",
            "65.23.51.170" to "a5d43220bc8f112a3d426c84764f8c2a1150e616",
            "84.124.73.14" to "1b0321dd1bb1fe518101ceef99462b947a01ff41",
            "43.213.53.83" to "e56f6cbf5b7c4be0237986d5243b87aa6d51305a"
        )

        vectors.forEach { (host, hex) ->
            assertTrue(AceDhtNodeIdSecurity.isValidWriteTarget(nodeId(hex), host))
        }
    }

    @Test
    fun `global node with arbitrary ID is not a DHT write target`() {
        assertFalse(
            AceDhtNodeIdSecurity.isValidWriteTarget(
                nodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x42 }),
                host = "124.31.75.21"
            )
        )
    }

    @Test
    fun `BEP42 local-address exemptions remain eligible in explicit local tests`() {
        val arbitrary = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x42 })
        assertTrue(AceDhtNodeIdSecurity.isValidWriteTarget(arbitrary, "10.1.2.3"))
        assertTrue(AceDhtNodeIdSecurity.isValidWriteTarget(arbitrary, "172.16.2.3"))
        assertTrue(AceDhtNodeIdSecurity.isValidWriteTarget(arbitrary, "192.168.2.3"))
        assertTrue(AceDhtNodeIdSecurity.isValidWriteTarget(arbitrary, "169.254.2.3"))
        assertTrue(AceDhtNodeIdSecurity.isValidWriteTarget(arbitrary, "127.0.0.1"))
    }

    private fun nodeId(hex: String): AceLiveDhtNodeId {
        require(hex.length == 40)
        val bytes = ByteArray(20) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return AceLiveDhtNodeId.fromBytes(bytes)
    }
}
