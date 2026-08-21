package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesPortableBackupServiceTest {
    @Test
    fun roundTripPreservesPortableIdentityAndVariantsWithoutLocalRoomIds() {
        val favorite = favorite(preferredChannelId = 101, preferredPlaylistId = 11, preferredUrl = URL_A)
        val source = FavoriteBackupSource(
            favorite = favorite,
            variants = listOf(
                variant(channelId = 101, playlistId = 11, url = URL_A, playlistName = "Primary"),
                variant(channelId = 202, playlistId = 22, url = URL_B, playlistName = "Backup")
            )
        )

        val encoded = FavoritesPortableBackupCodec.encode(listOf(source), createdAt = 1000)
        val decoded = FavoritesPortableBackupCodec.decode(encoded.content)

        assertTrue(decoded is FavoriteBackupDecodeResult.Success)
        decoded as FavoriteBackupDecodeResult.Success
        val restored = decoded.document.favorites.single()
        assertEquals(LOGICAL_KEY, restored.logicalKey)
        assertEquals(UnifiedFavoritePersistence.variantKey(URL_A), restored.preferredVariantKey)
        assertEquals(setOf(URL_A, URL_B), restored.variants.mapNotNull { it.streamUrl }.toSet())
        assertFalse(encoded.content.contains("playlistId"))
        assertFalse(encoded.content.contains("channelId"))
        assertEquals(0, encoded.redactedVariantCount)
    }

    @Test
    fun defaultExportRedactsProviderCredentialBearingStreamUrl() {
        val secretUrl = "https://provider.example/live/user-name/password-value/123.ts?token=secret-token"
        val providerVariant = variant(
            channelId = 1,
            playlistId = 2,
            url = secretUrl,
            playlistName = "Provider",
            sourceType = "XTREAM",
            catalogOrigin = "PROVIDER"
        )
        val source = FavoriteBackupSource(
            favorite = favorite(
                preferredChannelId = 1,
                preferredPlaylistId = 2,
                preferredUrl = secretUrl
            ),
            variants = listOf(providerVariant)
        )

        val encoded = FavoritesPortableBackupCodec.encode(listOf(source), createdAt = 1000)
        val decoded = FavoritesPortableBackupCodec.decode(encoded.content)

        assertEquals(1, encoded.redactedVariantCount)
        assertFalse(encoded.content.contains("user-name"))
        assertFalse(encoded.content.contains("password-value"))
        assertFalse(encoded.content.contains("secret-token"))
        assertTrue(decoded is FavoriteBackupDecodeResult.Success)
        decoded as FavoriteBackupDecodeResult.Success
        assertTrue(decoded.document.favorites.single().variants.single().isRedacted)
    }

    @Test
    fun repeatedImportIsIdempotentAndKeepsExistingPreferredSource() {
        val existingFavorite = favorite(
            preferredChannelId = 10,
            preferredPlaylistId = 1,
            preferredUrl = URL_A
        )
        val existingVariant = variant(
            channelId = 10,
            playlistId = 1,
            url = URL_A,
            playlistName = "Current"
        )
        val document = document(
            preferredUrl = URL_B,
            variants = listOf(
                backupVariant(URL_A, playlistName = "Imported A"),
                backupVariant(URL_B, playlistName = "Imported B")
            )
        )

        val first = FavoritesPortableBackupPlanner.planImport(
            document = document,
            existingFavorites = listOf(existingFavorite),
            existingVariants = listOf(existingVariant),
            liveChannels = emptyList(),
            playlists = emptyMap(),
            now = 2000
        )
        val second = FavoritesPortableBackupPlanner.planImport(
            document = document,
            existingFavorites = first.favoritesToUpsert,
            existingVariants = first.variantsToUpsert,
            liveChannels = emptyList(),
            playlists = emptyMap(),
            now = 3000
        )

        assertEquals(URL_A, first.favoritesToUpsert.single().preferredStreamUrl)
        assertEquals(URL_A, second.favoritesToUpsert.single().preferredStreamUrl)
        assertEquals(2, first.variantsToUpsert.size)
        assertEquals(2, second.variantsToUpsert.size)
        assertEquals(
            first.variantsToUpsert.map { it.logicalKey to it.variantKey }.toSet(),
            second.variantsToUpsert.map { it.logicalKey to it.variantKey }.toSet()
        )
        assertEquals(0, first.importedFavorites)
        assertEquals(1, first.mergedFavorites)
    }

    @Test
    fun importedPreferredVariantRelinksToCurrentLiveRoomIds() {
        val preferredKey = UnifiedFavoritePersistence.variantKey(URL_B)
        val document = FavoriteBackupDocument(
            formatVersion = 1,
            createdAt = 100,
            favorites = listOf(
                FavoriteBackupEntry(
                    logicalKey = LOGICAL_KEY,
                    tvgId = "news.one",
                    name = "News One",
                    groupName = "News",
                    logo = null,
                    preferredVariantKey = preferredKey,
                    addedAt = 10,
                    updatedAt = 20,
                    variants = listOf(backupVariant(URL_B, playlistName = "Remote source"))
                )
            )
        )
        val live = channel(id = 42, playlistId = 7, url = URL_B)
        val playlist = playlist(id = 7, name = "Local source")

        val plan = FavoritesPortableBackupPlanner.planImport(
            document = document,
            existingFavorites = emptyList(),
            existingVariants = emptyList(),
            liveChannels = listOf(live),
            playlists = mapOf(7L to playlist),
            now = 2000
        )

        val imported = plan.favoritesToUpsert.single()
        assertEquals(42L, imported.preferredChannelId)
        assertEquals(7L, imported.preferredPlaylistId)
        assertEquals(URL_B, imported.preferredStreamUrl)
        assertEquals(setOf(42L), plan.compatibilityFavorites.map { it.channelId }.toSet())
    }

    @Test
    fun unsupportedFutureVersionIsRejectedBeforeImportPlanning() {
        val content = """{"format":"rinat-iptv-favorites","formatVersion":99,"createdAt":1,"favorites":[]}"""

        val decoded = FavoritesPortableBackupCodec.decode(content)

        assertTrue(decoded is FavoriteBackupDecodeResult.UnsupportedVersion)
        decoded as FavoriteBackupDecodeResult.UnsupportedVersion
        assertEquals(99, decoded.version)
    }

    @Test
    fun redactedOnlyFavoriteWithoutLocalEquivalentIsSkipped() {
        val redacted = backupVariant(URL_A).copy(streamUrl = null)
        val document = FavoriteBackupDocument(
            formatVersion = 1,
            createdAt = 100,
            favorites = listOf(
                FavoriteBackupEntry(
                    logicalKey = LOGICAL_KEY,
                    tvgId = "news.one",
                    name = "News One",
                    groupName = null,
                    logo = null,
                    preferredVariantKey = redacted.variantKey,
                    addedAt = 10,
                    updatedAt = 20,
                    variants = listOf(redacted)
                )
            )
        )

        val plan = FavoritesPortableBackupPlanner.planImport(
            document = document,
            existingFavorites = emptyList(),
            existingVariants = emptyList(),
            liveChannels = emptyList(),
            playlists = emptyMap(),
            now = 2000
        )

        assertTrue(plan.favoritesToUpsert.isEmpty())
        assertEquals(1, plan.redactedVariantsIgnored)
        assertEquals(1, plan.skippedUnrestorableFavorites)
    }

    private fun document(
        preferredUrl: String,
        variants: List<FavoriteBackupVariant>
    ): FavoriteBackupDocument = FavoriteBackupDocument(
        formatVersion = 1,
        createdAt = 100,
        favorites = listOf(
            FavoriteBackupEntry(
                logicalKey = LOGICAL_KEY,
                tvgId = "news.one",
                name = "News One",
                groupName = "News",
                logo = null,
                preferredVariantKey = UnifiedFavoritePersistence.variantKey(preferredUrl),
                addedAt = 10,
                updatedAt = 20,
                variants = variants
            )
        )
    )

    private fun favorite(
        preferredChannelId: Long,
        preferredPlaylistId: Long,
        preferredUrl: String
    ): FavoriteChannelEntity = FavoriteChannelEntity(
        logicalKey = LOGICAL_KEY,
        tvgId = "news.one",
        name = "News One",
        groupName = "News",
        logo = null,
        preferredStreamUrl = preferredUrl,
        preferredPlaylistId = preferredPlaylistId,
        preferredChannelId = preferredChannelId,
        addedAt = 10,
        updatedAt = 20
    )

    private fun variant(
        channelId: Long,
        playlistId: Long,
        url: String,
        playlistName: String,
        sourceType: String = "URL",
        catalogOrigin: String = "USER_IMPORT"
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = LOGICAL_KEY,
        variantKey = UnifiedFavoritePersistence.variantKey(url),
        legacyChannelId = channelId,
        playlistId = playlistId,
        playlistName = playlistName,
        sourceType = sourceType,
        catalogOrigin = catalogOrigin,
        tvgId = "news.one",
        name = "News One",
        groupName = "News",
        logo = null,
        streamUrl = url,
        addedAt = 10,
        updatedAt = 20
    )

    private fun backupVariant(
        url: String,
        playlistName: String = "Portable source"
    ): FavoriteBackupVariant = FavoriteBackupVariant(
        variantKey = UnifiedFavoritePersistence.variantKey(url),
        playlistName = playlistName,
        sourceType = "URL",
        catalogOrigin = "USER_IMPORT",
        tvgId = "news.one",
        name = "News One",
        groupName = "News",
        logo = null,
        streamUrl = url,
        addedAt = 10,
        updatedAt = 20
    )

    private fun channel(id: Long, playlistId: Long, url: String): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = playlistId,
        tvgId = "news.one",
        name = "News One",
        groupName = "News",
        logo = null,
        streamUrl = url,
        health = ChannelHealth.AVAILABLE.name,
        orderIndex = 0,
        isHidden = false
    )

    private fun playlist(id: Long, name: String): PlaylistEntity = PlaylistEntity(
        id = id,
        name = name,
        sourceType = "URL",
        source = "https://playlist.example/list.m3u8",
        epgSourceUrl = null,
        scheduleHours = 0,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = 1,
        catalogOrigin = "USER_IMPORT"
    )

    private companion object {
        const val LOGICAL_KEY = "tvg:news.one"
        const val URL_A = "https://safe.example/a.m3u8"
        const val URL_B = "https://safe.example/b.m3u8"
    }
}
