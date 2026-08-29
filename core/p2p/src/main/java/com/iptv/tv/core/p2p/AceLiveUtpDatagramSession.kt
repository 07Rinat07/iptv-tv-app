package com.iptv.tv.core.p2p

import kotlin.math.abs
import kotlin.math.max

internal data class AceLiveUtpSessionPolicy(
    val maxPayloadBytes: Int = 1_200,
    val maxInFlightPackets: Int = 32,
    val maxInFlightBytes: Int = 64 * 1024,
    val maxOutOfOrderPackets: Int = 32,
    val localReceiveWindowBytes: Int = 64 * 1024,
    val maxRetransmissionsPerPacket: Int = 4,
    val initialRetransmissionTimeoutMillis: Long = 1_000L,
    val minRetransmissionTimeoutMillis: Long = 500L,
    val maxRetransmissionTimeoutMillis: Long = 60_000L
) {
    init {
        require(maxPayloadBytes in 1..AceLiveUtpCodec.MAX_DATAGRAM_BYTES - AceLiveUtpCodec.HEADER_BYTES)
        require(maxInFlightPackets in 1..MAX_SAFE_IN_FLIGHT_PACKETS)
        require(maxInFlightBytes in maxPayloadBytes..MAX_SAFE_WINDOW_BYTES)
        require(maxOutOfOrderPackets in 1..MAX_SAFE_OUT_OF_ORDER_PACKETS)
        require(localReceiveWindowBytes in maxPayloadBytes..MAX_SAFE_WINDOW_BYTES)
        require(maxRetransmissionsPerPacket in 0..MAX_SAFE_RETRANSMISSIONS)
        require(initialRetransmissionTimeoutMillis in minRetransmissionTimeoutMillis..maxRetransmissionTimeoutMillis)
        require(minRetransmissionTimeoutMillis in 100L..10_000L)
        require(maxRetransmissionTimeoutMillis in minRetransmissionTimeoutMillis..MAX_SAFE_TIMEOUT_MILLIS)
    }

    private companion object {
        const val MAX_SAFE_IN_FLIGHT_PACKETS = 256
        const val MAX_SAFE_OUT_OF_ORDER_PACKETS = 256
        const val MAX_SAFE_WINDOW_BYTES = 4 * 1024 * 1024
        const val MAX_SAFE_RETRANSMISSIONS = 12
        const val MAX_SAFE_TIMEOUT_MILLIS = 5L * 60_000L
    }
}

internal class AceLiveUtpTransmission(
    val packet: AceLiveUtpPacket,
    val attempt: Int,
    val retransmission: Boolean
) {
    private val encodedDatagram: ByteArray = AceLiveUtpCodec.encode(packet)
    val datagram: ByteArray
        get() = encodedDatagram.copyOf()

    init {
        require(attempt > 0)
        require(retransmission == (attempt > 1))
    }
}

internal data class AceLiveUtpSendResult(
    val acceptedBytes: Int,
    val transmissions: List<AceLiveUtpTransmission>
)

internal class AceLiveUtpReceiveResult(
    deliveredBytes: ByteArray,
    val acknowledgement: AceLiveUtpTransmission?,
    acknowledgedSequenceNumbers: Set<Int>,
    val ignored: Boolean,
    val remoteClosed: Boolean,
    fastRetransmissions: List<AceLiveUtpTransmission> = emptyList()
) {
    val deliveredBytes: ByteArray = deliveredBytes.copyOf()
    val acknowledgedSequenceNumbers: Set<Int> = acknowledgedSequenceNumbers.toSet()
    val fastRetransmissions: List<AceLiveUtpTransmission> = fastRetransmissions.toList()
}

internal sealed interface AceLiveUtpTimeoutResult {
    object None : AceLiveUtpTimeoutResult

    data class Retransmit(
        val transmission: AceLiveUtpTransmission
    ) : AceLiveUtpTimeoutResult

    data class Exhausted(
        val sequenceNumber: Int
    ) : AceLiveUtpTimeoutResult
}

