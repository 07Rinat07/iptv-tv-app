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
}
