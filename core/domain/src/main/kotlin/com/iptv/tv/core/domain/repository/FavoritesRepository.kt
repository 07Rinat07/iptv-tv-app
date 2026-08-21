package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.FavoritePlaybackContext
import com.iptv.tv.core.model.FavoritesPortableExport
import com.iptv.tv.core.model.FavoritesPortableImportResult
import com.iptv.tv.core.model.FavoritesPortableImportStatus
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository {
    /** One representative per logical favorite channel for the dedicated Favorites screen. */
    fun observeFavorites(): Flow<List<Channel>>

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
