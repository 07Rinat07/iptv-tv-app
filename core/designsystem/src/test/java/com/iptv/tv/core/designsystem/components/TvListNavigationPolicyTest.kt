package com.iptv.tv.core.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvListNavigationPolicyTest {
    @Test
    fun pageDown_advancesByVisiblePageMinusOverlap() {
        assertEquals(9, calculateTvListScrollTarget(TvListScrollCommand.PAGE_DOWN, 3, 7, 30))
    }

    @Test
    fun pageUp_clampsAtStart() {
        assertEquals(0, calculateTvListScrollTarget(TvListScrollCommand.PAGE_UP, 3, 7, 30))
    }

    @Test
    fun pageDown_clampsAtLastItem() {
        assertEquals(9, calculateTvListScrollTarget(TvListScrollCommand.PAGE_DOWN, 8, 5, 10))
    }

    @Test
    fun startAndEnd_areDeterministic() {
        assertEquals(0, calculateTvListScrollTarget(TvListScrollCommand.START, 5, 4, 12))
        assertEquals(11, calculateTvListScrollTarget(TvListScrollCommand.END, 5, 4, 12))
    }

    @Test
    fun emptyList_hasNoTarget() {
        assertNull(calculateTvListScrollTarget(TvListScrollCommand.PAGE_DOWN, 0, 0, 0))
    }

    @Test
    fun invalidVisibleCount_stillMovesOneItem() {
        assertEquals(5, calculateTvListScrollTarget(TvListScrollCommand.PAGE_DOWN, 4, 0, 10))
    }
}
