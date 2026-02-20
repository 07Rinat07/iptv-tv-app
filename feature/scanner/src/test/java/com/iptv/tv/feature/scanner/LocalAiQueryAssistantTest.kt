package com.iptv.tv.feature.scanner

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
    fun `assistant always keeps playlist core terms`() {
        val keywords = assistant.inferIntentKeywords("sport", emptyList())

        assertTrue(keywords.any { it.equals("iptv", ignoreCase = true) })
        assertTrue(keywords.any { it.equals("m3u", ignoreCase = true) })
        assertTrue(keywords.any { it.equals("m3u8", ignoreCase = true) })
    }
}

