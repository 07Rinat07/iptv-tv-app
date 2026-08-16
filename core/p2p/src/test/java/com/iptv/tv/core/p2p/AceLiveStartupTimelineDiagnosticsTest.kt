package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLiveStartupTimelineDiagnosticsTest {
    @Test
    fun `emits one stable diagnostic record for first milestone occurrence`() {
        val records = mutableListOf<Pair<String, String>>()
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 1_000L,
            clockMillis = { 1_250L },
            diagnosticsObserver = { status, message -> records += status to message }
        )

        val first = diagnostics.mark(AceLiveStartupMilestone.TRANSPORT_CONNECTED)
        val duplicate = diagnostics.mark(
            AceLiveStartupMilestone.TRANSPORT_CONNECTED,
            atMillis = 1_900L
        )

        assertEquals(250L, first?.elapsedMillis)
        assertNull(duplicate)
        assertEquals(
            listOf(
                AceLiveStartupTimelineDiagnostics.STATUS to
                    "phase=connected, elapsed_ms=250"
            ),
            records
        )
    }

    @Test
    fun `observer failure cannot change timeline evidence`() {
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 2_000L,
            clockMillis = { 2_100L },
            diagnosticsObserver = { _, _ -> error("diagnostics sink unavailable") }
        )

        diagnostics.onConsumerLifecycle(AceLiveConsumerLifecycleEvent.Opened(readerId = 1L))

        assertEquals(
            listOf(
                AceLiveStartupTimelineEntry(
                    milestone = AceLiveStartupMilestone.HTTP_READER_OPEN,
                    elapsedMillis = 100L
                )
            ),
            diagnostics.snapshot()
        )
    }

    @Test
    fun `snapshot remains in canonical milestone order when runtime reports out of order`() {
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 5_000L,
            diagnosticsObserver = { _, _ -> }
        )

        diagnostics.onFirstRead(atMillis = 5_400L)
        diagnostics.onDiscoveryCandidates(candidateCount = 3, atMillis = 5_100L)
        diagnostics.onBufferReady(atMillis = 5_300L)

        assertEquals(
            listOf(
                AceLiveStartupMilestone.FIRST_CANDIDATE,
                AceLiveStartupMilestone.BUFFER_READY,
                AceLiveStartupMilestone.HTTP_FIRST_READ
            ),
            diagnostics.snapshot().map { it.milestone }
        )
    }

    @Test
    fun `maps transport peer and loopback boundaries without inferring missing stages`() {
        val records = mutableListOf<String>()
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 10_000L,
            diagnosticsObserver = { _, message -> records += message }
        )

        diagnostics.onTransportSelection(atMillis = 10_010L)
        diagnostics.onDirectAttempt(atMillis = 10_020L)
        diagnostics.onMetadataAttempt(atMillis = 10_030L)
        assertNull(diagnostics.onDiscoveryCandidates(candidateCount = 0, atMillis = 10_040L))
        diagnostics.onDiscoveryCandidates(candidateCount = 2, atMillis = 10_050L)
        diagnostics.onPoolEvent(
            AceLiveTcpPoolEvent.TransportConnected(peerId = 7L, reconnectAttempt = 0),
            atMillis = 10_060L
        )
        diagnostics.onPoolEvent(
            AceLiveTcpPoolEvent.HandshakeAccepted(peerId = 7L),
            atMillis = 10_070L
        )
        diagnostics.onPeerQuality(
            peer = peerQuality(peerId = 7L, windowUseful = false),
            atMillis = 10_080L
        )
        diagnostics.onPeerQuality(
            peer = peerQuality(peerId = 7L, windowUseful = true),
            atMillis = 10_090L
        )
        diagnostics.onFirstMedia(atMillis = 10_100L)
        diagnostics.onBufferReady(atMillis = 10_110L)
        diagnostics.onConsumerLifecycle(
            AceLiveConsumerLifecycleEvent.Opened(readerId = 3L),
            atMillis = 10_120L
        )
        diagnostics.onFirstRead(atMillis = 10_130L)

        assertEquals(
            listOf(
                AceLiveStartupMilestone.TRANSPORT_SELECTION,
                AceLiveStartupMilestone.DIRECT_ATTEMPT,
                AceLiveStartupMilestone.METADATA_ATTEMPT,
                AceLiveStartupMilestone.FIRST_CANDIDATE,
                AceLiveStartupMilestone.TRANSPORT_CONNECTED,
                AceLiveStartupMilestone.HANDSHAKE_ACCEPTED,
                AceLiveStartupMilestone.USEFUL_WINDOW,
                AceLiveStartupMilestone.FIRST_MEDIA,
                AceLiveStartupMilestone.BUFFER_READY,
                AceLiveStartupMilestone.HTTP_READER_OPEN,
                AceLiveStartupMilestone.HTTP_FIRST_READ
            ),
            diagnostics.snapshot().map { it.milestone }
        )
        assertEquals(11, records.size)
    }

    @Test
    fun `reconnect reopen and repeated media cannot overwrite first runtime evidence`() {
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 20_000L,
            diagnosticsObserver = { _, _ -> }
        )

        diagnostics.onPoolEvent(
            AceLiveTcpPoolEvent.TransportConnected(peerId = 1L, reconnectAttempt = 0),
            atMillis = 20_100L
        )
        diagnostics.onPoolEvent(
            AceLiveTcpPoolEvent.TransportConnected(peerId = 1L, reconnectAttempt = 1),
            atMillis = 20_900L
        )
        diagnostics.onConsumerLifecycle(
            AceLiveConsumerLifecycleEvent.Opened(readerId = 1L),
            atMillis = 20_200L
        )
        diagnostics.onConsumerLifecycle(
            AceLiveConsumerLifecycleEvent.Opened(readerId = 2L),
            atMillis = 21_200L
        )
        diagnostics.onFirstMedia(atMillis = 20_300L)
        diagnostics.onFirstMedia(atMillis = 21_300L)

        assertEquals(
            100L,
            diagnostics.snapshot().first {
                it.milestone == AceLiveStartupMilestone.TRANSPORT_CONNECTED
            }.elapsedMillis
        )
        assertEquals(
            200L,
            diagnostics.snapshot().first {
                it.milestone == AceLiveStartupMilestone.HTTP_READER_OPEN
            }.elapsedMillis
        )
        assertEquals(
            300L,
            diagnostics.snapshot().first {
                it.milestone == AceLiveStartupMilestone.FIRST_MEDIA
            }.elapsedMillis
        )
    }

    private fun peerQuality(
        peerId: Long,
        windowUseful: Boolean
    ) = AceLivePeerQualitySnapshot(
        peerId = peerId,
        connected = true,
        handshaked = true,
        windowUseful = windowUseful,
        unchoked = true,
        producing = false,
        recentBytesPerSecond = 0L,
        mediaAgeMillis = null,
        connectedAgeMillis = 0L,
        totalMediaBytes = 0L
    )
}
