package com.iptv.tv.feature.favorites

import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.FavoriteSourceVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSourceVariantUiTest {
    @Test
    fun titlePrefersPlaylistNameThenSourceType() {
        assertEquals("Provider A", favoriteSourceVariantTitle(variant(playlistName = "Provider A", sourceType = "XTREAM")))
        assertEquals("M3U", favoriteSourceVariantTitle(variant(playlistName = null, sourceType = "M3U")))
        assertEquals("Источник канала", favoriteSourceVariantTitle(variant(playlistName = null, sourceType = null)))
    }

    @Test
    fun summaryNeverLeaksCredentialBearingStreamUrl() {
        val streamUrl = "https://user:secret@example.test/live.m3u8?token=very-secret"
        val variant = variant(
            playlistName = "Provider A",
            sourceType = "XTREAM",
            streamUrl = streamUrl,
            catalogOrigin = "PROVIDER",
            isLive = true,
            health = ChannelHealth.AVAILABLE
        )

        val summary = favoriteSourceVariantSummary(variant)

        assertFalse(summary.contains(streamUrl))
        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("token="))
        assertTrue(summary.contains("XTREAM"))
        assertTrue(summary.contains("PROVIDER"))
        assertTrue(summary.contains("доступен сейчас"))
    }

    @Test
    fun savedVariantUsesExplicitOfflineSnapshotLanguage() {
        val summary = favoriteSourceVariantSummary(
            variant(
                playlistName = "Backup source",
                sourceType = "URL",
                isLive = false,
                health = ChannelHealth.UNKNOWN
            )
        )

        assertTrue(summary.contains("сохраненный вариант"))
        assertTrue(summary.contains("статус неизвестен"))
    }

    @Test
    fun selectionFeedbackContainsSafeSourceTitleOnly() {
        val variant = variant(
            playlistName = "Provider A",
            streamUrl = "https://user:pass@example.test/live?token=abc"
        )

        val message = favoriteSourceSelectionMessage(variant)

        assertEquals("Источник выбран: Provider A", message)
        assertFalse(message.contains("example.test"))
        assertFalse(message.contains("token"))
    }

    private fun variant(
        playlistName: String? = "Provider A",
        sourceType: String? = "URL",
        streamUrl: String = "https://example.test/live",
        catalogOrigin: String? = "USER_IMPORT",
        isLive: Boolean = true,
        health: ChannelHealth = ChannelHealth.AVAILABLE
    ): FavoriteSourceVariant = FavoriteSourceVariant(
        logicalKey = "tvg:news.one",
        variantKey = "variant-key",
        name = "News One",
        streamUrl = streamUrl,
        playlistName = playlistName,
        sourceType = sourceType,
        catalogOrigin = catalogOrigin,
        isLive = isLive,
        health = health,
        isPreferred = false
    )
}
