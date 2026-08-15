package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLiveActiveConsumerSelectorTest {
    @Test
    fun openedReaderDoesNotBecomeActiveBeforeConfirmedDelivery() {
        val selector = AceLiveActiveConsumerSelector()

        assertNull(selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L)))
        assertNull(selector.activeSnapshot())
    }

    @Test
    fun firstConfirmedDeliverySelectsReader() {
        val selector = AceLiveActiveConsumerSelector()
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))

        val active = selector.onEvent(
            AceLiveConsumerLifecycleEvent.Delivered(snapshot(readerId = 1L, delivered = 188L))
        )

        assertEquals(1L, active?.readerId)
        assertEquals(188L, active?.totalDeliveredBytes)
    }

    @Test
    fun newerOpenedReaderDoesNotPreemptUntilItDelivers() {
        val selector = AceLiveActiveConsumerSelector()
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Delivered(snapshot(1L, 188L)))

        val stillFirst = selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(2L))

        assertEquals(1L, stillFirst?.readerId)
    }

    @Test
    fun newerConfirmedReaderPerformsMonotonicHandoff() {
        val selector = AceLiveActiveConsumerSelector()
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Delivered(snapshot(1L, 188L)))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(2L))

        val second = selector.onEvent(
            AceLiveConsumerLifecycleEvent.Delivered(snapshot(2L, 376L))
        )
        val afterLateOldDelivery = selector.onEvent(
            AceLiveConsumerLifecycleEvent.Delivered(snapshot(1L, 564L))
        )

        assertEquals(2L, second?.readerId)
        assertEquals(2L, afterLateOldDelivery?.readerId)
        assertEquals(376L, afterLateOldDelivery?.totalDeliveredBytes)
    }

    @Test
    fun closingActiveReaderFallsBackToNewestOpenConfirmedReader() {
        val selector = AceLiveActiveConsumerSelector()
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Delivered(snapshot(1L, 188L)))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(2L))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Delivered(snapshot(2L, 376L)))

        val fallback = selector.onEvent(AceLiveConsumerLifecycleEvent.Closed(2L))

        assertEquals(1L, fallback?.readerId)
        assertEquals(188L, fallback?.totalDeliveredBytes)
    }

    @Test
    fun closingUnconfirmedReplacementLeavesCurrentReaderActive() {
        val selector = AceLiveActiveConsumerSelector()
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Delivered(snapshot(1L, 188L)))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(2L))

        val active = selector.onEvent(AceLiveConsumerLifecycleEvent.Closed(2L))

        assertEquals(1L, active?.readerId)
    }

    @Test
    fun closingOnlyActiveReaderClearsSelection() {
        val selector = AceLiveActiveConsumerSelector()
        selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(1L))
        selector.onEvent(AceLiveConsumerLifecycleEvent.Delivered(snapshot(1L, 188L)))

        assertNull(selector.onEvent(AceLiveConsumerLifecycleEvent.Closed(1L)))
        assertNull(selector.activeSnapshot())
    }

    @Test
    fun trackedStateRemainsBounded() {
        val selector = AceLiveActiveConsumerSelector(maxTrackedReaders = 3)

        for (readerId in 1L..10L) {
            selector.onEvent(AceLiveConsumerLifecycleEvent.Opened(readerId))
            selector.onEvent(
                AceLiveConsumerLifecycleEvent.Delivered(snapshot(readerId, readerId * 188L))
            )
            if (readerId < 10L) {
                selector.onEvent(AceLiveConsumerLifecycleEvent.Closed(readerId))
            }
        }

        assertEquals(3, selector.trackedReaderCount())
        assertEquals(10L, selector.activeSnapshot()?.readerId)
    }

    private fun snapshot(
        readerId: Long,
        delivered: Long
    ) = AceLiveMediaConsumerSnapshot(
        readerId = readerId,
        consumerOffset = delivered,
        liveEdgeOffset = delivered + 1_880L,
        playableBytes = 1_880L,
        consumerBytesPerSecond = 188_000L,
        totalDeliveredBytes = delivered,
        fellBehind = false
    )
}
