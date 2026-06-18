package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentalChannelFilterTest {

    @Test
    fun decodeKeywords_normalizesCsvAndFallsBackToDefaults() {
        assertTrue(ParentalChannelFilter.decodeKeywords(null).contains("adult"))
        assertTrue(ParentalChannelFilter.decodeKeywords(" ").contains("adult"))
        assertEquals(
            listOf("adult", "18+"),
            ParentalChannelFilter.decodeKeywords(" adult, 18+, Adult ")
        )
    }

    @Test
    fun isBlocked_matchesNameGroupAndTvgIdIgnoringCase() {
        val gate = ParentalChannelGate(
            enabled = true,
            hideAdultChannels = true,
            blockedKeywords = listOf("adult", "18+")
        )

        assertTrue(
            ParentalChannelFilter.isBlocked(
                name = "Movie Night",
                groupName = "Adult",
                tvgId = "movie-night",
                gate = gate
            )
        )
        assertTrue(
            ParentalChannelFilter.isBlocked(
                name = "18+ Cinema",
                groupName = "Movies",
                tvgId = "cinema",
                gate = gate
            )
        )
        assertTrue(
            ParentalChannelFilter.isBlocked(
                name = "Cinema",
                groupName = "Movies",
                tvgId = "provider-adult-hd",
                gate = gate
            )
        )
    }

    @Test
    fun isBlocked_ignoresBlankKeywordsAndAllowsSafeChannels() {
        val gate = ParentalChannelGate(
            enabled = true,
            hideAdultChannels = true,
            blockedKeywords = listOf(" ", "xxx")
        )

        assertFalse(
            ParentalChannelFilter.isBlocked(
                name = "Kids HD",
                groupName = "Family",
                tvgId = "kids",
                gate = gate
            )
        )
    }

    @Test
    fun isBlocked_doesNotBlockWhenParentalOrHideAdultIsDisabled() {
        val disabledGate = ParentalChannelGate(
            enabled = false,
            hideAdultChannels = true,
            blockedKeywords = listOf("adult")
        )
        val visibleAdultGate = ParentalChannelGate(
            enabled = true,
            hideAdultChannels = false,
            blockedKeywords = listOf("adult")
        )

        assertFalse(
            ParentalChannelFilter.isBlocked(
                name = "Adult Movies",
                groupName = "Adult",
                tvgId = null,
                gate = disabledGate
            )
        )
        assertFalse(
            ParentalChannelFilter.isBlocked(
                name = "Adult Movies",
                groupName = "Adult",
                tvgId = null,
                gate = visibleAdultGate
            )
        )
    }
}
