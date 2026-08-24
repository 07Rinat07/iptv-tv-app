package com.iptv.tv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDashboardFocusPolicyTest {
    @Test
    fun `horizontal graph moves from navigation through main to quick sources`() {
        assertEquals(
            HomeDashboardFocusZone.MAIN_CONTENT,
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.NAVIGATION,
                HomeDashboardFocusDirection.RIGHT
            )
        )
        assertEquals(
            HomeDashboardFocusZone.QUICK_SOURCES,
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.MAIN_CONTENT,
                HomeDashboardFocusDirection.RIGHT
            )
        )
    }

    @Test
    fun `horizontal graph moves back from quick sources through main to navigation`() {
        assertEquals(
            HomeDashboardFocusZone.MAIN_CONTENT,
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.QUICK_SOURCES,
                HomeDashboardFocusDirection.LEFT
            )
        )
        assertEquals(
            HomeDashboardFocusZone.NAVIGATION,
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.MAIN_CONTENT,
                HomeDashboardFocusDirection.LEFT
            )
        )
    }

    @Test
    fun `channel rail leaves horizontal dpad movement to local row`() {
        assertNull(
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.CHANNEL_RAIL,
                HomeDashboardFocusDirection.LEFT
            )
        )
        assertNull(
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.CHANNEL_RAIL,
                HomeDashboardFocusDirection.RIGHT
            )
        )
    }

    @Test
    fun `outer edges do not wrap or create a focus trap`() {
        assertNull(
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.NAVIGATION,
                HomeDashboardFocusDirection.LEFT
            )
        )
        assertNull(
            nextHomeDashboardFocusZone(
                HomeDashboardFocusZone.QUICK_SOURCES,
                HomeDashboardFocusDirection.RIGHT
            )
        )
    }

    @Test
    fun `saved focus restores known zone and falls back to main content`() {
        assertEquals(
            HomeDashboardFocusZone.CHANNEL_RAIL,
            restoreHomeDashboardFocusZone(HomeDashboardFocusZone.CHANNEL_RAIL.name)
        )
        assertEquals(
            HomeDashboardFocusZone.QUICK_SOURCES,
            restoreHomeDashboardFocusZone(HomeDashboardFocusZone.QUICK_SOURCES.name)
        )
        assertEquals(
            HomeDashboardFocusZone.MAIN_CONTENT,
            restoreHomeDashboardFocusZone("unknown")
        )
        assertEquals(
            HomeDashboardFocusZone.MAIN_CONTENT,
            restoreHomeDashboardFocusZone(null)
        )
    }

    @Test
    fun `quick focus item indexes follow rendered lazy list order`() {
        assertEquals(
            1,
            homeDashboardQuickFocusItemIndex(
                HomeDashboardQuickFocusAnchor.READY_PLAYLIST,
                readySourceCount = 3,
                hasScanner = true
            )
        )
        assertEquals(
            4,
            homeDashboardQuickFocusItemIndex(
                HomeDashboardQuickFocusAnchor.SCANNER,
                readySourceCount = 3,
                hasScanner = true
            )
        )
        assertEquals(
            5,
            homeDashboardQuickFocusItemIndex(
                HomeDashboardQuickFocusAnchor.PRIMARY_ACTION,
                readySourceCount = 3,
                hasScanner = true
            )
        )
    }

    @Test
    fun `quick focus policy rejects missing anchors`() {
        assertNull(
            homeDashboardQuickFocusItemIndex(
                HomeDashboardQuickFocusAnchor.READY_PLAYLIST,
                readySourceCount = 0,
                hasScanner = false
            )
        )
        assertNull(
            homeDashboardQuickFocusItemIndex(
                HomeDashboardQuickFocusAnchor.SCANNER,
                readySourceCount = 3,
                hasScanner = false
            )
        )
        assertNull(
            homeDashboardQuickFocusItemIndex(
                HomeDashboardQuickFocusAnchor.NONE,
                readySourceCount = 3,
                hasScanner = true
            )
        )
    }
}
