package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLivePeerProductionTrackerTest {

    @Test
    fun `discovered candidates do not count as producing peers`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)

        tracker.recordDiscovery(7)
        tracker.onTransportConnected(peerId = 1L, nowMillis = 100L)
        tracker.onHandshakeAccepted(peerId = 1L)

        val snapshot = tracker.snapshot(nowMillis = 200L)

        assertEquals(7, snapshot.discoveredCandidates)
        assertEquals(1, snapshot.connectedPeers)
        assertEquals(1, snapshot.handshakedPeers)
        assertEquals(0, snapshot.producingPeers)
        assertEquals(0L, snapshot.aggregateBytesPerSecond)
        assertNull(snapshot.freshestMediaAgeMillis)
    }

    @Test
    fun `accepted media marks peer producing and estimates rate after second sample`() {
        val tracker = AceLivePeerProductionTracker(
            producingFreshnessMillis = 5_000L,
            ewmaCurrentWeightPercent = 50L
        )
        tracker.onTransportConnected(peerId = 4L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 4L)

        tracker.onMediaProduced(peerId = 4L, mediaBytes = 256_000L, nowMillis = 1_000L)
        tracker.onMediaProduced(peerId = 4L, mediaBytes = 512_000L, nowMillis = 2_000L)

        val snapshot = tracker.snapshot(nowMillis = 2_250L)

        assertEquals(1, snapshot.producingPeers)
        assertEquals(512_000L, snapshot.aggregateBytesPerSecond)
        assertEquals(250L, snapshot.freshestMediaAgeMillis)
    }

    @Test
    fun `stale media peer stops counting as producing without losing connection state`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 2_000L)
        tracker.onTransportConnected(peerId = 2L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 2L)
        tracker.onMediaProduced(peerId = 2L, mediaBytes = 128_000L, nowMillis = 1_000L)

        val snapshot = tracker.snapshot(nowMillis = 3_001L)

        assertEquals(1, snapshot.connectedPeers)
        assertEquals(1, snapshot.handshakedPeers)
        assertEquals(0, snapshot.producingPeers)
        assertEquals(0L, snapshot.aggregateBytesPerSecond)
    }

    @Test
    fun `disconnect removes peer from connected handshaked and producing counts`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)
        tracker.onTransportConnected(peerId = 8L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 8L)
        tracker.onMediaProduced(peerId = 8L, mediaBytes = 64_000L, nowMillis = 1_000L)

        tracker.onDisconnected(peerId = 8L)
        val snapshot = tracker.snapshot(nowMillis = 1_100L)

        assertEquals(0, snapshot.connectedPeers)
        assertEquals(0, snapshot.handshakedPeers)
        assertEquals(0, snapshot.producingPeers)
    }

    @Test
    fun `aggregate rate includes only fresh producing peers`() {
        val tracker = AceLivePeerProductionTracker(
            producingFreshnessMillis = 2_500L,
            ewmaCurrentWeightPercent = 100L
        )
        tracker.onTransportConnected(1L, 0L)
        tracker.onHandshakeAccepted(1L)
        tracker.onTransportConnected(2L, 0L)
        tracker.onHandshakeAccepted(2L)

        tracker.onMediaProduced(1L, 100_000L, 1_000L)
        tracker.onMediaProduced(1L, 200_000L, 2_000L)
        tracker.onMediaProduced(2L, 100_000L, 4_000L)
        tracker.onMediaProduced(2L, 300_000L, 5_000L)

        val snapshot = tracker.snapshot(nowMillis = 5_000L)

        assertEquals(1, snapshot.producingPeers)
        assertEquals(300_000L, snapshot.aggregateBytesPerSecond)
    }
}
