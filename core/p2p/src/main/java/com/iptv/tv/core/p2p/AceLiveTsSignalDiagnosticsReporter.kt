package com.iptv.tv.core.p2p

/**
 * Read-only MPEG-TS signal evidence for the V4d player/demux boundary.
 *
 * Input is the already resynchronized TS byte stream. The tracker never withholds, rewrites or
 * reorders media. It only observes packet/PSI/continuity/random-access evidence so a TV Box field
 * log can distinguish "P2P produced bytes" from "Media3 received a structurally useful TS stream".
 */
internal class AceLiveTsSignalDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit,
    private val context: AceLiveRuntimeDiagnosticsContext,
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS
) {
    private val tracker = AceLiveTsSignalTracker()
    private var lastSignature: MaterialSignature? = null
    private var lastReportedAtMillis: Long? = null

    init {
        require(periodicIntervalMillis > 0L) { "periodicIntervalMillis must be positive" }
    }

    @Synchronized
    fun onBytes(bytes: ByteArray, nowMillis: Long = System.currentTimeMillis()) {
        if (bytes.isEmpty()) return
        val now = nowMillis.coerceAtLeast(0L)
        val snapshot = tracker.consume(bytes)
        val signature = MaterialSignature.from(snapshot)
        val previousReportAt = lastReportedAtMillis
        val materialChange = lastSignature == null || lastSignature != signature
        val periodicRefresh = previousReportAt == null ||
            now - previousReportAt >= periodicIntervalMillis
        if (!materialChange && !periodicRefresh) return

        lastSignature = signature
        lastReportedAtMillis = now
        runCatching {
            observer(STATUS, formatMessage(snapshot))
        }
    }

    internal fun formatMessage(snapshot: AceLiveTsSignalSnapshot): String = buildString {
        append("packets=")
        append(snapshot.packetCount)
        append(" bytes=")
        append(snapshot.observedBytes)
        append(" sync_losses=")
        append(snapshot.syncLossCount)
        append(" transport_errors=")
        append(snapshot.transportErrorCount)
        append(" pat_sections=")
        append(snapshot.patSectionCount)
        append(" pmt_sections=")
        append(snapshot.pmtSectionCount)
        append(" pmt_pid=")
        append(snapshot.pmtPid?.toString() ?: "none")
        append(" pcr_pid=")
        append(snapshot.pcrPid?.toString() ?: "none")
        append(" video_pid=")
        append(snapshot.videoPid?.toString() ?: "none")
        append(" video_type=")
        append(snapshot.videoStreamType?.let(::streamTypeName) ?: "none")
        append(" audio_pids=")
        append(
            if (snapshot.audioPids.isEmpty()) "none"
            else snapshot.audioPids.sorted().joinToString("|")
        )
        append(" random_access=")
        append(snapshot.randomAccessCount)
        append(" continuity_gaps=")
        append(snapshot.continuityGapCount)
        append(" continuity_duplicates=")
        append(snapshot.continuityDuplicateCount)
        append(" startup_id=")
        append(context.startupId)
        append(" runtime_id=")
        append(context.runtimeId)
        append(" generation=")
        append(context.generation)
        append(" path=")
        append(context.path)
    }

    private fun streamTypeName(streamType: Int): String =
        "0x" + streamType.toString(16).padStart(2, '0')

    private data class MaterialSignature(
        val pmtPid: Int?,
        val pcrPid: Int?,
        val videoPid: Int?,
        val videoStreamType: Int?,
        val audioPids: Set<Int>,
        val patSeen: Boolean,
        val pmtSeen: Boolean,
        val randomAccessSeen: Boolean,
        val syncLossSeen: Boolean,
        val transportErrorSeen: Boolean,
        val continuityGapSeen: Boolean
    ) {
        companion object {
            fun from(snapshot: AceLiveTsSignalSnapshot) = MaterialSignature(
                pmtPid = snapshot.pmtPid,
                pcrPid = snapshot.pcrPid,
                videoPid = snapshot.videoPid,
                videoStreamType = snapshot.videoStreamType,
                audioPids = snapshot.audioPids,
                patSeen = snapshot.patSectionCount > 0L,
                pmtSeen = snapshot.pmtSectionCount > 0L,
                randomAccessSeen = snapshot.randomAccessCount > 0L,
                syncLossSeen = snapshot.syncLossCount > 0L,
                transportErrorSeen = snapshot.transportErrorCount > 0L,
                continuityGapSeen = snapshot.continuityGapCount > 0L
            )
        }
    }

    private companion object {
        const val STATUS = "embedded_ace_live_ts_signal"
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS = 5_000L
    }
}

