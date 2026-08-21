package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.model.FavoritesShareableExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesShareableExportServiceTest {
    @Test
    fun m3u8FallsBackFromCredentialPreferredToSafeVariant() {
        val providerUrl = "https://provider.example/live/user123/pass123/42.ts"
        val safeUrl = "https://public.example/news.m3u8"
        val favorite = favorite(preferredUrl = providerUrl)
        val provider = variant(
            url = providerUrl,
            sourceType = "XTREAM",
            catalogOrigin = "PROVIDER",
            updatedAt = 200
        )
        val safe = variant(
            url = safeUrl,
            sourceType = "URL",
            catalogOrigin = "USER_IMPORT",
            updatedAt = 100
        )

        val result = FavoritesShareableExportCodec.encode(
            format = FavoritesShareableExportFormat.M3U8,
            sources = listOf(FavoriteShareableSource(favorite, listOf(provider, safe)))
        )

        assertEquals(1, result.favoriteCount)
        assertEquals(1, result.safeUrlCount)
        assertEquals(1, result.redactedVariantCount)
        assertTrue(result.content.contains(safeUrl))
        assertFalse(result.content.contains("user123"))
        assertFalse(result.content.contains("pass123"))
        assertFalse(result.content.contains(providerUrl))
    }

    @Test
    fun txtKeepsFavoriteMetadataButNeverWritesCredentialBearingUrlOrLocalIds() {
        val secretUrl = "https://example.test/live.m3u8?token=super-secret"
        val favorite = favorite(preferredUrl = secretUrl)
        val secret = variant(
            url = secretUrl,
            sourceType = "URL",
            catalogOrigin = "USER_IMPORT",
            updatedAt = 100
        )

        val result = FavoritesShareableExportCodec.encode(
            format = FavoritesShareableExportFormat.TXT,
            sources = listOf(FavoriteShareableSource(favorite, listOf(secret)))
        )

        assertEquals(0, result.safeUrlCount)
        assertEquals(1, result.redactedVariantCount)
        assertTrue(result.content.contains("News One"))
        assertTrue(result.content.contains("url=[REDACTED]"))
        assertFalse(result.content.contains("super-secret"))
        assertFalse(result.content.contains("playlistId"))
        assertFalse(result.content.contains("channelId"))
    }

    @Test
    fun preferredSafeVariantWinsEvenWhenAlternateWasUpdatedLater() {
        val preferredUrl = "https://safe.example/preferred.m3u8"
        val alternateUrl = "https://safe.example/newer.m3u8"
        val favorite = favorite(preferredUrl = preferredUrl)
        val preferred = variant(url = preferredUrl, updatedAt = 10)
        val alternate = variant(url = alternateUrl, updatedAt = 999)

        val result = FavoritesShareableExportCodec.encode(
            format = FavoritesShareableExportFormat.M3U8,
            sources = listOf(FavoriteShareableSource(favorite, listOf(alternate, preferred)))
        )

        assertTrue(result.content.contains(preferredUrl))
        assertFalse(result.content.contains(alternateUrl))
    }

    @Test
    fun m3u8MetadataIsCollapsedToSingleLines() {
        val safeUrl = "https://safe.example/live.m3u8"
        val favorite = favorite(
            preferredUrl = safeUrl,
            name = "News\nInjected",
            group = "World\r\nTV"
        )
        val variant = variant(url = safeUrl, updatedAt = 100)

        val result = FavoritesShareableExportCodec.encode(
            format = FavoritesShareableExportFormat.M3U8,
            sources = listOf(FavoriteShareableSource(favorite, listOf(variant)))
        )

        assertTrue(result.content.contains("News Injected"))
        assertTrue(result.content.contains("World  TV"))
        assertFalse(result.content.contains("News\nInjected"))
        assertFalse(result.content.contains("World\r\nTV"))
    }

    private fun favorite(
        preferredUrl: String,
        name: String = "News One",
        group: String? = "News"
    ): FavoriteChannelEntity = FavoriteChannelEntity(
        logicalKey = LOGICAL_KEY,
        tvgId = "news.one",
        name = name,
        groupName = group,
        logo = "https://img.example/news.png",
        preferredStreamUrl = preferredUrl,
        preferredPlaylistId = 77,
        preferredChannelId = 88,
        addedAt = 1,
        updatedAt = 2
    )

    private fun variant(
        url: String,
        sourceType: String? = "URL",
        catalogOrigin: String? = "USER_IMPORT",
        updatedAt: Long
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = LOGICAL_KEY,
        variantKey = UnifiedFavoritePersistence.variantKey(url),
        legacyChannelId = 88,
        playlistId = 77,
        playlistName = "Source",
        sourceType = sourceType,
        catalogOrigin = catalogOrigin,
        tvgId = "news.one",
        name = "News One",
        groupName = "News",
        logo = "https://img.example/news.png",
        streamUrl = url,
        addedAt = 1,
        updatedAt = updatedAt
    )

    private companion object {
        const val LOGICAL_KEY = "tvg:news.one"
    }
}
