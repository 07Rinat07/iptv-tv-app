package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgChannelMatchPolicyTest {
    @Test
    fun presentationAliasCandidateIsAccepted() {
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
    fun ambiguousAliasCandidatesFailClosed() {
        val firstOrder = listOf(
            "discoveryhd" to "xmltv.discovery.hd",
            "discovery4k" to "xmltv.discovery.4k"
        )
        val reverseOrder = firstOrder.reversed()

        assertNull(EpgChannelMatchPolicy.uniquePartialChannelId("discovery", firstOrder))
        assertNull(EpgChannelMatchPolicy.uniquePartialChannelId("discovery", reverseOrder))
    }

    @Test
    fun multipleMatchingAliasesForSameChannelRemainUnambiguous() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "news",
            channelIdsByTextKey = listOf(
                "newshd" to "xmltv.news",
                "news4k" to "xmltv.news"
            )
        )

        assertEquals("xmltv.news", result)
    }

    @Test
    fun arbitrarySubstringSimilarityIsNotAccepted() {
        assertNull(
            EpgChannelMatchPolicy.uniquePartialChannelId(
                normalizedChannelName = "news",
                channelIdsByTextKey = listOf(
                    "worldnews" to "xmltv.world-news",
                    "newsworld" to "xmltv.news-world"
                )
            )
        )
    }

    @Test
    fun collidingNormalizedKeysWithDifferentChannelsFailClosed() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "newshd",
            channelIdsByTextKey = listOf(
                "newshd" to "xmltv.news-hd",
                "newshd" to "xmltv.news hd"
            )
        )

        assertNull(result)
    }

    @Test
    fun noAliasCandidateReturnsNull() {
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
    fun trailingTvLabelOnPlaylistNameMatchesBaseXmlTvChannel() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "матчтв",
            channelIdsByTextKey = listOf(
                "матч" to "xmltv.match",
                "матчстрана" to "xmltv.match-country"
            )
        )

        assertEquals("xmltv.match", result)
    }

    @Test
    fun trailingTvAliasFailsClosedWhenBaseAndTvFeedsBothExist() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "матчтв",
            channelIdsByTextKey = listOf(
                "матч" to "xmltv.match",
                "матчтв" to "xmltv.match-tv"
            )
        )

        assertNull(result)
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
    fun boundedRegionalSuffixMatchesBaseXmlTvChannel() {
        assertEquals(
            "xmltv.russia1",
            EpgChannelMatchPolicy.uniquePartialChannelId(
                normalizedChannelName = "россия1москваhd",
                channelIdsByTextKey = listOf(
                    "россия1" to "xmltv.russia1",
                    "россия24" to "xmltv.russia24"
                )
            )
        )
        assertEquals(
            "xmltv.discovery",
            EpgChannelMatchPolicy.uniquePartialChannelId(
                normalizedChannelName = "discoveryrussia",
                channelIdsByTextKey = listOf("discovery" to "xmltv.discovery")
            )
        )
    }

    @Test
    fun regionalAliasFailsClosedWhenBaseAndRegionalFeedsBothExist() {
        val result = EpgChannelMatchPolicy.uniquePartialChannelId(
            normalizedChannelName = "discoveryrussia",
            channelIdsByTextKey = listOf(
                "discovery" to "xmltv.discovery",
                "discoveryrussia" to "xmltv.discovery.russia"
            )
        )

        assertNull(result)
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
        assertEquals(setOf("mtv"), EpgChannelMatchPolicy.qualityAliases("mtv"))
        assertEquals(setOf("htv"), EpgChannelMatchPolicy.qualityAliases("htv"))
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
