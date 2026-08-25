package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class StableResponsivePlayerLayoutTest {

    @Test
    fun typicalLaptopWindow_usesMediumNewPlayer() {
        assertEquals(
            StableResponsivePlayerLayout.MEDIUM,
            stableResponsivePlayerLayout(widthDp = 1024f, heightDp = 768f)
        )
    }

    @Test
    fun tvBoxDensityReducedViewport_staysInNewPlayerFamily() {
        assertEquals(
            StableResponsivePlayerLayout.COMPACT,
            stableResponsivePlayerLayout(widthDp = 853f, heightDp = 480f)
        )
    }

    @Test
    fun narrowWindow_usesCompactNewPlayer() {
        assertEquals(
            StableResponsivePlayerLayout.COMPACT,
            stableResponsivePlayerLayout(widthDp = 640f, heightDp = 720f)
        )
    }

    @Test
    fun lowHeightWindow_usesCompactNewPlayer() {
        assertEquals(
            StableResponsivePlayerLayout.COMPACT,
            stableResponsivePlayerLayout(widthDp = 1200f, heightDp = 420f)
        )
    }

    @Test
    fun mediumBoundary_isDeterministic() {
        assertEquals(
            StableResponsivePlayerLayout.MEDIUM,
            stableResponsivePlayerLayout(widthDp = 760f, heightDp = 500f)
        )
    }
}
