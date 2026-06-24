package com.iptv.tv.feature.editor

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

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

    @Test
    fun normalizeSharedRulesCatalogUrl_acceptsOnlyHttpUrls() {
        assertEquals(
            "https://example.com/rules.txt",
            normalizeSharedRulesCatalogUrl("  https://example.com/rules.txt  ")
        )
        assertEquals("http://example.com/rules.txt", normalizeSharedRulesCatalogUrl("http://example.com/rules.txt"))
        assertNull(normalizeSharedRulesCatalogUrl(""))
        assertNull(normalizeSharedRulesCatalogUrl("ftp://example.com/rules.txt"))
        assertNull(normalizeSharedRulesCatalogUrl("file:///tmp/rules.txt"))
    }

    @Test
    fun parseSharedMetadataRulesCatalogInfo_readsVersionedHeaders() {
        val info = parseSharedMetadataRulesCatalogInfo(
            """
                # catalog: Community IPTV metadata
                # version: 2026.06
                # updated: 2026-06-20
                # description: Base language and country hints
                # pack: News
                match=news; category=News
            """.trimIndent()
        )

        assertEquals("Community IPTV metadata", info.title)
        assertEquals("2026.06", info.version)
        assertEquals("2026-06-20", info.updatedAt)
        assertEquals("Base language and country hints", info.description)
    }

    @Test
    fun buildSharedRulesCatalogLoadedMessage_includesVersionedInfoWhenAvailable() {
        val message = buildSharedRulesCatalogLoadedMessage(
            prefix = "Shared rules catalog загружен по URL",
            packsCount = 3,
            info = SharedMetadataRulesCatalogInfo(
                title = "Community IPTV metadata",
                version = "2026.06",
                updatedAt = "2026-06-20"
            )
        )

        assertEquals(
            "Shared rules catalog загружен по URL: 3 (Community IPTV metadata, v2026.06, updated 2026-06-20)",
            message
        )
    }

    @Test
    fun buildSharedRulesCatalogCacheLabel_includesPackCountVersionAndChecksum() {
        val label = buildSharedRulesCatalogCacheLabel(
            packsCount = 2,
            info = SharedMetadataRulesCatalogInfo(
                title = "Community IPTV metadata",
                version = "2026.06",
                updatedAt = "2026-06-24",
                checksumStatus = SharedRulesCatalogChecksumStatus.VALID
            )
        )

        assertEquals(
            "Cached shared catalog: 2 packs · Community IPTV metadata · v2026.06 · updated 2026-06-24 · sha256 ok",
            label
        )
    }

    @Test
    fun parseSharedMetadataRulesCatalogInfo_marksValidSha256Checksum() {
        val catalogWithoutChecksum = """
            # catalog: Community IPTV metadata
            # version: 2026.06
            # pack: News
            match=news; category=News
        """.trimIndent()
        val checksum = catalogWithoutChecksum.sha256HexForTest()
        val catalog = """
            # sha256: $checksum
            $catalogWithoutChecksum
        """.trimIndent()

        val info = parseSharedMetadataRulesCatalogInfo(catalog)

        assertEquals(checksum, info.checksumSha256)
        assertEquals(checksum, info.computedSha256)
        assertEquals(SharedRulesCatalogChecksumStatus.VALID, info.checksumStatus)
        assertEquals(catalogWithoutChecksum, canonicalSharedRulesCatalogForChecksum(catalog))
    }

    @Test
    fun parseSharedMetadataRulesCatalogInfo_marksInvalidSha256Checksum() {
        val catalog = """
            # sha256: 0000000000000000000000000000000000000000000000000000000000000000
            # pack: News
            match=news; category=News
        """.trimIndent()

        val info = parseSharedMetadataRulesCatalogInfo(catalog)

        assertEquals(SharedRulesCatalogChecksumStatus.INVALID, info.checksumStatus)
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

    private fun String.sha256HexForTest(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