internal data class AceLiveTsSignalSnapshot(
    val observedBytes: Long,
    val packetCount: Long,
    val syncLossCount: Long,
    val transportErrorCount: Long,
    val continuityGapCount: Long,
    val continuityDuplicateCount: Long,
    val patSectionCount: Long,
    val pmtSectionCount: Long,
    val pmtPid: Int?,
    val pcrPid: Int?,
    val videoPid: Int?,
    val videoStreamType: Int?,
    val audioPids: Set<Int>,
    val randomAccessCount: Long
)

/**
 * Bounded incremental MPEG-TS observer. It accepts arbitrary byte chunking and keeps at most one TS
 * packet of alignment remainder plus one bounded PSI section for PAT and PMT assembly.
 */
internal class AceLiveTsSignalTracker {
    private var remainder = byteArrayOf()
    private val lastPayloadContinuity = IntArray(MAX_PID + 1) { UNSEEN_CONTINUITY }
    private val patAssembler = PsiSectionAssembler(PAT_TABLE_ID)
    private var pmtAssembler = PsiSectionAssembler(PMT_TABLE_ID)

    private var observedBytes = 0L
    private var packetCount = 0L
    private var syncLossCount = 0L
    private var transportErrorCount = 0L
    private var continuityGapCount = 0L
    private var continuityDuplicateCount = 0L
    private var patSectionCount = 0L
    private var pmtSectionCount = 0L
    private var pmtPid: Int? = null
    private var pcrPid: Int? = null
    private var videoPid: Int? = null
    private var videoStreamType: Int? = null
    private var audioPids: Set<Int> = emptySet()
    private var randomAccessCount = 0L

    fun consume(bytes: ByteArray): AceLiveTsSignalSnapshot {
        if (bytes.isEmpty()) return snapshot()
        observedBytes = saturatingAdd(observedBytes, bytes.size.toLong())

        val candidate = remainder + bytes
        var offset = 0
        while (offset + TS_PACKET_BYTES <= candidate.size) {
            if (candidate[offset] != TS_SYNC_BYTE) {
                syncLossCount = saturatingIncrement(syncLossCount)
                val recovered = findNextSync(candidate, offset + 1)
                if (recovered < 0) break
                offset = recovered
                continue
            }

            val packet = candidate.copyOfRange(offset, offset + TS_PACKET_BYTES)
            observePacket(packet)
            offset += TS_PACKET_BYTES
        }

        remainder = candidate.copyOfRange(
            offset.coerceAtMost(candidate.size),
            candidate.size
        ).let { pending ->
            if (pending.size <= MAX_ALIGNMENT_REMAINDER_BYTES) pending
            else pending.copyOfRange(pending.size - MAX_ALIGNMENT_REMAINDER_BYTES, pending.size)
        }
        return snapshot()
    }

    fun snapshot(): AceLiveTsSignalSnapshot = AceLiveTsSignalSnapshot(
        observedBytes = observedBytes,
        packetCount = packetCount,
        syncLossCount = syncLossCount,
        transportErrorCount = transportErrorCount,
        continuityGapCount = continuityGapCount,
        continuityDuplicateCount = continuityDuplicateCount,
        patSectionCount = patSectionCount,
        pmtSectionCount = pmtSectionCount,
        pmtPid = pmtPid,
        pcrPid = pcrPid,
        videoPid = videoPid,
        videoStreamType = videoStreamType,
        audioPids = audioPids,
        randomAccessCount = randomAccessCount
    )

