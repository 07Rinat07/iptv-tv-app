package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class P2pChannelAvailabilityTest {

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
            "P2P · играет · пиры 3",
            p2pChannelAvailabilityLabel(
                P2pChannelAvailability(
                    state = P2pChannelAvailabilityState.PLAYING,
                    peers = 3
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
}
