package com.iptv.tv.core.p2p

/**
 * Bounded MPEG-TS decoder recovery gate used only after an explicit Ace Live output discontinuity.
 *
 * Input must already be TS-resynchronized by [AceLiveMpegTsResynchronizer]. Once activated, bytes
 * are withheld until a fresh PAT identifies a PMT, that PMT identifies a video PID, and the video
 * PID carries either MPEG-TS random_access_indicator or H.264/H.265 random-access NAL evidence.
 */
internal class AceLiveTsDiscontinuityGate {
    private var active = false
    private var packetRemainder = byteArrayOf()
    private var heldOutput = byteArrayOf()
    private var pmtPid: Int? = null
    private var videoPid: Int? = null
    private var videoStreamType: Int? = null
    private var sawPmt = false

    fun activate() {
        active = true
        clearState()
    }

    fun consume(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return byteArrayOf()
        if (!active) return bytes.copyOf()

        val candidate = packetRemainder + bytes
        val completePackets = candidate.size / TS_PACKET_BYTES
        var offset = 0
        repeat(completePackets) {
            val packet = candidate.copyOfRange(offset, offset + TS_PACKET_BYTES)
            val pid = packetPid(packet)

            if (pid == PAT_PID) {
                parsePatPmtPid(packet)?.let { freshPmtPid ->
                    pmtPid = freshPmtPid
                    videoPid = null
                    videoStreamType = null
                    sawPmt = false
                    heldOutput = packet
                }
            } else if (heldOutput.isNotEmpty()) {
                heldOutput += packet
            }

            if (pmtPid != null && pid == pmtPid) {
                parsePmtVideoStream(packet)?.let { stream ->
                    videoPid = stream.pid
                    videoStreamType = stream.streamType
                    sawPmt = true
                }
            }

            if (
                heldOutput.isNotEmpty() &&
                sawPmt &&
                pid == videoPid &&
                hasRandomAccessEvidence(packet, videoStreamType)
            ) {
                val remainderStart = offset + TS_PACKET_BYTES
                val output = heldOutput + candidate.copyOfRange(remainderStart, candidate.size)
                active = false
                clearState()
                return output
            }

            if (heldOutput.size > MAX_HELD_BYTES) {
                clearState()
            }
            offset += TS_PACKET_BYTES
        }

        packetRemainder = candidate.copyOfRange(offset, candidate.size)
        return byteArrayOf()
    }

    private fun clearState() {
        packetRemainder = byteArrayOf()
        heldOutput = byteArrayOf()
        pmtPid = null
        videoPid = null
        videoStreamType = null
        sawPmt = false
    }

    private fun packetPid(packet: ByteArray): Int =
        ((packet[1].toInt() and 0x1f) shl 8) or (packet[2].toInt() and 0xff)

    private fun payloadOffset(packet: ByteArray): Int? {
        if (packet.size != TS_PACKET_BYTES || packet[0] != TS_SYNC_BYTE) return null
        return when ((packet[3].toInt() ushr 4) and 0x03) {
            1 -> TS_HEADER_BYTES
            3 -> {
                val adaptationLength = packet[4].toInt() and 0xff
                (TS_HEADER_BYTES + 1 + adaptationLength).takeIf { it < TS_PACKET_BYTES }
            }
            else -> null
        }
    }

    private fun psiSectionOffset(packet: ByteArray): Int? {
        if ((packet[1].toInt() and PAYLOAD_UNIT_START_FLAG) == 0) return null
        val payload = payloadOffset(packet) ?: return null
        val pointer = packet[payload].toInt() and 0xff
        return (payload + 1 + pointer).takeIf { it + 3 <= TS_PACKET_BYTES }
    }

    private fun parsePatPmtPid(packet: ByteArray): Int? {
        val section = psiSectionOffset(packet) ?: return null
        if ((packet[section].toInt() and 0xff) != PAT_TABLE_ID) return null
        val sectionEnd = sectionEnd(packet, section) ?: return null
        val programEnd = sectionEnd - PSI_CRC_BYTES
        var offset = section + PAT_PROGRAM_LOOP_OFFSET
        while (offset + PAT_PROGRAM_BYTES <= programEnd) {
            val programNumber = ((packet[offset].toInt() and 0xff) shl 8) or
                (packet[offset + 1].toInt() and 0xff)
            val pid = ((packet[offset + 2].toInt() and 0x1f) shl 8) or
                (packet[offset + 3].toInt() and 0xff)
            if (programNumber != 0) return pid
            offset += PAT_PROGRAM_BYTES
        }
        return null
    }