    private fun observePacket(packet: ByteArray) {
        packetCount = saturatingIncrement(packetCount)
        val pid = packetPid(packet)
        val transportError = (packet[1].toInt() and TRANSPORT_ERROR_FLAG) != 0
        if (transportError) {
            transportErrorCount = saturatingIncrement(transportErrorCount)
            lastPayloadContinuity[pid] = UNSEEN_CONTINUITY
            return
        }

        observeContinuity(packet, pid)
        val payloadOffset = payloadOffset(packet)
        val payloadUnitStart = (packet[1].toInt() and PAYLOAD_UNIT_START_FLAG) != 0

        if (pid == PAT_PID && payloadOffset != null) {
            patAssembler.consume(packet, payloadOffset, payloadUnitStart).forEach(::observePatSection)
        }
        val currentPmtPid = pmtPid
        if (currentPmtPid != null && pid == currentPmtPid && payloadOffset != null) {
            pmtAssembler.consume(packet, payloadOffset, payloadUnitStart).forEach(::observePmtSection)
        }

        if (pid == videoPid && hasRandomAccessEvidence(packet, payloadOffset, videoStreamType)) {
            randomAccessCount = saturatingIncrement(randomAccessCount)
        }
    }

    private fun observeContinuity(packet: ByteArray, pid: Int) {
        val adaptationControl = (packet[3].toInt() ushr 4) and 0x03
        val hasPayload = adaptationControl == 1 || adaptationControl == 3
        if (!hasPayload) return

        val continuity = packet[3].toInt() and CONTINUITY_COUNTER_MASK
        if (hasDiscontinuityIndicator(packet)) {
            lastPayloadContinuity[pid] = continuity
            return
        }

        val previous = lastPayloadContinuity[pid]
        if (previous != UNSEEN_CONTINUITY) {
            val expected = (previous + 1) and CONTINUITY_COUNTER_MASK
            when {
                continuity == previous ->
                    continuityDuplicateCount = saturatingIncrement(continuityDuplicateCount)
                continuity != expected ->
                    continuityGapCount = saturatingIncrement(continuityGapCount)
            }
        }
        lastPayloadContinuity[pid] = continuity
    }

    private fun observePatSection(section: ByteArray) {
        val parsed = parsePat(section) ?: return
        patSectionCount = saturatingIncrement(patSectionCount)
        if (pmtPid == parsed) return

        pmtPid = parsed
        pcrPid = null
        videoPid = null
        videoStreamType = null
        audioPids = emptySet()
        pmtAssembler = PsiSectionAssembler(PMT_TABLE_ID)
    }

    private fun observePmtSection(section: ByteArray) {
        val parsed = parsePmt(section) ?: return
        pmtSectionCount = saturatingIncrement(pmtSectionCount)
        pcrPid = parsed.pcrPid
        videoPid = parsed.videoPid
        videoStreamType = parsed.videoStreamType
        audioPids = parsed.audioPids
    }

    private fun parsePat(section: ByteArray): Int? {
        if (section.size < MIN_PAT_SECTION_BYTES || section[0].toInt() and 0xff != PAT_TABLE_ID) {
            return null
        }
        val programEnd = section.size - PSI_CRC_BYTES
        var offset = PAT_PROGRAM_LOOP_OFFSET
        while (offset + PAT_PROGRAM_BYTES <= programEnd) {
            val programNumber = ((section[offset].toInt() and 0xff) shl 8) or
                (section[offset + 1].toInt() and 0xff)
            val pid = ((section[offset + 2].toInt() and 0x1f) shl 8) or
                (section[offset + 3].toInt() and 0xff)
            if (programNumber != 0) return pid
            offset += PAT_PROGRAM_BYTES
        }
        return null
    }

    private fun parsePmt(section: ByteArray): ParsedPmt? {
        if (section.size < MIN_PMT_SECTION_BYTES || section[0].toInt() and 0xff != PMT_TABLE_ID) {
            return null
        }
        val pcrPid = ((section[8].toInt() and 0x1f) shl 8) or
            (section[9].toInt() and 0xff)
        val programInfoLength = ((section[10].toInt() and 0x0f) shl 8) or
            (section[11].toInt() and 0xff)
        var offset = PMT_STREAM_LOOP_OFFSET + programInfoLength
        val streamEnd = section.size - PSI_CRC_BYTES
        var videoPid: Int? = null
        var videoStreamType: Int? = null
        val audio = linkedSetOf<Int>()

        while (offset + PMT_STREAM_HEADER_BYTES <= streamEnd) {
            val streamType = section[offset].toInt() and 0xff
            val elementaryPid = ((section[offset + 1].toInt() and 0x1f) shl 8) or
                (section[offset + 2].toInt() and 0xff)
            val esInfoLength = ((section[offset + 3].toInt() and 0x0f) shl 8) or
                (section[offset + 4].toInt() and 0xff)
            if (videoPid == null && streamType in VIDEO_STREAM_TYPES) {
                videoPid = elementaryPid
                videoStreamType = streamType
            }
            if (streamType in AUDIO_STREAM_TYPES) audio += elementaryPid
            offset += PMT_STREAM_HEADER_BYTES + esInfoLength
        }
        return ParsedPmt(
            pcrPid = pcrPid,
            videoPid = videoPid,
            videoStreamType = videoStreamType,
            audioPids = audio
        )
    }

