package com.iptv.tv.feature.editor

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataRuleBuilderTest {
    @Test
    fun buildMetadataRuleLine_sanitizesValuesAndKeepsMetadataFields() {
        val rule = buildMetadataRuleLine(
            matcherType = METADATA_RULE_MATCH_GROUP,
            matcherValue = "Sports; HD",
            country = "US=CA",
            language = "en",
            category = "Sports"
        )

        assertEquals("group=Sports HD; country=US CA; language=en; category=Sports", rule)
    }

    @Test
    fun buildMetadataRuleLine_requiresMatcherAndMetadata() {
        assertNull(
            buildMetadataRuleLine(
                matcherType = METADATA_RULE_MATCH_NAME,
                matcherValue = "",
                country = "US",
                language = "",
                category = ""
            )
        )
        assertNull(
            buildMetadataRuleLine(
                matcherType = METADATA_RULE_MATCH_NAME,
                matcherValue = "News",
                country = "",
                language = "",
                category = ""
            )
        )
    }

    @Test
    fun metadataRulePreviewCount_matchesSelectedChannelField() {
        val channels = listOf(
            channel(id = 1, name = "Kazakh News", group = "News", tvgId = "kz.news", streamUrl = "https://a.example/live"),
            channel(id = 2, name = "Movie One", group = "Movies", tvgId = "movie.one", streamUrl = "https://b.example/live"),
            channel(id = 3, name = "Sports KZ", group = "Sports", tvgId = "sport.kz", streamUrl = "https://sport.example/live")
        )

        assertEquals(1, metadataRulePreviewCount(channels, METADATA_RULE_MATCH_NAME, "kazakh"))
        assertEquals(1, metadataRulePreviewCount(channels, METADATA_RULE_MATCH_GROUP, "movie"))
        assertEquals(2, metadataRulePreviewCount(channels, METADATA_RULE_MATCH_ANY, "kz"))
        assertEquals(1, metadataRulePreviewCount(channels, METADATA_RULE_MATCH_SOURCE, "sport.example"))
    }

    @Test
    fun appendMetadataRulesText_keepsExistingRulesAndAddsPackAfterNewLine() {
        val result = appendMetadataRulesText(
            existingRules = "match=news; category=News",
            newRules = "match=sport; category=Sports"
        )

        assertEquals("match=news; category=News\nmatch=sport; category=Sports", result)
    }

    @Test
    fun sharedMetadataRulePacks_haveStableIdsAndRules() {
        assertEquals(
            listOf("basic-categories", "cis-language-country", "source-domains"),
            sharedMetadataRulePacks.map { it.id }
        )
        sharedMetadataRulePacks.forEach { pack ->
            val activeRules = pack.rules
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .toList()

            assertEquals(true, activeRules.isNotEmpty())
            assertEquals(true, activeRules.all { it.contains("=") && it.contains(";") })
        }
    }

    @Test
    fun parseSharedMetadataRulePacksCatalog_readsNamedPacks() {
        val packs = parseSharedMetadataRulePacksCatalog(
            """
                # pack: Sports
                match=sport; category=Sports
                match=спорт; category=Sports
                # pack: Countries
                source=.kz; country=KZ
                source=.ru; country=RU; language=ru
            """.trimIndent()
        )

        assertEquals(listOf("Sports", "Countries"), packs.map { it.title })
        assertEquals("external-1-sports", packs[0].id)
        assertEquals("match=sport; category=Sports\nmatch=спорт; category=Sports", packs[0].rules)
    }

    @Test
    fun parseSharedMetadataRulePacksCatalog_usesFallbackTitleAndSkipsInvalidPacks() {
        val packs = parseSharedMetadataRulePacksCatalog(
            """
                # only a comment
                match=news; category=News
                # pack: Broken
                just text
            """.trimIndent()
        )

        assertEquals(1, packs.size)
        assertEquals("Imported pack 1", packs[0].title)
        assertEquals("external-1-imported-pack-1", packs[0].id)
    }

    private fun channel(
        id: Long,
        name: String,
        group: String?,
        tvgId: String?,
        streamUrl: String
    ): Channel {
        return Channel(
            id = id,
            playlistId = 10,
            tvgId = tvgId,
            name = name,
            group = group,
            logo = null,
            streamUrl = streamUrl,
            health = ChannelHealth.UNKNOWN,
            orderIndex = id.toInt(),
            isHidden = false
        )
    }
}
