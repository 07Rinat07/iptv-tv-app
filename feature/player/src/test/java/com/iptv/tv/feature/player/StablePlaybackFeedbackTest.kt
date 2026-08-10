package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StablePlaybackFeedbackTest {

    @Test
    fun `error has priority over connecting state`() {
        assertEquals(
            StablePlaybackFeedback(
                message = "Не удалось подготовить поток",
                isError = true
            ),
            stablePlaybackFeedback(
                lastError = "  Не удалось подготовить поток  ",
                isStartingPlayback = true
            )
        )
    }

    @Test
    fun `connecting state is visible before player session exists`() {
        assertEquals(
            StablePlaybackFeedback(
                message = "Подключение к каналу…",
                isError = false
            ),
            stablePlaybackFeedback(
                lastError = null,
                isStartingPlayback = true
            )
        )
    }

    @Test
    fun `idle state has no feedback banner`() {
        assertNull(
            stablePlaybackFeedback(
                lastError = "   ",
                isStartingPlayback = false
            )
        )
    }
}
