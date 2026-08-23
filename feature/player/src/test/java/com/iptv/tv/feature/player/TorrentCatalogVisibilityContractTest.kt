package com.iptv.tv.feature.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentCatalogVisibilityContractTest {

    @Test
    fun stablePlayer_doesNotHideUnavailableChannelsOrExposeHideToggle() {
        val source = File("src/main/java/com/iptv/tv/feature/player/StablePlayerScreenReplacement.kt")
            .readText()

        assertFalse(source.contains("hideUnavailable"))
        assertFalse(source.contains("onToggleUnavailable"))
        assertFalse(source.contains("Скрывать недоступные"))
        assertFalse(source.contains("Показывать недоступные"))
        assertFalse(source.contains("it.health != ChannelHealth.UNAVAILABLE"))
    }

    @Test
    fun stablePlayer_rendersP2pAvailabilityInChannelLists() {
        val source = File("src/main/java/com/iptv/tv/feature/player/StablePlayerChannelBrowser.kt")
            .readText()

        assertTrue(source.contains("p2pChannelAvailabilityLabel"))
        assertTrue(source.contains("P2pChannelAvailabilityUiCache.statuses[channel.id]"))
    }
}