/**
 * RFC6298-shaped estimator using the BEP-29 coefficients and timeout floor.
 *
 * Only never-retransmitted packets may provide RTT samples. A valid ACK still clears timeout
 * backoff because forward progress has resumed.
 */
internal class AceLiveUtpRttEstimator(
    private val policy: AceLiveUtpSessionPolicy
) {
    private var smoothedRttMillis: Long? = null
    private var rttVariationMillis: Long = 0L
    private var baseTimeoutMillis: Long = policy.initialRetransmissionTimeoutMillis

    var timeoutMillis: Long = policy.initialRetransmissionTimeoutMillis
        private set

    fun onAcknowledgement(sampleMillis: Long?) {
        if (sampleMillis != null) {
            require(sampleMillis >= 0L)
            updateRtt(sampleMillis.coerceAtLeast(1L))
        }
        timeoutMillis = baseTimeoutMillis
    }

    fun onTimeout() {
        timeoutMillis = (timeoutMillis * 2L)
            .coerceAtMost(policy.maxRetransmissionTimeoutMillis)
    }

    fun smoothedRttMillis(): Long? = smoothedRttMillis

    fun rttVariationMillis(): Long = rttVariationMillis

    private fun updateRtt(sampleMillis: Long) {
        val currentRtt = smoothedRttMillis
        if (currentRtt == null) {
            // BEP-29 leaves initial estimator values unspecified. Seeding from the first sample
            // avoids an artificial sub-sample RTO while keeping the mandated 500 ms floor.
            smoothedRttMillis = sampleMillis
            rttVariationMillis = sampleMillis / 2L
        } else {
            val delta = currentRtt - sampleMillis
            rttVariationMillis += (abs(delta) - rttVariationMillis) / 4L
            smoothedRttMillis = currentRtt + (sampleMillis - currentRtt) / 8L
        }

        val rtt = checkNotNull(smoothedRttMillis)
        baseTimeoutMillis = max(
            rtt + rttVariationMillis * 4L,
            policy.minRetransmissionTimeoutMillis
        ).coerceAtMost(policy.maxRetransmissionTimeoutMillis)
    }
}

/**
 * Deterministic connected uTP session core.
 *
 * Owns packet sequence numbers, cumulative/selective ACK processing, bounded out-of-order receive
 * buffering, peer receive-window flow control, bounded fast loss recovery and timeout retransmission.
 * It deliberately does not own a DatagramSocket or LEDBAT congestion control; those are socket
 * adapter responsibilities.
 */
