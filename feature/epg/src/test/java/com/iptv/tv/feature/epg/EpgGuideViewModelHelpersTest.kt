package com.iptv.tv.feature.epg

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgGuideViewModelHelpersTest {

    @Test
    fun nextEpgVisibleRowLimit_capsAtTotalRows() {
        assertEquals(60, nextEpgVisibleRowLimit(currentVisibleRows = 20, totalRows = 60))
        assertEquals(55, nextEpgVisibleRowLimit(currentVisibleRows = 40, totalRows = 55))
    }

    @Test
    fun buildEpgStatus_formatsFullAndPartialStates() {
        assertEquals("Найдено каналов с EPG: 12", buildEpgStatus(totalRows = 12, visibleRows = 12))
        assertEquals("Показано каналов с EPG: 40 из 95", buildEpgStatus(totalRows = 95, visibleRows = 40))
    }

    @Test
    fun shouldPrefetchEpgRows_loadsNearEndOnly() {
        assertEquals(
            false,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 20,
                totalLazyItems = 80,
                visibleRows = 40,
                totalRows = 1_000,
                isLoading = false,
                thresholdItems = 8
            )
        )
        assertEquals(
            true,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 73,
                totalLazyItems = 80,
                visibleRows = 40,
                totalRows = 1_000,
                isLoading = false,
                thresholdItems = 8
            )
        )
    }

    @Test
    fun shouldPrefetchEpgRows_skipsWhenBusyOrComplete() {
        assertEquals(
            false,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 99,
                totalLazyItems = 100,
                visibleRows = 100,
                totalRows = 100,
                isLoading = false
            )
        )
        assertEquals(
            false,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 99,
                totalLazyItems = 100,
                visibleRows = 40,
                totalRows = 1_000,
                isLoading = true
            )
        )
    }
}
