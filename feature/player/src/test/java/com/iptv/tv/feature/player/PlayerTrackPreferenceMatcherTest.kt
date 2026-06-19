package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTrackPreferenceMatcherTest {

    @Test
    fun select_prefersLanguageMatch() {
        val selected = PlayerTrackPreferenceMatcher.select(
            preference = PlayerTrackPreference(
                mode = PlayerTrackPreferenceMode.SELECTED,
                language = "ru",
                label = null
            ),
            candidates = listOf(
                candidate(trackIndex = 0, language = "en", label = "English"),
                candidate(trackIndex = 1, language = "ru", label = "Russian")
            )
        )

        assertEquals(1, selected?.trackIndex)
    }

    @Test
    fun select_usesLabelToDisambiguateSameLanguage() {
        val selected = PlayerTrackPreferenceMatcher.select(
            preference = PlayerTrackPreference(
                mode = PlayerTrackPreferenceMode.SELECTED,
                language = "en",
                label = "Original"
            ),
            candidates = listOf(
                candidate(trackIndex = 0, language = "en", label = "Commentary"),
                candidate(trackIndex = 1, language = "en", label = "Original")
            )
        )

        assertEquals(1, selected?.trackIndex)
    }

    @Test
    fun select_ignoresUnsupportedCandidates() {
        val selected = PlayerTrackPreferenceMatcher.select(
            preference = PlayerTrackPreference(
                mode = PlayerTrackPreferenceMode.SELECTED,
                language = "kk",
                label = "Kazakh"
            ),
            candidates = listOf(
                candidate(trackIndex = 0, language = "kk", label = "Kazakh", supported = false),
                candidate(trackIndex = 1, language = "en", label = "English", supported = true)
            )
        )

        assertNull(selected)
    }

    @Test
    fun select_returnsNullForAutoOrDisabledPreferences() {
        assertNull(
            PlayerTrackPreferenceMatcher.select(
                preference = PlayerTrackPreference(mode = PlayerTrackPreferenceMode.AUTO),
                candidates = listOf(candidate(trackIndex = 0, language = "en", label = "English"))
            )
        )
        assertNull(
            PlayerTrackPreferenceMatcher.select(
                preference = PlayerTrackPreference(mode = PlayerTrackPreferenceMode.DISABLED),
                candidates = listOf(candidate(trackIndex = 0, language = "en", label = "English"))
            )
        )
    }

    private fun candidate(
        trackIndex: Int,
        language: String?,
        label: String?,
        supported: Boolean = true
    ): PlayerTrackPreferenceCandidate {
        return PlayerTrackPreferenceCandidate(
            groupIndex = 0,
            trackIndex = trackIndex,
            language = language,
            label = label,
            supported = supported
        )
    }
}