    private fun payloadOffset(packet: ByteArray): Int? {
        val adaptationControl = (packet[3].toInt() ushr 4) and 0x03
        return when (adaptationControl) {
            1 -> TS_HEADER_BYTES
            3 -> {
                val adaptationLength = packet[4].toInt() and 0xff
                (TS_HEADER_BYTES + 1 + adaptationLength).takeIf { it < TS_PACKET_BYTES }
            }
            else -> null
        }
    }

    private fun hasDiscontinuityIndicator(packet: ByteArray): Boolean {
        val adaptationControl = (packet[3].toInt() ushr 4) and 0x03
        if (adaptationControl != 2 && adaptationControl != 3) return false
        val adaptationLength = packet[4].toInt() and 0xff
        return adaptationLength >= 1 &&
            5 + adaptationLength <= TS_PACKET_BYTES &&
            (packet[5].toInt() and DISCONTINUITY_INDICATOR_FLAG) != 0
    }

    private fun hasRandomAccessEvidence(
        packet: ByteArray,
        payloadOffset: Int?,
        streamType: Int?
    ): Boolean {
        if (hasRandomAccessIndicator(packet)) return true
        val payload = payloadOffset ?: return false
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

    private fun packetPid(packet: ByteArray): Int =
        ((packet[1].toInt() and 0x1f) shl 8) or (packet[2].toInt() and 0xff)

    private fun findNextSync(bytes: ByteArray, start: Int): Int {
        var index = start
        while (index < bytes.size) {
            if (bytes[index] == TS_SYNC_BYTE) {
                val nextPacket = index + TS_PACKET_BYTES
                if (nextPacket >= bytes.size || bytes[nextPacket] == TS_SYNC_BYTE) return index
            }
            index++
        }
        return -1
    }

    private fun saturatingIncrement(value: Long): Long =
        if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1L

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private data class ParsedPmt(
        val pcrPid: Int,
        val videoPid: Int?,
        val videoStreamType: Int?,
        val audioPids: Set<Int>
    )

    private class PsiSectionAssembler(private val tableId: Int) {
        private var pending = byteArrayOf()

        fun consume(
            packet: ByteArray,
            payloadOffset: Int,
            payloadUnitStart: Boolean
        ): List<ByteArray> {
            if (payloadOffset !in 0 until packet.size) return emptyList()
            val sections = mutableListOf<ByteArray>()
            var cursor = payloadOffset

            if (!payloadUnitStart) {
                if (pending.isEmpty()) return emptyList()
                pending += packet.copyOfRange(cursor, packet.size)
                drainPending()?.let(sections::add)
                return sections
            }

            val pointer = packet[cursor].toInt() and 0xff
            cursor += 1
            val sectionStart = (cursor + pointer).coerceAtMost(packet.size)
            if (pending.isNotEmpty()) {
                if (sectionStart > cursor) {
                    pending += packet.copyOfRange(cursor, sectionStart)
                    drainPending()?.let(sections::add)
                }
                // A new payload-unit start is authoritative. Do not carry a malformed old section
                // across the new section boundary.
                pending = byteArrayOf()
            }
            cursor = sectionStart

            while (cursor < packet.size) {
                val table = packet[cursor].toInt() and 0xff
                if (table == STUFFING_BYTE) break
                if (cursor + SECTION_HEADER_BYTES > packet.size) {
                    pending = packet.copyOfRange(cursor, packet.size)
                    break
                }
                val totalLength = sectionTotalLength(packet, cursor)
                if (totalLength !in MIN_SECTION_BYTES..MAX_PSI_SECTION_BYTES) {
                    break
                }
                val end = cursor + totalLength
                if (end <= packet.size) {
                    val section = packet.copyOfRange(cursor, end)
                    if (section[0].toInt() and 0xff == tableId) sections += section
                    cursor = end
                } else {
                    pending = packet.copyOfRange(cursor, packet.size)
                    break
                }
            }
            return sections
        }

        private fun drainPending(): ByteArray? {
            if (pending.size < SECTION_HEADER_BYTES) return null
            val totalLength = sectionTotalLength(pending, 0)
            if (totalLength !in MIN_SECTION_BYTES..MAX_PSI_SECTION_BYTES) {
                pending = byteArrayOf()
                return null
            }
            if (pending.size < totalLength) return null
            val section = pending.copyOfRange(0, totalLength)
            pending = byteArrayOf()
            return section.takeIf { it[0].toInt() and 0xff == tableId }
        }

        private fun sectionTotalLength(bytes: ByteArray, offset: Int): Int {
            val sectionLength = ((bytes[offset + 1].toInt() and 0x0f) shl 8) or
                (bytes[offset + 2].toInt() and 0xff)
            return SECTION_HEADER_BYTES + sectionLength
        }
    }

    private companion object {
        const val TS_PACKET_BYTES = 188
        const val TS_HEADER_BYTES = 4
        const val MAX_ALIGNMENT_REMAINDER_BYTES = TS_PACKET_BYTES - 1
        const val MAX_PID = 0x1fff
        const val UNSEEN_CONTINUITY = -1
        const val CONTINUITY_COUNTER_MASK = 0x0f
        const val TRANSPORT_ERROR_FLAG = 0x80
        const val PAYLOAD_UNIT_START_FLAG = 0x40
        const val DISCONTINUITY_INDICATOR_FLAG = 0x80
        const val RANDOM_ACCESS_INDICATOR_FLAG = 0x40
        const val PAT_PID = 0
        const val PAT_TABLE_ID = 0x00
        const val PMT_TABLE_ID = 0x02
        const val PSI_CRC_BYTES = 4
        const val PAT_PROGRAM_LOOP_OFFSET = 8
        const val PAT_PROGRAM_BYTES = 4
        const val PMT_STREAM_LOOP_OFFSET = 12
        const val PMT_STREAM_HEADER_BYTES = 5
        const val SECTION_HEADER_BYTES = 3
        const val MIN_SECTION_BYTES = 3
        const val MAX_PSI_SECTION_BYTES = 4096
        const val MIN_PAT_SECTION_BYTES = 12
        const val MIN_PMT_SECTION_BYTES = 16
        const val STUFFING_BYTE = 0xff
        const val MPEG1_VIDEO_STREAM_TYPE = 0x01
        const val MPEG2_VIDEO_STREAM_TYPE = 0x02
        const val H264_STREAM_TYPE = 0x1b
        const val HEVC_STREAM_TYPE = 0x24
        const val MPEG1_AUDIO_STREAM_TYPE = 0x03
        const val MPEG2_AUDIO_STREAM_TYPE = 0x04
        const val AAC_ADTS_STREAM_TYPE = 0x0f
        const val AAC_LATM_STREAM_TYPE = 0x11
        const val AC3_ATSC_STREAM_TYPE = 0x81
        const val H264_IDR_NAL = 5
        val HEVC_IRAP_NALS = 16..21
        val VIDEO_STREAM_TYPES = setOf(
            MPEG1_VIDEO_STREAM_TYPE,
            MPEG2_VIDEO_STREAM_TYPE,
            H264_STREAM_TYPE,
            HEVC_STREAM_TYPE
        )
        val AUDIO_STREAM_TYPES = setOf(
            MPEG1_AUDIO_STREAM_TYPE,
            MPEG2_AUDIO_STREAM_TYPE,
            AAC_ADTS_STREAM_TYPE,
            AAC_LATM_STREAM_TYPE,
            AC3_ATSC_STREAM_TYPE
        )
        val TS_SYNC_BYTE: Byte = 0x47
    }
}
