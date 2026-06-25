package com.iptv.tv.feature.scanner

import com.iptv.tv.core.model.ScannerLearnedQuery
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAiQueryAssistantTest {

    private val assistant = LocalAiQueryAssistant()

    @Test
    fun `russian intent produces russian-centric variants`() {
        val keywords = assistant.inferIntentKeywords("русские каналы", emptyList())
        val variants = assistant.buildAiVariants(
            query = "русские каналы",
            manualKeywords = keywords,
            inferredKeywords = keywords
        )

        assertTrue(variants.any { it.contains("russian", ignoreCase = true) })
    }

    @Test
    fun `movie intent produces movie-centric variants`() {
        val keywords = assistant.inferIntentKeywords("movie iptv", listOf("vod"))
        val variants = assistant.buildAiVariants(
            query = "movie iptv",
            manualKeywords = keywords,
            inferredKeywords = keywords
        )

        assertTrue(variants.any { it.contains("movie", ignoreCase = true) || it.contains("cinema", ignoreCase = true) })
    }

    @Test
    fun `sport intent includes raw and category-oriented variants`() {
        val keywords = assistant.inferIntentKeywords("sports football", emptyList())
        val variants = assistant.buildAiVariants(
            query = "sports football",
            manualKeywords = keywords,
            inferredKeywords = keywords
        )

        assertTrue(variants.any { it.contains("raw.githubusercontent.com", ignoreCase = true) })
        assertTrue(variants.any { it.contains("iptv-org", ignoreCase = true) && it.contains("sports", ignoreCase = true) })
    }

    @Test
    fun `voxlist intent keeps direct repository variants`() {
        val keywords = assistant.inferIntentKeywords("voxlist iptv", emptyList())
        val variants = assistant.buildAiVariants(
            query = "voxlist iptv",
            manualKeywords = keywords,
            inferredKeywords = keywords
        )

        assertTrue(variants.any { it.contains("voxlist", ignoreCase = true) && it.contains("m3u", ignoreCase = true) })
        assertTrue(variants.any { it.contains("raw.githubusercontent.com", ignoreCase = true) })
    }

    @Test
    fun `assistant always keeps playlist core terms`() {
        val keywords = assistant.inferIntentKeywords("sport", emptyList())

        assertTrue(keywords.any { it.equals("iptv", ignoreCase = true) })
        assertTrue(keywords.any { it.equals("m3u", ignoreCase = true) })
        assertTrue(keywords.any { it.equals("m3u8", ignoreCase = true) })
    }

    @Test
    fun `learned variants reuse successful matching queries`() {
        val variants = assistant.buildLearnedVariants(
            baseQuery = "sport channels",
            presetId = "public",
            intentKeywords = listOf("sport", "football", "hockey", "m3u8"),
            learnedQueries = listOf(
                ScannerLearnedQuery(
                    query = "football hockey playlist",
                    hits = 5,
                    lastSuccessAt = 86_400_000L,
                    presetId = "public"
                )
            )
        )

        assertTrue(variants.any { it.contains("football", ignoreCase = true) })
        assertTrue(variants.any { it.contains("sport", ignoreCase = true) && it.contains("m3u8", ignoreCase = true) })
    }

    @Test
    fun `learned variants ignore unrelated low confidence queries`() {
        val variants = assistant.buildLearnedVariants(
            baseQuery = "kids cartoons",
            presetId = "public",
            intentKeywords = listOf("kids", "cartoon"),
            learnedQueries = listOf(
                ScannerLearnedQuery(
                    query = "adult movies playlist",
                    hits = 1,
                    lastSuccessAt = 86_400_000L,
                    presetId = "other"
                )
            )
        )

        assertFalse(variants.any { it.contains("adult", ignoreCase = true) })
    }

    @Test
    fun `learned variants allow high confidence preset fallback`() {
        val variants = assistant.buildLearnedVariants(
            baseQuery = "regional channels",
            presetId = "public",
            intentKeywords = emptyList(),
            learnedQueries = listOf(
                ScannerLearnedQuery(
                    query = "community tv playlist",
                    hits = 3,
                    lastSuccessAt = 86_400_000L,
                    presetId = "public"
                )
            )
        )

        assertTrue(variants.any { it.contains("community", ignoreCase = true) })
    }
}
