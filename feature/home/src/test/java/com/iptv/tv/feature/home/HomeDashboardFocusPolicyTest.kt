package com.iptv.tv.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeDashboardFocusPolicyTest {
    private val allSections = HomeDashboardFocusSection.entries.toSet()

    @Test
    fun `right moves navigation to content`() {
        assertEquals(
            HomeDashboardFocusSection.CONTENT,
            nextHomeDashboardFocusSection(
                current = HomeDashboardFocusSection.NAVIGATION,
                direction = HomeDashboardHorizontalDirection.RIGHT,
                availableSections = allSections
            )
        )
    }

    @Test
    fun `right moves content to sources`() {
        assertEquals(
            HomeDashboardFocusSection.SOURCES,
            nextHomeDashboardFocusSection(
                current = HomeDashboardFocusSection.CONTENT,
                direction = HomeDashboardHorizontalDirection.RIGHT,
                availableSections = allSections
            )
        )
    }

    @Test
    fun `left moves sources to content`() {
        assertEquals(
            HomeDashboardFocusSection.CONTENT,
            nextHomeDashboardFocusSection(
                current = HomeDashboardFocusSection.SOURCES,
                direction = HomeDashboardHorizontalDirection.LEFT,
                availableSections = allSections
            )
        )
    }

    @Test
    fun `navigation skips unavailable content section`() {
        assertEquals(
            HomeDashboardFocusSection.SOURCES,
            nextHomeDashboardFocusSection(
                current = HomeDashboardFocusSection.NAVIGATION,
                direction = HomeDashboardHorizontalDirection.RIGHT,
                availableSections = setOf(
                    HomeDashboardFocusSection.NAVIGATION,
                    HomeDashboardFocusSection.SOURCES
                )
            )
        )
    }

    @Test
    fun `outer edge does not trap focus`() {
        assertNull(
            nextHomeDashboardFocusSection(
                current = HomeDashboardFocusSection.SOURCES,
                direction = HomeDashboardHorizontalDirection.RIGHT,
                availableSections = allSections
            )
        )
    }

    @Test
    fun `restore keeps previous logical item when still available`() {
        assertEquals(
            "sources:ready:two",
            resolveHomeDashboardRestoreKey(
                savedKey = "sources:ready:two",
                availableKeys = listOf("navigation:playlists", "content:video", "sources:ready:two"),
                fallbackKey = "navigation:playlists"
            )
        )
    }

    @Test
    fun `restore falls back when previous item disappeared`() {
        assertEquals(
            "navigation:playlists",
            resolveHomeDashboardRestoreKey(
                savedKey = "content:playlist:42",
                availableKeys = listOf("navigation:playlists", "content:video"),
                fallbackKey = "navigation:playlists"
            )
        )
    }

    @Test
    fun `restore returns null without focusable candidates`() {
        assertNull(
            resolveHomeDashboardRestoreKey(
                savedKey = "content:video",
                availableKeys = emptyList(),
                fallbackKey = "content:video"
            )
        )
    }
}
