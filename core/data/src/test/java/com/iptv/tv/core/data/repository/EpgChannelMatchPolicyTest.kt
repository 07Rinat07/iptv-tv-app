package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgChannelMatchPolicyTest {
    @Test
    fun uniquePartialCandidateIsAccepted() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "discovery",
            channelIdsByTextKey = listOf(
                "discoveryhd" to "xmltv.discovery.hd",
                "history" to "xmltv.history"
            )
        )

        assertEquals("xmltv.discovery.hd", result)
    }

    @Test
    fun ambiguousPartialCandidatesFailClosed() {
        val firstOrder = listOf(
            "discoveryhd" to "xmltv.discovery.hd",
            "discoveryplus" to "xmltv.discovery.plus"
        )
        val reverseOrder = listOf(
            "discoveryplus" to "xmltv.discovery.plus",
            "discoveryhd" to "xmltv.discovery.hd"
        )

        assertNull(EpgChannelMatchPolicy.uniquePartialChannelId("discovery", firstOrder))
        assertNull(EpgChannelMatchPolicy.uniquePartialChannelId("discovery", reverseOrder))
    }

    @Test
    fun multipleMatchingAliasesForSameChannelRemainUnambiguous() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "news",
            channelIdsByTextKey = listOf(
                "worldnews" to "xmltv.news",
                "newsworld" to "xmltv.news"
            )
        )

        assertEquals("xmltv.news", result)
    }

    @Test
    fun collidingNormalizedKeysWithDifferentChannelsFailClosed() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "news",
            channelIdsByTextKey = listOf(
                "newshd" to "xmltv.news-hd",
                "newshd" to "xmltv.news hd"
            )
        )

        assertNull(result)
    }

    @Test
    fun noPartialCandidateReturnsNull() {
        assertNull(
            EpgChannelMatchPolicy.uniquePartialChannelId(
                normalizedChannelName = "sports",
                channelIdsByTextKey = listOf("movies" to "xmltv.movies")
            )
        )
    }

    @Test
    fun qualitySuffixOnPlaylistNameMatchesBaseXmlTvChannel() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "discoveryhd",
            channelIdsByTextKey = listOf(
                "discovery" to "xmltv.discovery",
                "history" to "xmltv.history"
            )
        )

        assertEquals("xmltv.discovery", result)
    }

    @Test
    fun resolutionAndAvailabilityDecorationsMatchBaseXmlTvChannel() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "brtv720pgeoblocked",
            channelIdsByTextKey = listOf(
                "brtv" to "xmltv.brtv",
                "birtv" to "xmltv.birtv"
            )
        )

        assertEquals("xmltv.brtv", result)
    }

    @Test
    fun not24x7DecorationAndResolutionMatchBaseXmlTvChannel() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "etvmanisa1080pnot247",
            channelIdsByTextKey = listOf(
                "etvmanisa" to "xmltv.etv-manisa",
                "etvkayseri" to "xmltv.etv-kayseri"
            )
        )

        assertEquals("xmltv.etv-manisa", result)
    }

    @Test
    fun qualitySuffixAliasStillFailsClosedWhenBaseIsAmbiguous() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "discoveryhd",
            channelIdsByTextKey = listOf(
                "discovery" to "xmltv.discovery",
                "discovery4k" to "xmltv.discovery.4k"
            )
        )

        assertNull(result)
    }

    @Test
    fun decoratedAliasStillFailsClosedWhenBaseIsAmbiguous() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "news720pgeoblocked",
            channelIdsByTextKey = listOf(
                "news" to "xmltv.news",
                "newshd" to "xmltv.news-hd"
            )
        )

        assertNull(result)
    }

    @Test
    fun shortNamesAreNotDestroyedByQualitySuffixRule() {
        assertEquals(setOf("hd"), EpgChannelMatchPolicy.qualityAliases("hd"))
        assertEquals(setOf("uhd"), EpgChannelMatchPolicy.qualityAliases("uhd"))
    }

    @Test
    fun blankNormalizedNameReturnsNull() {
        assertNull(
            EpgChannelMatchPolicy.uniquePartialChannelId(
                normalizedChannelName = "",
                channelIdsByTextKey = listOf("news" to "xmltv.news")
            )
        )
    }
}
