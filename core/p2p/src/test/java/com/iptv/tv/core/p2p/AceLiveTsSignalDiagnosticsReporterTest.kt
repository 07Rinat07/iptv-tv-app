package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTsSignalDiagnosticsReporterTest {
    @Test
    fun tracksPatPmtVideoAudioAndRandomAccessAcrossArbitraryChunks() {
        val tracker = AceLiveTsSignalTracker()
        val patPackets = splitPsiSectionAcrossPackets(
            pid = 0,
            section = patSection(pmtPid = 0x100),
            firstContinuity = 0
        )
        val pmt = psiPacket(
            pid = 0x100,
            section = pmtSection(
                pcrPid = 0x101,
                videoPid = 0x101,
                audioPid = 0x102
            ),
            continuity = 0
        )
        val video = videoPacket(pid = 0x101, continuity = 0, randomAccess = true)
        val bytes = patPackets + pmt + video

        tracker.consume(bytes.copyOfRange(0, 73))
        tracker.consume(bytes.copyOfRange(73, 317))
        val snapshot = tracker.consume(bytes.copyOfRange(317, bytes.size))

        assertEquals(bytes.size.toLong(), snapshot.observedBytes)
        assertEquals(4L, snapshot.packetCount)
        assertEquals(1L, snapshot.patSectionCount)
        assertEquals(1L, snapshot.pmtSectionCount)
        assertEquals(0x100, snapshot.pmtPid)
        assertEquals(0x101, snapshot.pcrPid)
        assertEquals(0x101, snapshot.videoPid)
        assertEquals(0x1b, snapshot.videoStreamType)
        assertEquals(setOf(0x102), snapshot.audioPids)
        assertEquals(1L, snapshot.randomAccessCount)
        assertEquals(0L, snapshot.syncLossCount)
        assertEquals(0L, snapshot.transportErrorCount)
    }

    @Test
    fun continuityGapAndDuplicateAreObservedButExplicitDiscontinuityResetsExpectation() {
        val tracker = AceLiveTsSignalTracker()
        val pid = 0x121
        val bytes = payloadPacket(pid, continuity = 0) +
            payloadPacket(pid, continuity = 2) +
            payloadPacket(pid, continuity = 2) +
            payloadPacket(pid, continuity = 7, discontinuity = true) +
            payloadPacket(pid, continuity = 8)

        val snapshot = tracker.consume(bytes)

        assertEquals(5L, snapshot.packetCount)
        assertEquals(1L, snapshot.continuityGapCount)
        assertEquals(1L, snapshot.continuityDuplicateCount)
    }

    @Test
    fun transportErrorResetsPidContinuityWithoutInventingGap() {
        val tracker = AceLiveTsSignalTracker()
        val pid = 0x130
        val bytes = payloadPacket(pid, continuity = 0) +
            payloadPacket(pid, continuity = 6, transportError = true) +
            payloadPacket(pid, continuity = 9)

        val snapshot = tracker.consume(bytes)

        assertEquals(1L, snapshot.transportErrorCount)
        assertEquals(0L, snapshot.continuityGapCount)
    }

    @Test
    fun reporterAddsRuntimeCorrelationAndReportsMaterialTsProgress() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveTsSignalDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            context = AceLiveRuntimeDiagnosticsContext(
                startupId = 100L,
                runtimeId = 7L,
                generation = 3L,
                path = "direct"
            ),
            periodicIntervalMillis = 5_000L
        )

        reporter.onBytes(
            psiPacket(0, patSection(0x100), continuity = 0),
            nowMillis = 1_000L
        )
        reporter.onBytes(
            payloadPacket(0x120, continuity = 0),
            nowMillis = 1_200L
        )
        reporter.onBytes(
            psiPacket(
                0x100,
                pmtSection(pcrPid = 0x101, videoPid = 0x101, audioPid = 0x102),
                continuity = 0
            ),
            nowMillis = 1_400L
        )
        reporter.onBytes(
            videoPacket(0x101, continuity = 0, randomAccess = true),
            nowMillis = 1_600L
        )

        assertEquals(3, events.size)
        assertTrue(events.all { it.first == "embedded_ace_live_ts_signal" })
        val last = events.last().second
        assertTrue(last.contains("pmt_pid=256"))
        assertTrue(last.contains("video_pid=257"))
        assertTrue(last.contains("video_type=0x1b"))
        assertTrue(last.contains("audio_pids=258"))
        assertTrue(last.contains("random_access=1"))
        assertTrue(last.contains("startup_id=100"))
        assertTrue(last.contains("runtime_id=7"))
        assertTrue(last.contains("generation=3"))
        assertTrue(last.contains("path=direct"))
    }

    private fun patSection(pmtPid: Int): ByteArray = byteArrayOf(
        0x00, 0xB0.toByte(), 0x0D, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
        0x00, 0x01,
        (0xE0 or ((pmtPid ushr 8) and 0x1f)).toByte(), (pmtPid and 0xff).toByte(),
        0x00, 0x00, 0x00, 0x00
    )

    private fun pmtSection(
        pcrPid: Int,
        videoPid: Int,
        audioPid: Int
    ): ByteArray = byteArrayOf(
        0x02, 0xB0.toByte(), 0x17, 0x00, 0x01, 0xC1.toByte(), 0x00, 0x00,
        (0xE0 or ((pcrPid ushr 8) and 0x1f)).toByte(), (pcrPid and 0xff).toByte(),
        0xF0.toByte(), 0x00,
        0x1B,
        (0xE0 or ((videoPid ushr 8) and 0x1f)).toByte(), (videoPid and 0xff).toByte(),
        0xF0.toByte(), 0x00,
        0x0F,
        (0xE0 or ((audioPid ushr 8) and 0x1f)).toByte(), (audioPid and 0xff).toByte(),
        0xF0.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x00
    )

    private fun splitPsiSectionAcrossPackets(
        pid: Int,
        section: ByteArray,
        firstContinuity: Int
    ): ByteArray {
        val first = tsPacket(
            pid = pid,
            continuity = firstContinuity,
            payloadUnitStart = true,
            adaptationAndPayload = true
        )
        // 171 adaptation bytes leave 12 payload bytes after the pointer field, forcing the PSI
        // section to continue in the next 188-byte packet.
        first[4] = 170.toByte()
        first[5] = 0
        val firstPayloadOffset = 175
        first[firstPayloadOffset] = 0
        val firstSectionBytes = minOf(section.size, first.size - firstPayloadOffset - 1)
        section.copyInto(
            first,
            destinationOffset = firstPayloadOffset + 1,
            endIndex = firstSectionBytes
        )

        val second = tsPacket(
            pid = pid,
            continuity = (firstContinuity + 1) and 0x0f
        )
        section.copyInto(
            second,
            destinationOffset = 4,
            startIndex = firstSectionBytes
        )
        return first + second
    }

    private fun psiPacket(
        pid: Int,
        section: ByteArray,
        continuity: Int
    ): ByteArray = tsPacket(
        pid = pid,
        continuity = continuity,
        payloadUnitStart = true
    ).also { packet ->
        packet[4] = 0
        section.copyInto(packet, destinationOffset = 5)
    }

    private fun videoPacket(
        pid: Int,
        continuity: Int,
        randomAccess: Boolean
    ): ByteArray = payloadPacket(
        pid = pid,
        continuity = continuity,
        randomAccess = randomAccess
    )

    private fun payloadPacket(
        pid: Int,
        continuity: Int,
        discontinuity: Boolean = false,
        randomAccess: Boolean = false,
        transportError: Boolean = false
    ): ByteArray {
        val withAdaptation = discontinuity || randomAccess
        return tsPacket(
            pid = pid,
            continuity = continuity,
            adaptationAndPayload = withAdaptation,
            transportError = transportError
        ).also { packet ->
            if (withAdaptation) {
                packet[4] = 1
                packet[5] = (
                    (if (discontinuity) 0x80 else 0) or
                        (if (randomAccess) 0x40 else 0)
                    ).toByte()
            }
        }
    }

    private fun tsPacket(
        pid: Int,
        continuity: Int,
        payloadUnitStart: Boolean = false,
        adaptationAndPayload: Boolean = false,
        transportError: Boolean = false
    ): ByteArray = ByteArray(188) { 0xFF.toByte() }.also { packet ->
        packet[0] = 0x47
        val highPid = (pid ushr 8) and 0x1f
        packet[1] = (
            highPid or
                (if (payloadUnitStart) 0x40 else 0) or
                (if (transportError) 0x80 else 0)
            ).toByte()
        packet[2] = (pid and 0xff).toByte()
        packet[3] = ((if (adaptationAndPayload) 0x30 else 0x10) or (continuity and 0x0f)).toByte()
    }
}
