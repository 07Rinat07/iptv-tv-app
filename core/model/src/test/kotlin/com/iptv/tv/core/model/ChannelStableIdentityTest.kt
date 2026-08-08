package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChannelStableIdentityTest {
    @Test
    fun tvgIdIsPreferredAndNormalized() {
        val first = ChannelStableIdentity.key(
            tvgId = "  Discovery.HD ",
            name = "Discovery HD",
            streamUrl = "https://a/1"
        )
        val second = ChannelStableIdentity.key(
            tvgId = "discovery.hd",
            name = "Discovery Channel",
            streamUrl = "https://b/2"
        )

        assertEquals("tvg:discovery.hd", first)
        assertEquals(first, second)
    }

    @Test
    fun normalizedNamePreservesLegacyFallback() {
        val first = ChannelStableIdentity.key(null, "Матч! ТВ HD", "https://a/1")
        val second = ChannelStableIdentity.key("", "Матч ТВ", "https://b/2")

        assertEquals("name:матч тв", first)
        assertEquals(first, second)
    }

    @Test
    fun normalizedUrlIsFinalFallbackAndDropsQueryCredentials() {
        val first = ChannelStableIdentity.key(
            tvgId = null,
            name = "",
            streamUrl = "HTTPS://Example.COM/live/channel?token=first"
        )
        val second = ChannelStableIdentity.key(
            tvgId = "",
            name = " ",
            streamUrl = "https://example.com/live/channel?token=second"
        )

        assertEquals("url:https://example.com/live/channel", first)
        assertEquals(first, second)
    }

    @Test
    fun logicalIdentityCanFeedCanonicalNodesWhileNodesRemainParentScoped() {
        val logicalKey = ChannelStableIdentity.key(
            tvgId = "news.world",
            name = "World News",
            streamUrl = "https://one.example/live"
        )
        val sameLogicalKey = ChannelStableIdentity.key(
            tvgId = "NEWS.WORLD",
            name = "World News HD",
            streamUrl = "https://two.example/live"
        )
        val provenance = CatalogProvenance(
            origin = CatalogOriginKind.USER_IMPORT,
            sourceKey = "user-imports",
            sourceType = PlaylistSourceType.URL
        )
        val firstPlaylist = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist:first"
        )
        val secondPlaylist = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist:second"
        )

        assertEquals(logicalKey, sameLogicalKey)
        assertNotEquals(
            CatalogNodeIdFactory.child(CatalogNodeKind.CHANNEL, firstPlaylist, logicalKey),
            CatalogNodeIdFactory.child(CatalogNodeKind.CHANNEL, secondPlaylist, sameLogicalKey)
        )
    }
}
