package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.FavoritesShareableExport
import com.iptv.tv.core.model.FavoritesShareableExportFormat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds user-shareable Favorites exports without exposing credential-bearing source URLs.
 *
 * The portable-backup service is invoked first as the existing durable-snapshot migration gate.
 * Export itself is then produced from durable snapshots plus current live equivalents so a safe
 * live source can replace an unsafe preferred source without changing Favorites persistence.
 */
@Singleton
class FavoritesShareableExportService @Inject constructor(
    private val portableBackupService: FavoritesPortableBackupService,
    private val favoriteSnapshotDao: FavoriteSnapshotDao,
    private val favoriteLiveChannelResolver: FavoriteLiveChannelResolver,
    private val playlistDao: PlaylistDao
) {
    suspend fun export(format: FavoritesShareableExportFormat): FavoritesShareableExport {
        // Reuse the already-tested portable backend to consolidate any pending 9->10 legacy seeds.
        // Its encoded content is intentionally ignored here; this service builds format-specific
        // output from the resulting durable snapshot and current live equivalents.
        portableBackupService.exportPortableBackup()

        val favorites = favoriteSnapshotDao.getFavoriteChannels()
        val liveChannels = favoriteLiveChannelResolver.findMatchingChannels(
            favorites.asSequence()
                .map(FavoriteChannelEntity::logicalKey)
                .toSet()
        )
        val playlists = playlistDao.findPlaylistMapByIds(
            liveChannels.map(ChannelEntity::playlistId)
        )
        val liveByLogicalKey = liveChannels.groupBy(UnifiedFavoritePersistence::logicalKey)
        val now = System.currentTimeMillis()

        val sources = favorites.map { favorite ->
            val persisted = favoriteSnapshotDao.getVariants(favorite.logicalKey)
            val live = liveByLogicalKey[favorite.logicalKey].orEmpty().map { channel ->
                channel.toFavoriteVariant(
                    logicalKey = favorite.logicalKey,
                    playlist = playlists[channel.playlistId],
                    favoriteAddedAt = favorite.addedAt,
                    updatedAt = now
                )
            }
            val preferredKey = UnifiedFavoritePersistence.variantKey(favorite.preferredStreamUrl)
            val combined = (persisted + live)
                .filter { variant -> variant.logicalKey == favorite.logicalKey }
                .distinctBy(FavoriteChannelVariantEntity::variantKey)
            val variants = if (combined.any { it.variantKey == preferredKey }) {
                combined
            } else {
                combined + favorite.toSyntheticPreferredVariant(preferredKey)
            }
            FavoriteShareableSource(favorite = favorite, variants = variants)
        }

        return FavoritesShareableExportCodec.encode(format = format, sources = sources)
    }

    private fun ChannelEntity.toFavoriteVariant(
        logicalKey: String,
        playlist: PlaylistEntity?,
        favoriteAddedAt: Long,
        updatedAt: Long
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = logicalKey,
        variantKey = UnifiedFavoritePersistence.variantKey(streamUrl),
        legacyChannelId = id,
        playlistId = playlistId,
        playlistName = playlist?.name,
        sourceType = playlist?.sourceType,
        catalogOrigin = playlist?.catalogOrigin,
        tvgId = tvgId,
        name = name,
        groupName = groupName,
        logo = logo,
        streamUrl = streamUrl,
        addedAt = favoriteAddedAt,
        updatedAt = updatedAt
    )

    private fun FavoriteChannelEntity.toSyntheticPreferredVariant(
        preferredKey: String
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = logicalKey,
        variantKey = preferredKey,
        legacyChannelId = preferredChannelId,
        playlistId = preferredPlaylistId,
        playlistName = null,
        sourceType = null,
        catalogOrigin = null,
        tvgId = tvgId,
        name = name,
        groupName = groupName,
        logo = logo,
        streamUrl = preferredStreamUrl,
        addedAt = addedAt,
        updatedAt = updatedAt
    )
}

internal data class FavoriteShareableSource(
    val favorite: FavoriteChannelEntity,
    val variants: List<FavoriteChannelVariantEntity>
)

private data class SelectedShareableFavorite(
    val favorite: FavoriteChannelEntity,
    val safeVariant: FavoriteChannelVariantEntity?
)

