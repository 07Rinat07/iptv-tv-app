package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLiveBufferControllerTest {
    @Test
    fun zeroPlayableHeadroomIsCriticalAndFallsBackToBytesWithoutRate() {
        val snapshot = AceLiveBufferController().evaluate(playableBytes = 0L)

        assertEquals(AceLiveBufferPressure.CRITICAL, snapshot.pressure)
        assertEquals(AceLiveBufferPressureSignal.BYTES, snapshot.signal)
        assertEquals(0L, snapshot.playableBytes)
        assertNull(snapshot.playableDurationMillis)
        assertNull(snapshot.consumerBytesPerSecond)
    }

    @Test
    fun unknownOrNonPositiveConsumerRateUsesByteBoundaries() {
        val controller = AceLiveBufferController()
        val bytes = 3L * 1024L * 1024L

        val unknown = controller.evaluate(playableBytes = bytes, consumerBytesPerSecond = null)
        assertEquals(AceLiveBufferPressure.TARGET, unknown.pressure)
        assertEquals(AceLiveBufferPressureSignal.BYTES, unknown.signal)

        val zero = controller.evaluate(playableBytes = bytes, consumerBytesPerSecond = 0L)
        assertEquals(AceLiveBufferPressure.TARGET, zero.pressure)
        assertEquals(AceLiveBufferPressureSignal.BYTES, zero.signal)
        assertNull(zero.playableDurationMillis)
    }

    @Test
    fun knownConsumerRateMakesDurationAuthoritativeEvenWhenByteCountIsLarge() {
        val snapshot = AceLiveBufferController().evaluate(
            playableBytes = 10L * 1024L * 1024L,
            consumerBytesPerSecond = 10L * 1024L * 1024L
        )

        assertEquals(AceLiveBufferPressure.CRITICAL, snapshot.pressure)
        assertEquals(AceLiveBufferPressureSignal.DURATION, snapshot.signal)
        assertEquals(1_000L, snapshot.playableDurationMillis)
        assertEquals(10L * 1024L * 1024L, snapshot.consumerBytesPerSecond)
    }

    @Test
    fun byteHysteresisPreventsCriticalLowFlapping() {
        val controller = AceLiveBufferController(testSettings())

        assertEquals(AceLiveBufferPressure.CRITICAL, controller.evaluate(50L).pressure)
        assertEquals(AceLiveBufferPressure.CRITICAL, controller.evaluate(105L).pressure)
        assertEquals(AceLiveBufferPressure.LOW, controller.evaluate(110L).pressure)
        assertEquals(AceLiveBufferPressure.LOW, controller.evaluate(95L).pressure)
        assertEquals(AceLiveBufferPressure.CRITICAL, controller.evaluate(89L).pressure)
    }

    @Test
    fun targetAndHighStatesDoNotFlapAroundTheirBoundaries() {
        val controller = AceLiveBufferController(testSettings())

        assertEquals(AceLiveBufferPressure.LOW, controller.evaluate(150L).pressure)
        assertEquals(AceLiveBufferPressure.TARGET, controller.evaluate(210L).pressure)
        assertEquals(AceLiveBufferPressure.TARGET, controller.evaluate(195L).pressure)
        assertEquals(AceLiveBufferPressure.LOW, controller.evaluate(189L).pressure)

        assertEquals(AceLiveBufferPressure.HIGH, controller.evaluate(310L).pressure)
        assertEquals(AceLiveBufferPressure.HIGH, controller.evaluate(295L).pressure)
        assertEquals(AceLiveBufferPressure.TARGET, controller.evaluate(289L).pressure)
    }

    @Test
    fun durationHysteresisUsesConsumerHeadroomInsteadOfRawBytes() {
        val controller = AceLiveBufferController(testSettings())
        val rate = 1_000L

        assertEquals(
            AceLiveBufferPressure.CRITICAL,
            controller.evaluate(playableBytes = 900L, consumerBytesPerSecond = rate).pressure
        )
        assertEquals(
            AceLiveBufferPressure.CRITICAL,
            controller.evaluate(playableBytes = 1_050L, consumerBytesPerSecond = rate).pressure
        )
        assertEquals(
            AceLiveBufferPressure.LOW,
            controller.evaluate(playableBytes = 1_100L, consumerBytesPerSecond = rate).pressure
        )
        assertEquals(
            AceLiveBufferPressure.LOW,
            controller.evaluate(playableBytes = 950L, consumerBytesPerSecond = rate).pressure
        )
        assertEquals(
            AceLiveBufferPressure.CRITICAL,
            controller.evaluate(playableBytes = 899L, consumerBytesPerSecond = rate).pressure
        )
    }

    @Test
    fun changingFromByteFallbackToDurationSignalReclassifiesImmediately() {
        val controller = AceLiveBufferController()
        val bytes = 5L * 1024L * 1024L

        assertEquals(AceLiveBufferPressure.HIGH, controller.evaluate(bytes).pressure)

        val durationDriven = controller.evaluate(
            playableBytes = bytes,
            consumerBytesPerSecond = 5L * 1024L * 1024L
        )
        assertEquals(AceLiveBufferPressure.CRITICAL, durationDriven.pressure)
        assertEquals(AceLiveBufferPressureSignal.DURATION, durationDriven.signal)
    }

    @Test
    fun durationCalculationSaturatesInsteadOfOverflowing() {
        val snapshot = AceLiveBufferController().evaluate(
            playableBytes = Long.MAX_VALUE,
            consumerBytesPerSecond = 1L
        )

        assertEquals(Long.MAX_VALUE, snapshot.playableDurationMillis)
        assertEquals(AceLiveBufferPressure.HIGH, snapshot.pressure)
    }

    @Test
    fun negativePlayableBytesAreClampedToZero() {
        val snapshot = AceLiveBufferController().evaluate(
            playableBytes = Long.MIN_VALUE,
            consumerBytesPerSecond = 1_000L
        )

        assertEquals(0L, snapshot.playableBytes)
        assertEquals(0L, snapshot.playableDurationMillis)
        assertEquals(AceLiveBufferPressure.CRITICAL, snapshot.pressure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingDurationHysteresisIsRejected() {
        AceLiveBufferPressureSettings(
            criticalBoundaryDurationMillis = 1_000L,
            targetBoundaryDurationMillis = 2_000L,
            highBoundaryDurationMillis = 3_000L,
            durationHysteresisMillis = 501L
        )
    }

    private fun testSettings() = AceLiveBufferPressureSettings(
        criticalBoundaryDurationMillis = 1_000L,
        targetBoundaryDurationMillis = 2_000L,
        highBoundaryDurationMillis = 3_000L,
        durationHysteresisMillis = 100L,
        criticalBoundaryBytes = 100L,
        targetBoundaryBytes = 200L,
        highBoundaryBytes = 300L,
        bytesHysteresis = 10L
    )
}
