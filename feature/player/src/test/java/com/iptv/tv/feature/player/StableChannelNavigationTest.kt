package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StableChannelNavigationTest {

    @Test
    fun `adjacent channel wraps in both directions`() {
        val ids = listOf(10L, 20L, 30L)

        assertEquals(10L, StableChannelNavigation.adjacentId(ids, 30L, 1))
        assertEquals(30L, StableChannelNavigation.adjacentId(ids, 10L, -1))
    }

    @Test
    fun `missing selection starts from edge matching direction`() {
        val ids = listOf(10L, 20L, 30L)

        assertEquals(10L, StableChannelNavigation.adjacentId(ids, 999L, 1))
        assertEquals(30L, StableChannelNavigation.adjacentId(ids, 999L, -1))
    }

    @Test
    fun `filter keeps current channel when still visible`() {
        assertEquals(
            20L,
            StableChannelNavigation.selectionAfterFilter(
                visibleChannelIds = listOf(10L, 20L, 30L),
                previousSelectedChannelId = 20L
            )
        )
    }

    @Test
    fun `filter selects first visible channel when current disappears`() {
        assertEquals(
            10L,
            StableChannelNavigation.selectionAfterFilter(
                visibleChannelIds = listOf(10L, 30L),
                previousSelectedChannelId = 20L
            )
        )
        assertNull(StableChannelNavigation.selectionAfterFilter(emptyList(), 20L))
    }

    @Test
    fun `page navigation clamps to list bounds`() {
        assertEquals(0, StableChannelNavigation.pageTargetIndex(2, 20, 5, -1))
        assertEquals(19, StableChannelNavigation.pageTargetIndex(18, 20, 5, 1))
        assertEquals(7, StableChannelNavigation.pageTargetIndex(2, 20, 5, 1))
    }

    @Test
    fun `invalid group clears subgroup too`() {
        assertEquals(
            null to null,
            StableChannelNavigation.normalizeGroupSelection(
                selectedGroup = "Новости",
                selectedSubGroup = "HD",
                availableGroups = listOf("Спорт"),
                availableSubGroups = listOf("HD")
            )
        )
    }

    @Test
    fun `valid group keeps only available subgroup`() {
        assertEquals(
            "Спорт" to null,
            StableChannelNavigation.normalizeGroupSelection(
                selectedGroup = "Спорт",
                selectedSubGroup = "4K",
                availableGroups = listOf("Спорт", "Новости"),
                availableSubGroups = listOf("HD", "SD")
            )
        )
    }
}