internal object FavoritesShareableExportCodec {
    fun encode(
        format: FavoritesShareableExportFormat,
        sources: List<FavoriteShareableSource>
    ): FavoritesShareableExport {
        var redactedVariantCount = 0
        val selected = sources
            .sortedBy { source -> source.favorite.logicalKey }
            .map { source ->
                val favorite = source.favorite
                val preferredKey = UnifiedFavoritePersistence.variantKey(favorite.preferredStreamUrl)
                val variants = source.variants
                    .filter { variant -> variant.logicalKey == favorite.logicalKey }
                    .distinctBy(FavoriteChannelVariantEntity::variantKey)
                val classified = variants.map { variant ->
                    variant to FavoriteBackupCredentialPolicy.mustRedact(variant)
                }
                redactedVariantCount += classified.count { (_, redacted) -> redacted }

                val preferredSafe = classified.firstOrNull { (variant, redacted) ->
                    variant.variantKey == preferredKey && !redacted
                }?.first
                val fallbackSafe = classified
                    .asSequence()
                    .filter { (_, redacted) -> !redacted }
                    .map { (variant, _) -> variant }
                    .sortedWith(
                        compareByDescending<FavoriteChannelVariantEntity> { it.updatedAt }
                            .thenBy { it.variantKey }
                    )
                    .firstOrNull()

                SelectedShareableFavorite(
                    favorite = favorite,
                    safeVariant = preferredSafe ?: fallbackSafe
                )
            }

        val safeUrlCount = selected.count { item -> item.safeVariant != null }
        val content = when (format) {
            FavoritesShareableExportFormat.TXT -> buildTxt(
                selected = selected,
                redactedVariantCount = redactedVariantCount
            )
            FavoritesShareableExportFormat.M3U8 -> buildM3u8(selected)
        }

        return FavoritesShareableExport(
            content = content,
            favoriteCount = selected.size,
            safeUrlCount = safeUrlCount,
            redactedVariantCount = redactedVariantCount
        )
    }

    private fun buildTxt(
        selected: List<SelectedShareableFavorite>,
        redactedVariantCount: Int
    ): String = buildString {
        appendLine("myscanerIPTV | Избранные каналы")
        appendLine("Каналов: ${selected.size}")
        appendLine("Безопасных URL: ${selected.count { it.safeVariant != null }}")
        appendLine("Скрыто credential-bearing вариантов: $redactedVariantCount")
        appendLine()

        selected.forEachIndexed { index, item ->
            val favorite = item.favorite
            val variant = item.safeVariant
            appendLine("${index + 1}. ${favorite.name.safeLine()}")
            favorite.groupName.safeOptionalLine()?.let { appendLine("   group=$it") }
            favorite.tvgId.safeOptionalLine()?.let { appendLine("   tvg-id=$it") }
            favorite.logo.safeOptionalLine()?.let { appendLine("   logo=$it") }
            variant?.sourceType.safeOptionalLine()?.let { appendLine("   source-type=$it") }
            variant?.catalogOrigin.safeOptionalLine()?.let { appendLine("   catalog-origin=$it") }
            appendLine("   url=${variant?.streamUrl?.safeLine() ?: "[REDACTED]"}")
            appendLine()
        }
    }

    private fun buildM3u8(selected: List<SelectedShareableFavorite>): String = buildString {
        appendLine("#EXTM3U")
        selected.forEach { item ->
            val variant = item.safeVariant ?: return@forEach
            val favorite = item.favorite
            append("#EXTINF:-1")
            appendM3uAttribute("tvg-id", favorite.tvgId.orEmpty())
            appendM3uAttribute("tvg-name", favorite.name)
            appendM3uAttribute("tvg-logo", favorite.logo.orEmpty())
            appendM3uAttribute("group-title", favorite.groupName.orEmpty())
            append(',')
            appendLine(favorite.name.safeLine())
            appendLine(variant.streamUrl.safeLine())
        }
    }

    private fun StringBuilder.appendM3uAttribute(name: String, value: String): StringBuilder {
        val cleaned = value.safeLine()
        if (cleaned.isBlank()) return this
        append(' ')
        append(name)
        append("=\"")
        append(cleaned.replace("\"", "'"))
        append('"')
        return this
    }

    private fun String?.safeOptionalLine(): String? = this
        ?.safeLine()
        ?.takeIf(String::isNotBlank)

    private fun String.safeLine(): String = replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
}
