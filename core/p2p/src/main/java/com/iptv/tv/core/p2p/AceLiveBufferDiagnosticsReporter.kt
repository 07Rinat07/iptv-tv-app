package com.iptv.tv.core.p2p

import java.util.Locale

/**
 * Emits bounded persistent diagnostics for confirmed loopback-consumer headroom.
 *
 * Each reader is throttled independently because Media3 retries/reopens can overlap briefly. Pressure,
 * signal and fall-behind transitions are material and emit immediately; rate/headroom drift is
 * refreshed periodically. A bounded reader-state map prevents disconnected clients from accumulating
 * diagnostic state forever when the runtime remains alive.
 */
internal class AceLiveBufferDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit,
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS,
    private val maxTrackedReaders: Int = DEFAULT_MAX_TRACKED_READERS
) {
    private val lock = Any()
    private val readerStates = linkedMapOf<Long, ReportState>()

    init {
        require(periodicIntervalMillis > 0L) { "periodicIntervalMillis must be positive" }
        require(maxTrackedReaders > 0) { "maxTrackedReaders must be positive" }
    }

    fun maybeReport(
        consumer: AceLiveMediaConsumerSnapshot,
        pressure: AceLiveBufferPressureSnapshot,
        nowMillis: Long
    ) {
        val now = nowMillis.coerceAtLeast(0L)
        val signature = MaterialSignature(
            pressure = pressure.pressure,
            signal = pressure.signal,
            fellBehind = consumer.fellBehind
        )
        val shouldReport = synchronized(lock) {
            val previous = readerStates[consumer.readerId]
            val materialChange = previous == null || previous.signature != signature
            val periodicRefresh = previous == null ||
                now < previous.lastReportedAtMillis ||
                now - previous.lastReportedAtMillis >= periodicIntervalMillis
            if (!materialChange && !periodicRefresh) {
                false
            } else {
                readerStates[consumer.readerId] = ReportState(
                    signature = signature,
                    lastReportedAtMillis = now
                )
                trimReaderStatesLocked()
                true
            }
        }
        if (!shouldReport) return

        runCatching {
            observer(STATUS, formatMessage(consumer, pressure))
        }
    }

    internal fun formatMessage(
        consumer: AceLiveMediaConsumerSnapshot,
        pressure: AceLiveBufferPressureSnapshot
    ): String {
        val consumerRate = consumer.consumerBytesPerSecond?.coerceAtLeast(0L)
        val consumerMegabitsPerSecond = consumerRate?.toDouble()
            ?.times(BITS_PER_BYTE)
            ?.div(BITS_PER_MEGABIT)
        val playableDuration = pressure.playableDurationMillis
            ?.coerceAtLeast(0L)
            ?.toString()
            ?: "none"
        return buildString {
            append("reader=")
            append(consumer.readerId)
            append(" pressure=")
            append(pressure.pressure.name.lowercase(Locale.US))
            append(" signal=")
            append(pressure.signal.name.lowercase(Locale.US))
            append(" playable_bytes=")
            append(consumer.playableBytes.coerceAtLeast(0L))
            append(" playable_ms=")
            append(playableDuration)
            append(" consumer_bps=")
            append(consumerRate?.toString() ?: "none")
            append(" consumer_mbps=")
            append(
                consumerMegabitsPerSecond?.let { value ->
                    String.format(Locale.US, "%.3f", value)
                } ?: "none"
            )
            append(" consumer_offset=")
            append(consumer.consumerOffset.coerceAtLeast(0L))
            append(" live_edge=")
            append(consumer.liveEdgeOffset.coerceAtLeast(0L))
            append(" total_delivered=")
            append(consumer.totalDeliveredBytes.coerceAtLeast(0L))
            append(" fell_behind=")
            append(consumer.fellBehind)
        }
    }

    private fun trimReaderStatesLocked() {
        while (readerStates.size > maxTrackedReaders) {
            val oldestReader = readerStates.entries
                .minByOrNull { (_, state) -> state.lastReportedAtMillis }
                ?.key
                ?: return
            readerStates.remove(oldestReader)
        }
    }

    private data class ReportState(
        val signature: MaterialSignature,
        val lastReportedAtMillis: Long
    )

    private data class MaterialSignature(
        val pressure: AceLiveBufferPressure,
        val signal: AceLiveBufferPressureSignal,
        val fellBehind: Boolean
    )

    private companion object {
        const val STATUS = "embedded_ace_live_buffer_pressure"
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS = 5_000L
        const val DEFAULT_MAX_TRACKED_READERS = 8
        const val BITS_PER_BYTE = 8.0
        const val BITS_PER_MEGABIT = 1_000_000.0
    }
}