internal class AceLiveUtpDatagramSession(
    private val connectionIds: AceLiveUtpClientConnectionIds,
    initialLocalSequenceNumber: Int,
    initialRemoteSequenceNumber: Int,
    initialRemoteReceiveWindowBytes: Long,
    private val policy: AceLiveUtpSessionPolicy = AceLiveUtpSessionPolicy()
) {
    private data class SentPacket(
        var packet: AceLiveUtpPacket,
        var lastSentAtMillis: Long,
        var attempt: Int,
        var lossEvidenceCount: Int = 0,
        var fastRetransmitted: Boolean = false
    )

    private data class AckProcessingResult(
        val acknowledgedSequenceNumbers: Set<Int>,
        val fastRetransmissions: List<AceLiveUtpTransmission>
    )

    private val unacknowledged = LinkedHashMap<Int, SentPacket>()
    private val outOfOrder = linkedMapOf<Int, ByteArray>()
    private val rttEstimator = AceLiveUtpRttEstimator(policy)

    private var nextLocalSequenceNumber = requireUint16(initialLocalSequenceNumber)
    private var highestCumulativeAcknowledgement = previousUint16(nextLocalSequenceNumber)
    private var lastContiguousRemoteSequenceNumber = requireUint16(initialRemoteSequenceNumber)
    private var remoteReceiveWindowBytes = requireUint32(initialRemoteReceiveWindowBytes)
    private var bufferedOutOfOrderBytes = 0
    private var reset = false
    private var remoteFinSequenceNumber: Int? = null

    fun send(
        bytes: ByteArray,
        nowMillis: Long,
        timestampMicros: Long,
        timestampDifferenceMicros: Long = 0L
    ): AceLiveUtpSendResult {
        checkActive()
        require(nowMillis >= 0L)
        if (bytes.isEmpty()) return AceLiveUtpSendResult(0, emptyList())

        val transmissions = ArrayList<AceLiveUtpTransmission>()
        var offset = 0
        while (offset < bytes.size) {
            if (unacknowledged.size >= policy.maxInFlightPackets) break

            val availableWindow = availableSendWindowBytes()
            if (availableWindow <= 0) break

            val payloadBytes = minOf(
                policy.maxPayloadBytes,
                bytes.size - offset,
                availableWindow
            )
            if (payloadBytes <= 0) break

            val payload = bytes.copyOfRange(offset, offset + payloadBytes)
            val packet = dataPacket(
                sequenceNumber = nextLocalSequenceNumber,
                payload = payload,
                timestampMicros = timestampMicros,
                timestampDifferenceMicros = timestampDifferenceMicros
            )
            val sent = SentPacket(
                packet = packet,
                lastSentAtMillis = nowMillis,
                attempt = 1
            )
            unacknowledged[packet.header.sequenceNumber] = sent
            transmissions += AceLiveUtpTransmission(packet, attempt = 1, retransmission = false)

            nextLocalSequenceNumber = nextUint16(nextLocalSequenceNumber)
            offset += payloadBytes
        }

        return AceLiveUtpSendResult(
            acceptedBytes = offset,
            transmissions = transmissions
        )
    }

    fun receiveDatagram(
        datagram: ByteArray,
        nowMillis: Long,
        nowMicros: Long,
        timestampDifferenceMicros: Long = 0L
    ): AceLiveUtpReceiveResult {
        require(nowMillis >= 0L)
        val packet = AceLiveUtpCodec.decode(datagram)
            ?: return ignoredReceiveResult()
        return receivePacket(packet, nowMillis, nowMicros, timestampDifferenceMicros)
    }

    fun receivePacket(
        packet: AceLiveUtpPacket,
        nowMillis: Long,
        nowMicros: Long,
        timestampDifferenceMicros: Long = 0L
    ): AceLiveUtpReceiveResult {
        require(nowMillis >= 0L)
        if (
            reset ||
            packet.header.connectionId != connectionIds.receiveConnectionId ||
            packet.header.type == AceLiveUtpPacketType.SYN
        ) {
            return ignoredReceiveResult()
        }

        remoteReceiveWindowBytes = packet.header.receiveWindowBytes
        val ackProgress = acknowledgeOutbound(
            packet = packet,
            nowMillis = nowMillis,
            timestampMicros = nowMicros,
            timestampDifferenceMicros = timestampDifferenceMicros,
            allowFastRetransmit = packet.header.type != AceLiveUtpPacketType.RESET
        )

        if (packet.header.type == AceLiveUtpPacketType.RESET) {
            reset = true
            return AceLiveUtpReceiveResult(
                deliveredBytes = ByteArray(0),
                acknowledgement = null,
                acknowledgedSequenceNumbers = ackProgress.acknowledgedSequenceNumbers,
                ignored = false,
                remoteClosed = true,
                fastRetransmissions = emptyList()
            )
        }

        if (packet.header.type != AceLiveUtpPacketType.DATA &&
            packet.header.type != AceLiveUtpPacketType.FIN
        ) {
            return AceLiveUtpReceiveResult(
                deliveredBytes = ByteArray(0),
                acknowledgement = null,
                acknowledgedSequenceNumbers = ackProgress.acknowledgedSequenceNumbers,
                ignored = false,
                remoteClosed = isRemoteClosed(),
                fastRetransmissions = ackProgress.fastRetransmissions
            )
        }

        val delivered = if (packet.header.type == AceLiveUtpPacketType.DATA) {
            acceptInboundData(packet)
        } else {
            acceptInboundFin(packet)
            ByteArray(0)
        }

        val acknowledgement = buildStateAcknowledgement(
            nowMicros = nowMicros,
            receivedTimestampMicros = packet.header.timestampMicros
        )

        return AceLiveUtpReceiveResult(
            deliveredBytes = delivered,
            acknowledgement = AceLiveUtpTransmission(
                packet = acknowledgement,
                attempt = 1,
                retransmission = false
            ),
            acknowledgedSequenceNumbers = ackProgress.acknowledgedSequenceNumbers,
            ignored = false,
            remoteClosed = isRemoteClosed(),
            fastRetransmissions = ackProgress.fastRetransmissions
        )
    }

    fun pollTimeout(
        nowMillis: Long,
        timestampMicros: Long,
        timestampDifferenceMicros: Long = 0L
    ): AceLiveUtpTimeoutResult {
        checkActive()
        require(nowMillis >= 0L)
        val oldest = unacknowledged.entries.firstOrNull() ?: return AceLiveUtpTimeoutResult.None
        val sent = oldest.value
        if (nowMillis - sent.lastSentAtMillis < rttEstimator.timeoutMillis) {
            return AceLiveUtpTimeoutResult.None
        }

        val retransmissionsSoFar = sent.attempt - 1
        if (retransmissionsSoFar >= policy.maxRetransmissionsPerPacket) {
            reset = true
            return AceLiveUtpTimeoutResult.Exhausted(oldest.key)
        }

        rttEstimator.onTimeout()
        val replacement = copyForRetransmission(
            packet = sent.packet,
            timestampMicros = timestampMicros,
            timestampDifferenceMicros = timestampDifferenceMicros
        )
        sent.packet = replacement
        sent.lastSentAtMillis = nowMillis
        sent.attempt += 1

        return AceLiveUtpTimeoutResult.Retransmit(
            AceLiveUtpTransmission(
                packet = replacement,
                attempt = sent.attempt,
                retransmission = true
            )
        )
    }

    fun inFlightPacketCount(): Int = unacknowledged.size

    fun inFlightPayloadBytes(): Int =
        unacknowledged.values.sumOf { sent -> sent.packet.payload.size }

    fun retransmissionTimeoutMillis(): Long = rttEstimator.timeoutMillis

    fun smoothedRttMillis(): Long? = rttEstimator.smoothedRttMillis()

    fun isReset(): Boolean = reset

    fun isRemoteClosed(): Boolean {
        val fin = remoteFinSequenceNumber ?: return false
        return fin == lastContiguousRemoteSequenceNumber
    }

    private fun acknowledgeOutbound(
        packet: AceLiveUtpPacket,
        nowMillis: Long,
        timestampMicros: Long,
        timestampDifferenceMicros: Long,
        allowFastRetransmit: Boolean
    ): AckProcessingResult {
        if (unacknowledged.isEmpty()) {
            return AckProcessingResult(emptySet(), emptyList())
        }

        val acknowledged = linkedSetOf<Int>()
        val cumulativeAck = packet.header.acknowledgementNumber
        val cumulativeDistance = forwardDistance(highestCumulativeAcknowledgement, cumulativeAck)
        val highestSentSequence = previousUint16(nextLocalSequenceNumber)
        val sentDistance = forwardDistance(highestCumulativeAcknowledgement, highestSentSequence)
        val acknowledgementBaseIsValid = when {
            cumulativeDistance == 0 -> true
            cumulativeDistance in 1..MAX_FORWARD_SEQUENCE_DISTANCE &&
                cumulativeDistance <= sentDistance -> {
                val toAcknowledge = unacknowledged.keys.filter { sequence ->
                    val distance = forwardDistance(highestCumulativeAcknowledgement, sequence)
                    distance in 1..cumulativeDistance
                }
                acknowledged += toAcknowledge
                highestCumulativeAcknowledgement = cumulativeAck
                true
            }
            else -> false
        }
        if (!acknowledgementBaseIsValid) {
            return AckProcessingResult(emptySet(), emptyList())
        }

        packet.extensions
            .asSequence()
            .filter { extension -> extension.type == AceLiveUtpExtension.SELECTIVE_ACK_TYPE }
            .forEach { extension ->
                extension.payload.forEachIndexed { byteIndex, byte ->
                    val bits = byte.toInt() and 0xff
                    for (bitIndex in 0 until 8) {
                        if ((bits and (1 shl bitIndex)) == 0) continue
                        val offset = 2 + byteIndex * 8 + bitIndex
                        val sequence = addUint16(cumulativeAck, offset)
                        if (sequence in unacknowledged) {
                            acknowledged += sequence
                        }
                    }
                }
            }

        recordLossEvidence(
            cumulativeAck = cumulativeAck,
            cumulativeDistance = cumulativeDistance,
            newlyAcknowledged = acknowledged
        )

        var sampledRtt = false
        acknowledged.forEach { sequence ->
            val sent = unacknowledged.remove(sequence) ?: return@forEach
            if (sent.attempt == 1) {
                val sample = (nowMillis - sent.lastSentAtMillis).coerceAtLeast(0L)
                rttEstimator.onAcknowledgement(sample)
                sampledRtt = true
            }
        }
        if (acknowledged.isNotEmpty() && !sampledRtt) {
            rttEstimator.onAcknowledgement(sampleMillis = null)
        }

        val fastRetransmissions = if (allowFastRetransmit) {
            buildFastRetransmissions(
                nowMillis = nowMillis,
                timestampMicros = timestampMicros,
                timestampDifferenceMicros = timestampDifferenceMicros
            )
        } else {
            emptyList()
        }

        return AckProcessingResult(
            acknowledgedSequenceNumbers = acknowledged,
            fastRetransmissions = fastRetransmissions
        )
    }

    private fun recordLossEvidence(
        cumulativeAck: Int,
        cumulativeDistance: Int,
        newlyAcknowledged: Set<Int>
    ) {
        if (unacknowledged.isEmpty()) return

        unacknowledged.forEach { (sequence, sent) ->
            if (sequence in newlyAcknowledged || sent.fastRetransmitted) return@forEach
            val acknowledgedPast = newlyAcknowledged.count { acknowledgedSequence ->
                val distance = forwardDistance(sequence, acknowledgedSequence)
                distance in 1..MAX_FORWARD_SEQUENCE_DISTANCE
            }
            if (acknowledgedPast > 0) {
                sent.lossEvidenceCount = (sent.lossEvidenceCount + acknowledgedPast)
                    .coerceAtMost(FAST_LOSS_EVIDENCE_THRESHOLD)
            }
        }

        if (cumulativeDistance != 0) return
        val missingSequence = nextUint16(cumulativeAck)
        val missing = unacknowledged[missingSequence] ?: return
        if (missing.fastRetransmitted) return
        val newlyAcknowledgedPastMissing = newlyAcknowledged.any { acknowledgedSequence ->
            val distance = forwardDistance(missingSequence, acknowledgedSequence)
            distance in 1..MAX_FORWARD_SEQUENCE_DISTANCE
        }
        if (!newlyAcknowledgedPastMissing) {
            missing.lossEvidenceCount = (missing.lossEvidenceCount + 1)
                .coerceAtMost(FAST_LOSS_EVIDENCE_THRESHOLD)
        }
    }

    private fun buildFastRetransmissions(
        nowMillis: Long,
        timestampMicros: Long,
        timestampDifferenceMicros: Long
    ): List<AceLiveUtpTransmission> {
        val retransmissions = ArrayList<AceLiveUtpTransmission>()
        unacknowledged.values.forEach { sent ->
            if (retransmissions.size >= MAX_FAST_RETRANSMISSIONS_PER_ACK) return@forEach
            if (
                sent.lossEvidenceCount < FAST_LOSS_EVIDENCE_THRESHOLD ||
                sent.fastRetransmitted
            ) {
                return@forEach
            }

            sent.fastRetransmitted = true
            val retransmissionsSoFar = sent.attempt - 1
            if (retransmissionsSoFar >= policy.maxRetransmissionsPerPacket) {
                return@forEach
            }

            val replacement = copyForRetransmission(
                packet = sent.packet,
                timestampMicros = timestampMicros,
                timestampDifferenceMicros = timestampDifferenceMicros
            )
            sent.packet = replacement
            sent.lastSentAtMillis = nowMillis
            sent.attempt += 1
            retransmissions += AceLiveUtpTransmission(
                packet = replacement,
                attempt = sent.attempt,
                retransmission = true
            )
        }
        return retransmissions
    }

    private fun acceptInboundData(packet: AceLiveUtpPacket): ByteArray {
        val sequence = packet.header.sequenceNumber
        val expected = nextUint16(lastContiguousRemoteSequenceNumber)
        if (sequence == expected) {
            val chunks = ArrayList<ByteArray>()
            chunks += packet.payload
            lastContiguousRemoteSequenceNumber = sequence
            while (true) {
                val next = nextUint16(lastContiguousRemoteSequenceNumber)
                val buffered = outOfOrder.remove(next) ?: break
                bufferedOutOfOrderBytes -= buffered.size
                chunks += buffered
                lastContiguousRemoteSequenceNumber = next
            }
            remoteFinSequenceNumber
                ?.takeIf { fin -> fin == nextUint16(lastContiguousRemoteSequenceNumber) }
                ?.let { fin -> lastContiguousRemoteSequenceNumber = fin }
            return concatenate(chunks)
        }

        val distance = forwardDistance(expected, sequence)
        if (
            distance in 1..MAX_FORWARD_SEQUENCE_DISTANCE &&
            sequence !in outOfOrder &&
            outOfOrder.size < policy.maxOutOfOrderPackets &&
            bufferedOutOfOrderBytes + packet.payload.size <= policy.localReceiveWindowBytes
        ) {
            outOfOrder[sequence] = packet.payload.copyOf()
            bufferedOutOfOrderBytes += packet.payload.size
        }
        return ByteArray(0)
    }

    private fun acceptInboundFin(packet: AceLiveUtpPacket) {
        val sequence = packet.header.sequenceNumber
        val expected = nextUint16(lastContiguousRemoteSequenceNumber)
        if (sequence == expected) {
            lastContiguousRemoteSequenceNumber = sequence
            remoteFinSequenceNumber = sequence
        } else {
            val distance = forwardDistance(expected, sequence)
            if (distance in 1..MAX_FORWARD_SEQUENCE_DISTANCE) {
                remoteFinSequenceNumber = sequence
            }
        }
    }

    private fun buildStateAcknowledgement(
        nowMicros: Long,
        receivedTimestampMicros: Long
    ): AceLiveUtpPacket {
        val sack = buildSelectiveAckExtension()
        return AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.STATE,
                connectionId = connectionIds.sendConnectionId,
                timestampMicros = nowMicros and UINT32_MAX,
                timestampDifferenceMicros = unsignedTimestampDifference(nowMicros, receivedTimestampMicros),
                receiveWindowBytes = advertisedReceiveWindowBytes().toLong(),
                sequenceNumber = nextLocalSequenceNumber,
                acknowledgementNumber = lastContiguousRemoteSequenceNumber
            ),
            extensions = listOfNotNull(sack)
        )
    }

    private fun buildSelectiveAckExtension(): AceLiveUtpExtension? {
        if (outOfOrder.isEmpty()) return null

        var highestOffset = 0
        outOfOrder.keys.forEach { sequence ->
            val offset = forwardDistance(lastContiguousRemoteSequenceNumber, sequence)
            if (offset >= 2 && offset <= MAX_SELECTIVE_ACK_OFFSET) {
                highestOffset = max(highestOffset, offset)
            }
        }
        if (highestOffset < 2) return null

        val representedBits = highestOffset - 1
        val bytesNeeded = (representedBits + 7) / 8
        val paddedBytes = ((bytesNeeded + 3) / 4) * 4
        val mask = ByteArray(paddedBytes)
        outOfOrder.keys.forEach { sequence ->
            val offset = forwardDistance(lastContiguousRemoteSequenceNumber, sequence)
            if (offset !in 2..(paddedBytes * 8 + 1)) return@forEach
            val bitOffset = offset - 2
            val byteIndex = bitOffset / 8
            val bitIndex = bitOffset % 8
            mask[byteIndex] = (mask[byteIndex].toInt() or (1 shl bitIndex)).toByte()
        }
        return AceLiveUtpExtension(AceLiveUtpExtension.SELECTIVE_ACK_TYPE, mask)
    }

    private fun dataPacket(
        sequenceNumber: Int,
        payload: ByteArray,
        timestampMicros: Long,
        timestampDifferenceMicros: Long
    ): AceLiveUtpPacket =
        AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.DATA,
                connectionId = connectionIds.sendConnectionId,
                timestampMicros = timestampMicros and UINT32_MAX,
                timestampDifferenceMicros = timestampDifferenceMicros and UINT32_MAX,
                receiveWindowBytes = advertisedReceiveWindowBytes().toLong(),
                sequenceNumber = sequenceNumber,
                acknowledgementNumber = lastContiguousRemoteSequenceNumber
            ),
            payload = payload
        )

    private fun copyForRetransmission(
        packet: AceLiveUtpPacket,
        timestampMicros: Long,
        timestampDifferenceMicros: Long
    ): AceLiveUtpPacket =
        AceLiveUtpPacket(
            header = packet.header.copy(
                timestampMicros = timestampMicros and UINT32_MAX,
                timestampDifferenceMicros = timestampDifferenceMicros and UINT32_MAX,
                receiveWindowBytes = advertisedReceiveWindowBytes().toLong(),
                acknowledgementNumber = lastContiguousRemoteSequenceNumber
            ),
            extensions = packet.extensions,
            payload = packet.payload
        )

    private fun availableSendWindowBytes(): Int {
        val remoteWindow = remoteReceiveWindowBytes
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val permitted = minOf(policy.maxInFlightBytes, remoteWindow)
        return (permitted - inFlightPayloadBytes()).coerceAtLeast(0)
    }

    private fun advertisedReceiveWindowBytes(): Int =
        (policy.localReceiveWindowBytes - bufferedOutOfOrderBytes).coerceAtLeast(0)

    private fun ignoredReceiveResult(): AceLiveUtpReceiveResult =
        AceLiveUtpReceiveResult(
            deliveredBytes = ByteArray(0),
            acknowledgement = null,
            acknowledgedSequenceNumbers = emptySet(),
            ignored = true,
            remoteClosed = isRemoteClosed()
        )

    private fun checkActive() {
        check(!reset) { "uTP session is reset or retransmission budget is exhausted" }
    }

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
        const val MAX_FORWARD_SEQUENCE_DISTANCE = 0x7fff
        const val MAX_SELECTIVE_ACK_OFFSET = 255
        const val FAST_LOSS_EVIDENCE_THRESHOLD = 3
        const val MAX_FAST_RETRANSMISSIONS_PER_ACK = 4
    }
}

private fun requireUint16(value: Int): Int {
    require(value in 0..0xffff) { "sequence number must be uint16" }
    return value
}

private fun requireUint32(value: Long): Long {
    require(value in 0L..0xffff_ffffL) { "window must be uint32" }
    return value
}

private fun nextUint16(value: Int): Int = (value + 1) and 0xffff

private fun previousUint16(value: Int): Int = (value - 1) and 0xffff

private fun addUint16(value: Int, increment: Int): Int = (value + increment) and 0xffff

private fun forwardDistance(from: Int, to: Int): Int = (to - from) and 0xffff

private fun unsignedTimestampDifference(nowMicros: Long, previousMicros: Long): Long =
    (nowMicros - previousMicros) and 0xffff_ffffL

private fun concatenate(chunks: List<ByteArray>): ByteArray {
    if (chunks.isEmpty()) return ByteArray(0)
    if (chunks.size == 1) return chunks.first().copyOf()
    val totalBytes = chunks.sumOf(ByteArray::size)
    val result = ByteArray(totalBytes)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}