package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgDisplayNameMatchPolicyTest {
    @Test
    fun basePlaylistNameMatchesDecoratedDisplayNameWithTechnicalChannelId() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf(
                "discoveryhd" to "42",
                "history" to "77"
            )
        )

        assertEquals("42", EpgDisplayNameMatchPolicy.uniqueChannelId("discovery", index))
    }

    @Test
    fun decoratedPlaylistNameMatchesBaseDisplayName() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf("discovery" to "xmltv-1001")
        )

        assertEquals("xmltv-1001", EpgDisplayNameMatchPolicy.uniqueChannelId("discovery4k", index))
    }

    @Test
    fun duplicateExactDisplayNameAcrossDifferentChannelsFailsClosed() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf(
                "discovery" to "xmltv-a",
                "discovery" to "xmltv-b"
            )
        )

        assertNull(EpgDisplayNameMatchPolicy.uniqueChannelId("discovery", index))
    }

    @Test
    fun baseAndRegionalDisplayFeedsFailClosedForDecoratedPlaylistName() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf(
                "discovery" to "xmltv-base",
                "discoveryrussia" to "xmltv-russia"
            )
        )

        assertNull(EpgDisplayNameMatchPolicy.uniqueChannelId("discoveryrussiahd", index))
    }

    @Test
    fun multipleDisplayAliasesForSameXmlTvChannelRemainUnambiguous() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf(
                "discovery" to "xmltv-discovery",
                "discoveryhd" to "xmltv-discovery"
            )
        )

        assertEquals(
            "xmltv-discovery",
            EpgDisplayNameMatchPolicy.uniqueChannelId("discovery4k", index)
        )
    }

    @Test
    fun ambiguousBaseAliasCannotBeBypassedByMoreSpecificAlias() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf(
                "discoveryhd" to "xmltv-hd",
                "discovery" to "xmltv-base"
            )
        )

        assertNull(EpgDisplayNameMatchPolicy.uniqueChannelId("discoveryhd", index))
    }

    @Test
    fun arbitrarySubstringSimilarityIsNotAccepted() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf(
                "worldnews" to "xmltv-world",
                "newsworld" to "xmltv-other"
            )
        )

        assertNull(EpgDisplayNameMatchPolicy.uniqueChannelId("news", index))
    }

    @Test
    fun blankNamesNeverMatch() {
        val index = EpgDisplayNameMatchPolicy.buildIndex(
            listOf("discovery" to "xmltv-discovery")
        )

        assertNull(EpgDisplayNameMatchPolicy.uniqueChannelId("", index))
        assertNull(
            EpgDisplayNameMatchPolicy.uniqueChannelId(
                "discovery",
                EpgDisplayNameMatchPolicy.buildIndex(listOf("" to "xmltv-discovery"))
            )
        )
    }
}
