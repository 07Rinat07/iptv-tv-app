package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLivePeerProductionTrackerTest {

    @Test
    fun `discovered candidates do not count as requestable or producing peers`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)

        tracker.recordDiscovery(7)
        tracker.onTransportConnected(peerId = 1L, nowMillis = 100L)
        tracker.onHandshakeAccepted(peerId = 1L)

        val snapshot = tracker.snapshot(nowMillis = 200L)

        assertEquals(7, snapshot.discoveredCandidates)
        assertEquals(1, snapshot.connectedPeers)
        assertEquals(1, snapshot.handshakedPeers)
        assertEquals(0, snapshot.windowUsefulPeers)
        assertEquals(0, snapshot.unchokedPeers)
        assertEquals(0, snapshot.producingPeers)
        assertEquals(0L, snapshot.aggregateBytesPerSecond)
        assertNull(snapshot.freshestMediaAgeMillis)
    }

    @Test
    fun `accepted media marks requestable peer producing and estimates rate after second sample`() {
        val tracker = AceLivePeerProductionTracker(
            producingFreshnessMillis = 5_000L,
            ewmaCurrentWeightPercent = 50L
        )
        tracker.onTransportConnected(peerId = 4L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 4L)
        tracker.onPeerRequestability(peerId = 4L, windowUseful = true, unchoked = true)

        tracker.onMediaProduced(peerId = 4L, mediaBytes = 256_000L, nowMillis = 1_000L)
        tracker.onMediaProduced(peerId = 4L, mediaBytes = 512_000L, nowMillis = 2_000L)

        val snapshot = tracker.snapshot(nowMillis = 2_250L)

        assertEquals(1, snapshot.windowUsefulPeers)
        assertEquals(1, snapshot.unchokedPeers)
        assertEquals(1, snapshot.producingPeers)
        assertEquals(512_000L, snapshot.aggregateBytesPerSecond)
        assertEquals(250L, snapshot.freshestMediaAgeMillis)
    }

    @Test
    fun `fresh media is not producing until peer is useful and unchoked`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)
        tracker.onTransportConnected(peerId = 5L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 5L)
        tracker.onPeerRequestability(peerId = 5L, windowUseful = true, unchoked = false)
        tracker.onMediaProduced(peerId = 5L, mediaBytes = 128_000L, nowMillis = 1_000L)

        val choked = tracker.snapshot(nowMillis = 1_100L)
        assertEquals(1, choked.windowUsefulPeers)
        assertEquals(0, choked.unchokedPeers)
        assertEquals(0, choked.producingPeers)
        assertEquals(0L, choked.aggregateBytesPerSecond)

        tracker.onPeerRequestability(peerId = 5L, windowUseful = true, unchoked = true)
        val requestable = tracker.snapshot(nowMillis = 1_200L)

        assertEquals(1, requestable.windowUsefulPeers)
        assertEquals(1, requestable.unchokedPeers)
        assertEquals(1, requestable.producingPeers)
        assertEquals(200L, requestable.freshestMediaAgeMillis)
    }

    @Test
    fun `losing useful live window removes fresh peer from producing immediately`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)
        tracker.onTransportConnected(peerId = 6L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 6L)
        tracker.onPeerRequestability(peerId = 6L, windowUseful = true, unchoked = true)
        tracker.onMediaProduced(peerId = 6L, mediaBytes = 128_000L, nowMillis = 1_000L)

        assertEquals(1, tracker.snapshot(nowMillis = 1_100L).producingPeers)

        tracker.onPeerRequestability(peerId = 6L, windowUseful = false, unchoked = true)
        val staleWindow = tracker.snapshot(nowMillis = 1_200L)

        assertEquals(0, staleWindow.windowUsefulPeers)
        assertEquals(1, staleWindow.unchokedPeers)
        assertEquals(0, staleWindow.producingPeers)
        assertEquals(0L, staleWindow.aggregateBytesPerSecond)
        assertNull(staleWindow.freshestMediaAgeMillis)
    }

    @Test
    fun `stale media peer stops counting as producing without losing connection state`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 2_000L)
        tracker.onTransportConnected(peerId = 2L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 2L)
        tracker.onPeerRequestability(peerId = 2L, windowUseful = true, unchoked = true)
        tracker.onMediaProduced(peerId = 2L, mediaBytes = 128_000L, nowMillis = 1_000L)

        val snapshot = tracker.snapshot(nowMillis = 3_001L)

        assertEquals(1, snapshot.connectedPeers)
        assertEquals(1, snapshot.handshakedPeers)
        assertEquals(1, snapshot.windowUsefulPeers)
        assertEquals(1, snapshot.unchokedPeers)
        assertEquals(0, snapshot.producingPeers)
        assertEquals(0L, snapshot.aggregateBytesPerSecond)
    }

    @Test
    fun `disconnect removes peer from all live quality counts`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)
        tracker.onTransportConnected(peerId = 8L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 8L)
        tracker.onPeerRequestability(peerId = 8L, windowUseful = true, unchoked = true)
        tracker.onMediaProduced(peerId = 8L, mediaBytes = 64_000L, nowMillis = 1_000L)

        tracker.onDisconnected(peerId = 8L)
        val snapshot = tracker.snapshot(nowMillis = 1_100L)

        assertEquals(0, snapshot.connectedPeers)
        assertEquals(0, snapshot.handshakedPeers)
        assertEquals(0, snapshot.windowUsefulPeers)
        assertEquals(0, snapshot.unchokedPeers)
        assertEquals(0, snapshot.producingPeers)
    }

    @Test
    fun `late output from buffered piece does not resurrect disconnected peer`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)
        tracker.onTransportConnected(peerId = 9L, nowMillis = 0L)
        tracker.onHandshakeAccepted(peerId = 9L)
        tracker.onPeerRequestability(peerId = 9L, windowUseful = true, unchoked = true)
        tracker.onDisconnected(peerId = 9L)

        tracker.onMediaProduced(peerId = 9L, mediaBytes = 188_000L, nowMillis = 2_000L)
        val snapshot = tracker.snapshot(nowMillis = 2_100L)

        assertEquals(0, snapshot.connectedPeers)
        assertEquals(0, snapshot.handshakedPeers)
        assertEquals(0, snapshot.windowUsefulPeers)
        assertEquals(0, snapshot.unchokedPeers)
        assertEquals(0, snapshot.producingPeers)
        assertEquals(0L, snapshot.aggregateBytesPerSecond)
        assertNull(snapshot.freshestMediaAgeMillis)
    }

    @Test
    fun `output evidence without lifecycle does not create a peer`() {
        val tracker = AceLivePeerProductionTracker(producingFreshnessMillis = 5_000L)

        tracker.onMediaProduced(peerId = 99L, mediaBytes = 188_000L, nowMillis = 1_000L)
        val snapshot = tracker.snapshot(nowMillis = 1_100L)

        assertEquals(0, snapshot.connectedPeers)
        assertEquals(0, snapshot.handshakedPeers)
        assertEquals(0, snapshot.producingPeers)
        assertNull(snapshot.freshestMediaAgeMillis)
    }

    @Test
    fun `aggregate rate includes only fresh requestable producing peers`() {
        val tracker = AceLivePeerProductionTracker(
            producingFreshnessMillis = 2_500L,
            ewmaCurrentWeightPercent = 100L
        )
        tracker.onTransportConnected(1L, 0L)
        tracker.onHandshakeAccepted(1L)
        tracker.onPeerRequestability(1L, windowUseful = true, unchoked = true)
        tracker.onTransportConnected(2L, 0L)
        tracker.onHandshakeAccepted(2L)
        tracker.onPeerRequestability(2L, windowUseful = true, unchoked = true)

        tracker.onMediaProduced(1L, 100_000L, 1_000L)
        tracker.onMediaProduced(1L, 200_000L, 2_000L)
        tracker.onMediaProduced(2L, 100_000L, 4_000L)
        tracker.onMediaProduced(2L, 300_000L, 5_000L)

        val snapshot = tracker.snapshot(nowMillis = 5_000L)

        assertEquals(2, snapshot.windowUsefulPeers)
        assertEquals(2, snapshot.unchokedPeers)
        assertEquals(1, snapshot.producingPeers)
        assertEquals(300_000L, snapshot.aggregateBytesPerSecond)
    }
}
