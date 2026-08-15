package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLiveAuthoritativeConsumerPressureTrackerTest {
    @Test
    fun openedReaderDoesNotCreateSchedulerPressure() {
        val tracker = tracker()

        assertNull(tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L)))
    }

    @Test
    fun confirmedDeliveryCreatesPressureForActiveReader() {
        val tracker = tracker()
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))

        val sample = tracker.onEvent(
            AceLiveConsumerLifecycleEvent.Delivered(consumer(1L, playableBytes = 250L))
        )

        assertEquals(1L, sample?.consumer?.readerId)
        assertEquals(AceLiveBufferPressure.TARGET, sample?.pressure?.pressure)
    }

    @Test
    fun lateDeliveryFromOlderReaderIsIgnoredAfterHandoff() {
        val tracker = tracker()
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Delivered(consumer(1L, 250L)))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(2L))
        val replacement = tracker.onEvent(
            AceLiveConsumerLifecycleEvent.Delivered(consumer(2L, 80L))
        )

        assertEquals(2L, replacement?.consumer?.readerId)
        assertEquals(AceLiveBufferPressure.CRITICAL, replacement?.pressure?.pressure)
        assertNull(
            tracker.onEvent(
                AceLiveConsumerLifecycleEvent.Delivered(consumer(1L, playableBytes = 350L))
            )
        )
    }

    @Test
    fun closingActiveReaderSurfacesFallbackPressureOnce() {
        val tracker = tracker()
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Delivered(consumer(1L, 250L)))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(2L))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Delivered(consumer(2L, 80L)))

        val fallback = tracker.onEvent(AceLiveConsumerLifecycleEvent.Closed(2L))

        assertEquals(1L, fallback?.consumer?.readerId)
        assertEquals(AceLiveBufferPressure.TARGET, fallback?.pressure?.pressure)
        assertNull(tracker.onEvent(AceLiveConsumerLifecycleEvent.Closed(2L)))
    }

    @Test
    fun closingNonActiveReaderDoesNotReemitCurrentPressure() {
        val tracker = tracker()
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Delivered(consumer(1L, 250L)))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Opened(2L))
        tracker.onEvent(AceLiveConsumerLifecycleEvent.Delivered(consumer(2L, 80L)))

        assertNull(tracker.onEvent(AceLiveConsumerLifecycleEvent.Closed(1L)))
    }

    private fun tracker() = AceLiveAuthoritativeConsumerPressureTracker(
        settings = AceLiveBufferPressureSettings(
            criticalBoundaryDurationMillis = 1_000L,
            targetBoundaryDurationMillis = 2_000L,
            highBoundaryDurationMillis = 3_000L,
            durationHysteresisMillis = 100L,
            criticalBoundaryBytes = 100L,
            targetBoundaryBytes = 200L,
            highBoundaryBytes = 300L,
            bytesHysteresis = 10L
        ),
        maxTrackedReaders = 4
    )

    private fun consumer(readerId: Long, playableBytes: Long) = AceLiveMediaConsumerSnapshot(
        readerId = readerId,
        consumerOffset = 0L,
        liveEdgeOffset = playableBytes,
        playableBytes = playableBytes,
        consumerBytesPerSecond = null,
        totalDeliveredBytes = 1L,
        fellBehind = false
    )
}
