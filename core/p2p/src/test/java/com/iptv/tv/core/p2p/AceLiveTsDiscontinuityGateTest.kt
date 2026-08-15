package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTsDiscontinuityGateTest {
    @Test
    fun waitsForFreshPatPmtAndRandomAccessAfterActivation() {
        val gate = AceLiveTsDiscontinuityGate()
        gate.activate()

        assertTrue(gate.consume(tsPacket(0x120).repeatPackets(5)).isEmpty())
        assertTrue(gate.consume(patPacket(0x100)).isEmpty())
        assertTrue(gate.consume(pmtPacket(0x100, 0x101)).isEmpty())

        val output = gate.consume(videoPacket(0x101, randomAccess = true))

        assertTrue(output.isNotEmpty())
        assertEquals(0, packetPid(output, 0))
        assertEquals(0x100, packetPid(output, 188))
        assertEquals(0x101, packetPid(output, 188 * 2))
    }

    @Test
    fun acceptsH264IdrAsCodecLevelRandomAccessEvidence() {
        val gate = AceLiveTsDiscontinuityGate()
        gate.activate()

        assertTrue(gate.consume(patPacket(0x100) + pmtPacket(0x100, 0x101)).isEmpty())
        val output = gate.consume(videoPacket(0x101, h264Idr = true))

        assertTrue(output.isNotEmpty())
        assertEquals(0, packetPid(output, 0))
    }

    @Test
    fun inactiveGateIsTransparent() {
        val bytes = ByteArray(777) { it.toByte() }
        assertTrue(bytes.contentEquals(AceLiveTsDiscontinuityGate().consume(bytes)))
    }

    private fun patPacket(pmtPid: Int): ByteArray = psiPacket(
        pid = 0,
        section = byteArrayOf(
            0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            0x00, 0x01,
            (0xE0 or ((pmtPid ushr 8) and 0x1f)).toByte(), (pmtPid and 0xff).toByte(),
            0x00, 0x00, 0x00, 0x00
        )
    )

    private fun pmtPacket(pmtPid: Int, videoPid: Int): ByteArray = psiPacket(
        pid = pmtPid,
        section = byteArrayOf(
            0x02, 0xB0.toByte(), 0x12, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
            (0xE0 or ((videoPid ushr 8) and 0x1f)).toByte(), (videoPid and 0xff).toByte(),
            0xF0.toByte(), 0x00,
            0x1B,
            (0xE0 or ((videoPid ushr 8) and 0x1f)).toByte(), (videoPid and 0xff).toByte(),
            0xF0.toByte(), 0x00,
            0x00, 0x00, 0x00, 0x00
        )
    )

    private fun psiPacket(pid: Int, section: ByteArray): ByteArray =
        tsPacket(pid, payloadUnitStart = true).also { packet ->
            packet[4] = 0
            section.copyInto(packet, destinationOffset = 5)
        }

    private fun videoPacket(
        pid: Int,
        randomAccess: Boolean = false,
        h264Idr: Boolean = false
    ): ByteArray {
        val packet = tsPacket(pid, adaptationAndPayload = randomAccess)
        var payload = 4
        if (randomAccess) {
            packet[4] = 1
            packet[5] = 0x40
            payload = 6
        }
        if (h264Idr) byteArrayOf(0, 0, 1, 0x65).copyInto(packet, payload)
        return packet
    }

    private fun tsPacket(
        pid: Int,
        payloadUnitStart: Boolean = false,
        adaptationAndPayload: Boolean = false
    ): ByteArray = ByteArray(188) { 0xFF.toByte() }.also { packet ->
        packet[0] = 0x47
        packet[1] = (((pid ushr 8) and 0x1f) or if (payloadUnitStart) 0x40 else 0).toByte()
        packet[2] = (pid and 0xff).toByte()
        packet[3] = if (adaptationAndPayload) 0x30 else 0x10
    }

    private fun ByteArray.repeatPackets(count: Int): ByteArray =
        ByteArray(size * count).also { output ->
            repeat(count) { index -> copyInto(output, destinationOffset = index * size) }
        }

    private fun packetPid(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset + 1].toInt() and 0x1f) shl 8) or (bytes[offset + 2].toInt() and 0xff)
}
