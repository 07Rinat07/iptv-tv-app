package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.ParentalChannelGateRow
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualAllChannelCountTest {
    @Test
    fun usesSqlVisibleCountWhenParentalBlockingIsDisabled() {
        val result = virtualAllChannelCount(
            visibleCount = 12_345,
            parentalRows = listOf(
                ParentalChannelGateRow("adult", "Adult", "18+")
            ),
            parentalGate = ParentalChannelGate(
                enabled = false,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            )
        )

        assertEquals(12_345, result)
    }

    @Test
    fun filtersOnlyNarrowRowsWhenParentalBlockingIsEnabled() {
        val result = virtualAllChannelCount(
            visibleCount = 3,
            parentalRows = listOf(
                ParentalChannelGateRow("one", "News", "News"),
                ParentalChannelGateRow("adult", "Adult Cinema", "18+"),
                ParentalChannelGateRow("three", "Travel", "Travel")
            ),
            parentalGate = ParentalChannelGate(
                enabled = true,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult", "18+")
            )
        )

        assertEquals(2, result)
    }
}