    private fun parsePmtVideoStream(packet: ByteArray): VideoStream? {
        val section = psiSectionOffset(packet) ?: return null
        if ((packet[section].toInt() and 0xff) != PMT_TABLE_ID) return null
        val sectionEnd = sectionEnd(packet, section) ?: return null
        if (section + PMT_STREAM_LOOP_OFFSET > sectionEnd) return null
        val programInfoLength = ((packet[section + 10].toInt() and 0x0f) shl 8) or
            (packet[section + 11].toInt() and 0xff)
        var offset = section + PMT_STREAM_LOOP_OFFSET + programInfoLength
        val streamEnd = sectionEnd - PSI_CRC_BYTES
        while (offset + PMT_STREAM_HEADER_BYTES <= streamEnd) {
            val streamType = packet[offset].toInt() and 0xff
            val elementaryPid = ((packet[offset + 1].toInt() and 0x1f) shl 8) or
                (packet[offset + 2].toInt() and 0xff)
            val esInfoLength = ((packet[offset + 3].toInt() and 0x0f) shl 8) or
                (packet[offset + 4].toInt() and 0xff)
            if (streamType in VIDEO_STREAM_TYPES) return VideoStream(elementaryPid, streamType)
            offset += PMT_STREAM_HEADER_BYTES + esInfoLength
        }
        return null
    }

    private fun sectionEnd(packet: ByteArray, section: Int): Int? {
        val sectionLength = ((packet[section + 1].toInt() and 0x0f) shl 8) or
            (packet[section + 2].toInt() and 0xff)
        return (section + 3 + sectionLength).takeIf { it <= TS_PACKET_BYTES }
    }

    private fun hasRandomAccessEvidence(packet: ByteArray, streamType: Int?): Boolean {
        if (hasRandomAccessIndicator(packet)) return true
        val payload = payloadOffset(packet) ?: return false
        return when (streamType) {
            H264_STREAM_TYPE -> annexBNalHeaders(packet, payload).any { (it and 0x1f) == H264_IDR_NAL }
            HEVC_STREAM_TYPE -> annexBNalHeaders(packet, payload).any {
                ((it ushr 1) and 0x3f) in HEVC_IRAP_NALS
            }
            else -> false
        }
    }

    private fun hasRandomAccessIndicator(packet: ByteArray): Boolean {
        val adaptationControl = (packet[3].toInt() ushr 4) and 0x03
        if (adaptationControl != 2 && adaptationControl != 3) return false
        val adaptationLength = packet[4].toInt() and 0xff
        return adaptationLength >= 1 &&
            5 + adaptationLength <= TS_PACKET_BYTES &&
            (packet[5].toInt() and RANDOM_ACCESS_INDICATOR_FLAG) != 0
    }

    private fun annexBNalHeaders(packet: ByteArray, payloadOffset: Int): Sequence<Int> = sequence {
        var index = payloadOffset
        while (index + 4 < packet.size) {
            when {
                packet[index] == 0.toByte() && packet[index + 1] == 0.toByte() &&
                    packet[index + 2] == 1.toByte() -> {
                    yield(packet[index + 3].toInt() and 0xff)
                    index += 4
                }
                packet[index] == 0.toByte() && packet[index + 1] == 0.toByte() &&
                    packet[index + 2] == 0.toByte() && packet[index + 3] == 1.toByte() -> {
                    yield(packet[index + 4].toInt() and 0xff)
                    index += 5
                }
                else -> index++
            }
        }
    }

    private data class VideoStream(val pid: Int, val streamType: Int)

    private companion object {
        const val TS_PACKET_BYTES = 188
        const val TS_HEADER_BYTES = 4
        const val MAX_HELD_BYTES = TS_PACKET_BYTES * 4096
        const val PAT_PID = 0
        const val PAT_TABLE_ID = 0x00
        const val PMT_TABLE_ID = 0x02
        const val PAYLOAD_UNIT_START_FLAG = 0x40
        const val RANDOM_ACCESS_INDICATOR_FLAG = 0x40
        const val PSI_CRC_BYTES = 4
        const val PAT_PROGRAM_LOOP_OFFSET = 8
        const val PAT_PROGRAM_BYTES = 4
        const val PMT_STREAM_LOOP_OFFSET = 12
        const val PMT_STREAM_HEADER_BYTES = 5
        const val MPEG2_VIDEO_STREAM_TYPE = 0x02
        const val H264_STREAM_TYPE = 0x1b
        const val HEVC_STREAM_TYPE = 0x24
        const val H264_IDR_NAL = 5
        val HEVC_IRAP_NALS = 16..21
        val VIDEO_STREAM_TYPES = setOf(MPEG2_VIDEO_STREAM_TYPE, H264_STREAM_TYPE, HEVC_STREAM_TYPE)
        val TS_SYNC_BYTE: Byte = 0x47
    }
}
