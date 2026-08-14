package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveConsumerBufferPressureTrackerTest {
    @Test
    fun readersKeepIndependentHysteresisState() {
        val tracker = AceLiveConsumerBufferPressureTracker(
            settings = settings(),
            maxTrackedReaders = 4
        )

        assertEquals(
            AceLiveBufferPressure.HIGH,
            tracker.evaluate(consumer(readerId = 1L, playableBytes = 310L)).pressure
        )

        // 295 is below the raw HIGH boundary (300), so a fresh reader must classify as TARGET.
        // Reusing reader 1's HIGH hysteresis state would incorrectly keep this HIGH.
        assertEquals(
            AceLiveBufferPressure.TARGET,
            tracker.evaluate(consumer(readerId = 2L, playableBytes = 295L)).pressure
        )

        // Reader 1 retains its own HIGH hysteresis band.
        assertEquals(
            AceLiveBufferPressure.HIGH,
            tracker.evaluate(consumer(readerId = 1L, playableBytes = 295L)).pressure
        )
    }

    @Test
    fun evictedReaderStartsWithFreshClassification() {
        val tracker = AceLiveConsumerBufferPressureTracker(
            settings = settings(),
            maxTrackedReaders = 2
        )

        assertEquals(
            AceLiveBufferPressure.HIGH,
            tracker.evaluate(consumer(readerId = 1L, playableBytes = 310L)).pressure
        )
        tracker.evaluate(consumer(readerId = 2L, playableBytes = 150L))
        tracker.evaluate(consumer(readerId = 3L, playableBytes = 150L))

        // Reader 1 was least-recently-used and evicted; 295 must now classify without old HIGH state.
        assertEquals(
            AceLiveBufferPressure.TARGET,
            tracker.evaluate(consumer(readerId = 1L, playableBytes = 295L)).pressure
        )
    }

    private fun consumer(readerId: Long, playableBytes: Long) = AceLiveMediaConsumerSnapshot(
        readerId = readerId,
        consumerOffset = 0L,
        liveEdgeOffset = playableBytes,
        playableBytes = playableBytes,
        consumerBytesPerSecond = null,
        totalDeliveredBytes = 0L,
        fellBehind = false
    )

    private fun settings() = AceLiveBufferPressureSettings(
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
