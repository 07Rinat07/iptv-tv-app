package com.iptv.tv.feature.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2pChannelAvailabilityTest {

    @After
    fun clearUiCache() {
        P2pChannelAvailabilityUiCache.statuses.clear()
    }

    @Test
    fun startingAnotherChannelClearsOnlySupersededSearch() {
        P2pChannelAvailabilityUiCache.beginSearch(channelId = 11L)
        P2pChannelAvailabilityUiCache.mark(
            channelId = 12L,
            state = P2pChannelAvailabilityState.READY,
            peers = 2
        )

        P2pChannelAvailabilityUiCache.beginSearch(channelId = 13L)

        assertEquals(
            P2pChannelAvailabilityState.UNCHECKED,
            P2pChannelAvailabilityUiCache.statuses.getValue(11L).state
        )
        assertEquals(
            P2pChannelAvailabilityState.READY,
            P2pChannelAvailabilityUiCache.statuses.getValue(12L).state
        )
        assertEquals(
            P2pChannelAvailabilityState.SEARCHING,
            P2pChannelAvailabilityUiCache.statuses.getValue(13L).state
        )
    }

    @Test
    fun uncheckedChannel_isShownAsNotChecked() {
        assertEquals("P2P · не проверен", p2pChannelAvailabilityLabel(null))
    }

    @Test
    fun searchingChannel_doesNotPretendStalePeersAreCurrent() {
        assertEquals(
            "P2P · поиск пиров…",
            p2pChannelAvailabilityLabel(
                P2pChannelAvailability(
                    state = P2pChannelAvailabilityState.SEARCHING,
                    peers = 0
                )
            )
        )
    }

    @Test
    fun playingChannel_showsObservedPeerCount() {
        assertEquals(
            "P2P · играет · 3 пир.",
            p2pChannelAvailabilityLabel(
                P2pChannelAvailability(
                    state = P2pChannelAvailabilityState.PLAYING,
                    peers = 3
                )
            )
        )
    }

    @Test
    fun fullscreenOsd_hidesUncheckedP2pChannelUntilPlaybackProbeStarts() {
        assertNull(
            p2pChannelOsdLabel(
                streamUrl = "acestream://0123456789abcdefghij",
                status = null
            )
        )
    }

    @Test
    fun fullscreenOsd_neverShowsP2pBadgeForRegularStream() {
        assertNull(
            p2pChannelOsdLabel(
                streamUrl = "https://example.test/live/channel.m3u8",
                status = P2pChannelAvailability(
                    state = P2pChannelAvailabilityState.PLAYING,
                    peers = 4,
                    speedKbps = 2_500
                )
            )
        )
    }

    @Test
    fun fullscreenOsd_showsUsefulPlayingTelemetryForP2pChannel() {
        assertEquals(
            "P2P · играет · 4 пир. · 2500 Кбит/с",
            p2pChannelOsdLabel(
                streamUrl = "acestream://0123456789abcdefghij",
                status = P2pChannelAvailability(
                    state = P2pChannelAvailabilityState.PLAYING,
                    peers = 4,
                    speedKbps = 2_500
                )
            )
        )
    }

    @Test
    fun peerTimeout_isClassifiedAsNoPeers() {
        assertEquals(
            P2pChannelAvailabilityState.NO_PEERS,
            p2pAvailabilityFromResolveError(
                "Torrent TV: доступный пир не найден за отведённое время. Возможно, канал сейчас не раздаётся."
            )
        )
    }

    @Test
    fun unrelatedP2pFailure_isClassifiedAsError() {
        assertEquals(
            P2pChannelAvailabilityState.ERROR,
            p2pAvailabilityFromResolveError("P2P-поток не подготовлен: transport metadata unavailable")
        )
    }

    @Test
    fun connectedPeerWithoutMedia_isClassifiedAsErrorInsteadOfNoPeers() {
        assertEquals(
            P2pChannelAvailabilityState.ERROR,
            p2pAvailabilityFromResolveError(
                "Torrent TV: пир был найден, но данные потока не поступили " +
                    "за отведённое время. Попробуйте повторить запуск позже."
            )
        )
    }

    @Test
    fun genericPeerFailure_isNotMisclassifiedAsNoPeers() {
        assertEquals(
            P2pChannelAvailabilityState.ERROR,
            p2pAvailabilityFromResolveError("peer handshake failed")
        )
    }
}
