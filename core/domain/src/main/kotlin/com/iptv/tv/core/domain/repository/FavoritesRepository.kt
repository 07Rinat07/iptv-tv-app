package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.FavoritePlaybackContext
import com.iptv.tv.core.model.FavoriteSourceVariant
import com.iptv.tv.core.model.FavoritesPortableExport
import com.iptv.tv.core.model.FavoritesPortableImportResult
import com.iptv.tv.core.model.FavoritesPortableImportStatus
import com.iptv.tv.core.model.FavoritesShareableExport
import com.iptv.tv.core.model.FavoritesShareableExportFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface FavoritesRepository {
    /** One representative per logical favorite channel for the dedicated Favorites screen. */
    fun observeFavorites(): Flow<List<Channel>>

    /**
     * Number of logical favorites, with one count entry per durable favorite identity.
     *
     * The default preserves compatibility with alternate implementations. Production persistence
     * overrides this with a scalar database path so playlist metadata does not materialize Channel
     * objects or reconcile the complete live catalog.
     */
    fun observeFavoriteCount(): Flow<Int> = observeFavorites().map { favorites -> favorites.size }

    /** All concrete channel row IDs marked by the global favorite identity. */
    fun observeFavoriteChannelIds(): Flow<Set<Long>>

    suspend fun toggleFavorite(channelId: Long)

    /**
     * Resolve the best playable source for a favorite represented by [favoriteChannelId].
     *
     * The default keeps compatibility with test doubles and alternate implementations while the
     * unified persistence implementation provides durable live/persisted variant selection.
     */
    suspend fun resolvePlaybackContext(favoriteChannelId: Long): FavoritePlaybackContext? = null

    /**
     * Return the reconciled source variants for one logical favorite.
     *
     * Implementations may persist newly discovered live equivalents so source provenance survives
     * later playlist deletion. [FavoriteSourceVariant.variantKey] is the selector used by
     * [selectPreferredSource].
     */
    suspend fun getSourceVariants(favoriteChannelId: Long): List<FavoriteSourceVariant> = emptyList()

    /**
     * Select the preferred playback source without changing the aggregate favorite identity.
     *
     * Returns false when the favorite or variant cannot be resolved. Implementations must preserve
     * all other variants and must not delete source playlist membership.
     */
    suspend fun selectPreferredSource(favoriteChannelId: Long, variantKey: String): Boolean = false

    /**
     * Build a standard TXT/M3U8 export using the same default credential policy as portable backup.
     */
    suspend fun exportShareableFavorites(
        format: FavoritesShareableExportFormat
    ): FavoritesShareableExport? = null

    /**
     * Build the default shareable Rinat IPTV Favorites backup.
     *
     * Implementations must not expose provider-account credentials or credential-bearing stream
     * URLs in this default export. A future explicitly private/secure backup mode is a separate
     * contract.
     */
    suspend fun exportPortableBackup(): FavoritesPortableExport? = null

    /** Validate and merge a versioned portable Favorites backup without replacing current data. */
    suspend fun importPortableBackup(content: String): FavoritesPortableImportResult =
        FavoritesPortableImportResult(
            status = FavoritesPortableImportStatus.INVALID_FORMAT,
            message = "Portable Favorites backup is not supported by this repository"
        )
}
