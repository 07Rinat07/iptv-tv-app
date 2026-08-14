package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveBufferDiagnosticsReporterTest {
    @Test
    fun firstSnapshotEmitsAndStableReaderIsThrottled() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveBufferDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )
        val consumer = consumer(readerId = 1L, playableBytes = 2_000L)
        val pressure = pressure(AceLiveBufferPressure.LOW)

        reporter.maybeReport(consumer, pressure, nowMillis = 1_000L)
        reporter.maybeReport(consumer.copy(playableBytes = 1_900L), pressure, nowMillis = 2_000L)

        assertEquals(1, events.size)
        assertEquals("embedded_ace_live_buffer_pressure", events.single().first)
    }

    @Test
    fun materialPressureChangeEmitsImmediately() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveBufferDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )
        val consumer = consumer(readerId = 2L, playableBytes = 2_000L)

        reporter.maybeReport(
            consumer,
            pressure(AceLiveBufferPressure.TARGET),
            nowMillis = 1_000L
        )
        reporter.maybeReport(
            consumer.copy(playableBytes = 500L),
            pressure(AceLiveBufferPressure.CRITICAL),
            nowMillis = 1_100L
        )

        assertEquals(2, events.size)
        assertTrue(events.last().second.contains("pressure=critical"))
    }

    @Test
    fun fallBehindTransitionEmitsImmediately() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveBufferDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )
        val pressure = pressure(AceLiveBufferPressure.LOW)

        reporter.maybeReport(consumer(readerId = 3L), pressure, nowMillis = 1_000L)
        reporter.maybeReport(
            consumer(readerId = 3L, fellBehind = true),
            pressure,
            nowMillis = 1_100L
        )

        assertEquals(2, events.size)
        assertTrue(events.last().second.contains("fell_behind=true"))
    }

    @Test
    fun separateReadersHaveIndependentThrottleState() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveBufferDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )
        val pressure = pressure(AceLiveBufferPressure.TARGET)

        reporter.maybeReport(consumer(readerId = 10L), pressure, nowMillis = 1_000L)
        reporter.maybeReport(consumer(readerId = 11L), pressure, nowMillis = 1_100L)
        reporter.maybeReport(consumer(readerId = 10L), pressure, nowMillis = 1_200L)

        assertEquals(2, events.size)
        assertTrue(events[0].second.contains("reader=10"))
        assertTrue(events[1].second.contains("reader=11"))
    }

    @Test
    fun stableReaderRefreshesPeriodically() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveBufferDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )
        val consumer = consumer(readerId = 4L)
        val pressure = pressure(AceLiveBufferPressure.TARGET)

        reporter.maybeReport(consumer, pressure, nowMillis = 1_000L)
        reporter.maybeReport(consumer, pressure, nowMillis = 5_999L)
        reporter.maybeReport(consumer, pressure, nowMillis = 6_000L)

        assertEquals(2, events.size)
    }

    @Test
    fun trackedReaderStateRemainsBounded() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveBufferDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 10_000L,
            maxTrackedReaders = 2
        )
        val pressure = pressure(AceLiveBufferPressure.LOW)

        reporter.maybeReport(consumer(readerId = 1L), pressure, nowMillis = 1_000L)
        reporter.maybeReport(consumer(readerId = 2L), pressure, nowMillis = 2_000L)
        reporter.maybeReport(consumer(readerId = 3L), pressure, nowMillis = 3_000L)
        reporter.maybeReport(consumer(readerId = 1L), pressure, nowMillis = 3_500L)

        assertEquals(4, events.size)
    }

    @Test
    fun formatIncludesHeadroomRateAndOffsets() {
        val reporter = AceLiveBufferDiagnosticsReporter(observer = { _, _ -> })
        val consumer = consumer(
            readerId = 7L,
            playableBytes = 500_000L,
            consumerOffset = 1_000_000L,
            liveEdgeOffset = 1_500_000L,
            consumerBytesPerSecond = 125_000L,
            totalDeliveredBytes = 900_000L,
            fellBehind = true
        )
        val pressure = pressure(
            value = AceLiveBufferPressure.LOW,
            signal = AceLiveBufferPressureSignal.DURATION,
            playableBytes = 500_000L,
            playableDurationMillis = 4_000L,
            consumerBytesPerSecond = 125_000L
        )

        val message = reporter.formatMessage(consumer, pressure)

        assertTrue(message.contains("reader=7"))
        assertTrue(message.contains("pressure=low"))
        assertTrue(message.contains("signal=duration"))
        assertTrue(message.contains("playable_bytes=500000"))
        assertTrue(message.contains("playable_ms=4000"))
        assertTrue(message.contains("consumer_bps=125000"))
        assertTrue(message.contains("consumer_mbps=1.000"))
        assertTrue(message.contains("consumer_offset=1000000"))
        assertTrue(message.contains("live_edge=1500000"))
        assertTrue(message.contains("total_delivered=900000"))
        assertTrue(message.contains("fell_behind=true"))
    }

    private fun consumer(
        readerId: Long,
        playableBytes: Long = 2_000L,
        consumerOffset: Long = 1_000L,
        liveEdgeOffset: Long = consumerOffset + playableBytes,
        consumerBytesPerSecond: Long? = null,
        totalDeliveredBytes: Long = consumerOffset,
        fellBehind: Boolean = false
    ) = AceLiveMediaConsumerSnapshot(
        readerId = readerId,
        consumerOffset = consumerOffset,
        liveEdgeOffset = liveEdgeOffset,
        playableBytes = playableBytes,
        consumerBytesPerSecond = consumerBytesPerSecond,
        totalDeliveredBytes = totalDeliveredBytes,
        fellBehind = fellBehind
    )

    private fun pressure(
        value: AceLiveBufferPressure,
        signal: AceLiveBufferPressureSignal = AceLiveBufferPressureSignal.BYTES,
        playableBytes: Long = 2_000L,
        playableDurationMillis: Long? = null,
        consumerBytesPerSecond: Long? = null
    ) = AceLiveBufferPressureSnapshot(
        pressure = value,
        signal = signal,
        playableBytes = playableBytes,
        playableDurationMillis = playableDurationMillis,
        consumerBytesPerSecond = consumerBytesPerSecond,
        criticalBoundaryBytes = 512L,
        targetBoundaryBytes = 2_048L,
        highBoundaryBytes = 4_096L,
        criticalBoundaryDurationMillis = 1_500L,
        targetBoundaryDurationMillis = 4_000L,
        highBoundaryDurationMillis = 8_000L
    )
}
