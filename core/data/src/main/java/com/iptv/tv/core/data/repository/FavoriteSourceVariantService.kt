package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.FavoritePlaybackContext
import com.iptv.tv.core.model.FavoriteSourceVariant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles and selects durable source variants for one logical favorite.
 *
 * Reading variants intentionally persists newly discovered live equivalents so their provenance
 * survives a later source deletion. Selecting a source changes only playback preference; the
 * aggregate representative compatibility ID remains stable.
 */
@Singleton
class FavoriteSourceVariantService @Inject constructor(
    private val favoriteSnapshotDao: FavoriteSnapshotDao,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao,
    private val favoriteDao: FavoriteDao,
    private val playlistDao: PlaylistDao
) {
    suspend fun getSourceVariants(favoriteChannelId: Long): List<FavoriteSourceVariant> {
        val favorite = findFavorite(favoriteChannelId) ?: return emptyList()
        val liveChannels = liveChannels(favorite)
        val playlists = playlistsFor(liveChannels)
        val persisted = reconcileLiveVariants(
            favorite = favorite,
            liveChannels = liveChannels,
            playlists = playlists
        )
        return FavoriteSourceVariantSelection.buildSourceVariants(
            favorite = favorite,
            persistedVariants = persisted,
            liveChannels = liveChannels,
            playlists = playlists
        )
    }

    suspend fun selectPreferredSource(favoriteChannelId: Long, variantKey: String): Boolean {
        if (variantKey.isBlank()) return false
        val favorite = findFavorite(favoriteChannelId) ?: return false
        val liveChannels = liveChannels(favorite)
        val playlists = playlistsFor(liveChannels)
        val persisted = reconcileLiveVariants(
            favorite = favorite,
            liveChannels = liveChannels,
            playlists = playlists
        )
        val updated = FavoriteSourceVariantSelection.selectPreferredSource(
            favorite = favorite,
            variantKey = variantKey,
            persistedVariants = persisted,
            liveChannels = liveChannels,
            updatedAt = System.currentTimeMillis()
        ) ?: return false

        favoriteSnapshotDao.upsertFavorite(updated)
        return true
    }

    suspend fun resolvePlaybackContext(favoriteChannelId: Long): FavoritePlaybackContext? {
        val favorite = findFavorite(favoriteChannelId) ?: return null
        val liveChannels = liveChannels(favorite)
        val persisted = favoriteSnapshotDao.getVariants(favorite.logicalKey)
        return FavoriteSourceVariantSelection.resolvePlaybackContext(
            requestedChannelId = favoriteChannelId,
            favorite = favorite,
            persistedVariants = persisted,
            liveChannels = liveChannels
        )
    }

    private suspend fun findFavorite(favoriteChannelId: Long): FavoriteChannelEntity? {
        val requestedLive = favoriteChannelLookupDao.findChannelById(favoriteChannelId)
        return requestedLive
            ?.let { channel ->
                favoriteSnapshotDao.findFavorite(UnifiedFavoritePersistence.logicalKey(channel))
            }
            ?: favoriteSnapshotDao.findFavoriteByPreferredChannelId(favoriteChannelId)
    }

    private suspend fun liveChannels(favorite: FavoriteChannelEntity): List<ChannelEntity> =
        favoriteChannelLookupDao.getAllChannels()
            .filter { channel -> UnifiedFavoritePersistence.logicalKey(channel) == favorite.logicalKey }

    private suspend fun playlistsFor(liveChannels: List<ChannelEntity>): Map<Long, PlaylistEntity?> =
        liveChannels
            .map(ChannelEntity::playlistId)
            .distinct()
            .associateWith { playlistId -> playlistDao.findById(playlistId) }

    private suspend fun reconcileLiveVariants(
        favorite: FavoriteChannelEntity,
        liveChannels: List<ChannelEntity>,
        playlists: Map<Long, PlaylistEntity?>
    ): List<FavoriteChannelVariantEntity> {
        val existing = favoriteSnapshotDao.getVariants(favorite.logicalKey)
        val additions = FavoriteSourceVariantSelection.missingLiveVariants(
            favorite = favorite,
            persistedVariants = existing,
            liveChannels = liveChannels,
            playlists = playlists,
            discoveredAt = System.currentTimeMillis()
        )
        if (additions.isNotEmpty()) {
            favoriteSnapshotDao.upsertVariants(additions)
            // Keep the old FavoriteDao identity bridge alive for legacy import inheritance. The
            // logical snapshot/variant tables remain authoritative and removal still goes through
            // UnifiedFavoritesRepositoryImpl, which clears both representations.
            favoriteDao.upsertAll(
                additions.map { variant ->
                    FavoriteEntity(
                        channelId = variant.legacyChannelId,
                        addedAt = variant.addedAt
                    )
                }
            )
        }
        return (existing + additions)
            .distinctBy(FavoriteChannelVariantEntity::variantKey)
    }
}

/** Pure deterministic source-variant transformations used by repository code and unit tests. */
internal object FavoriteSourceVariantSelection {
    fun missingLiveVariants(
        favorite: FavoriteChannelEntity,
        persistedVariants: List<FavoriteChannelVariantEntity>,
        liveChannels: List<ChannelEntity>,
        playlists: Map<Long, PlaylistEntity?>,
        discoveredAt: Long
    ): List<FavoriteChannelVariantEntity> {
        val existingKeys = persistedVariants
            .asSequence()
            .filter { variant -> variant.logicalKey == favorite.logicalKey }
            .mapTo(hashSetOf(), FavoriteChannelVariantEntity::variantKey)

        return liveChannels
            .asSequence()
            .filter { channel -> UnifiedFavoritePersistence.logicalKey(channel) == favorite.logicalKey }
            .map { channel ->
                val key = UnifiedFavoritePersistence.variantKey(channel.streamUrl)
                key to channel
            }
            .filter { (key, _) -> key !in existingKeys }
            .distinctBy { (key, _) -> key }
            .map { (key, channel) ->
                val playlist = playlists[channel.playlistId]
                FavoriteChannelVariantEntity(
                    logicalKey = favorite.logicalKey,
                    variantKey = key,
                    legacyChannelId = channel.id,
                    playlistId = channel.playlistId,
                    playlistName = playlist?.name,
                    sourceType = playlist?.sourceType,
                    catalogOrigin = playlist?.catalogOrigin,
                    tvgId = channel.tvgId,
                    name = channel.name,
                    groupName = channel.groupName,
                    logo = channel.logo,
                    streamUrl = channel.streamUrl,
                    addedAt = discoveredAt,
                    updatedAt = discoveredAt
                )
            }
            .sortedBy(FavoriteChannelVariantEntity::variantKey)
            .toList()
    }

    fun buildSourceVariants(
        favorite: FavoriteChannelEntity,
        persistedVariants: List<FavoriteChannelVariantEntity>,
        liveChannels: List<ChannelEntity>,
        playlists: Map<Long, PlaylistEntity?>
    ): List<FavoriteSourceVariant> {
        val savedByKey = persistedVariants
            .asSequence()
            .filter { variant -> variant.logicalKey == favorite.logicalKey }
            .associateBy(FavoriteChannelVariantEntity::variantKey)
        val liveByKey = liveChannels
            .asSequence()
            .filter { channel -> UnifiedFavoritePersistence.logicalKey(channel) == favorite.logicalKey }
            .associateBy { channel -> UnifiedFavoritePersistence.variantKey(channel.streamUrl) }
        val preferredKey = UnifiedFavoritePersistence.variantKey(favorite.preferredStreamUrl)
        val keys = linkedSetOf<String>().apply {
            addAll(savedByKey.keys)
            addAll(liveByKey.keys)
            if (favorite.preferredStreamUrl.isNotBlank()) add(preferredKey)
        }

        return keys.mapNotNull { key ->
            val live = liveByKey[key]
            val saved = savedByKey[key]
            val isSnapshotOnly = live == null && saved == null && key == preferredKey
            if (live == null && saved == null && !isSnapshotOnly) return@mapNotNull null
            val playlist = live?.let { channel -> playlists[channel.playlistId] }
            val streamUrl = live?.streamUrl ?: saved?.streamUrl ?: favorite.preferredStreamUrl
            if (streamUrl.isBlank()) return@mapNotNull null

            FavoriteSourceVariant(
                logicalKey = favorite.logicalKey,
                variantKey = key,
                name = live?.name ?: saved?.name ?: favorite.name,
                streamUrl = streamUrl,
                playlistName = playlist?.name ?: saved?.playlistName,
                sourceType = playlist?.sourceType ?: saved?.sourceType,
                catalogOrigin = playlist?.catalogOrigin ?: saved?.catalogOrigin,
                isLive = live != null,
                health = live?.health.toChannelHealthSafe(),
                isPreferred = key == preferredKey
            )
        }.sortedWith(
            compareByDescending<FavoriteSourceVariant> { variant -> variant.isPreferred }
                .thenByDescending { variant -> variant.isLive }
                .thenBy { variant -> variant.playlistName.orEmpty() }
                .thenBy(FavoriteSourceVariant::variantKey)
        )
    }

    fun selectPreferredSource(
        favorite: FavoriteChannelEntity,
        variantKey: String,
        persistedVariants: List<FavoriteChannelVariantEntity>,
        liveChannels: List<ChannelEntity>,
        updatedAt: Long
    ): FavoriteChannelEntity? {
        if (variantKey.isBlank()) return null
        val live = liveChannels.firstOrNull { channel ->
            UnifiedFavoritePersistence.logicalKey(channel) == favorite.logicalKey &&
                UnifiedFavoritePersistence.variantKey(channel.streamUrl) == variantKey
        }
        val saved = persistedVariants.firstOrNull { variant ->
            variant.logicalKey == favorite.logicalKey && variant.variantKey == variantKey
        }
        val streamUrl = live?.streamUrl ?: saved?.streamUrl ?: return null
        val playlistId = live?.playlistId ?: saved?.playlistId ?: favorite.preferredPlaylistId

        return favorite.copy(
            preferredStreamUrl = streamUrl,
            preferredPlaylistId = playlistId,
            // Keep the compatibility representative ID stable across source switching.
            preferredChannelId = favorite.preferredChannelId,
            updatedAt = updatedAt
        )
    }

    fun resolvePlaybackContext(
        requestedChannelId: Long,
        favorite: FavoriteChannelEntity,
        persistedVariants: List<FavoriteChannelVariantEntity>,
        liveChannels: List<ChannelEntity>
    ): FavoritePlaybackContext {
        if (requestedChannelId != favorite.preferredChannelId) {
            return UnifiedFavoritePersistence.resolvePlaybackContext(
                requestedChannelId = requestedChannelId,
                favorite = favorite,
                persistedVariants = persistedVariants,
                liveChannels = liveChannels
            )
        }

        val preferredKey = UnifiedFavoritePersistence.variantKey(favorite.preferredStreamUrl)
        val preferredLive = liveChannels.firstOrNull { channel ->
            UnifiedFavoritePersistence.logicalKey(channel) == favorite.logicalKey &&
                UnifiedFavoritePersistence.variantKey(channel.streamUrl) == preferredKey
        }
        if (preferredLive != null) {
            val preferredFavorite = favorite.copy(
                preferredPlaylistId = preferredLive.playlistId,
                preferredChannelId = preferredLive.id
            )
            return UnifiedFavoritePersistence.resolvePlaybackContext(
                requestedChannelId = preferredLive.id,
                favorite = preferredFavorite,
                persistedVariants = persistedVariants,
                liveChannels = liveChannels
            )
        }

        val preferredSaved = persistedVariants.firstOrNull { variant ->
            variant.logicalKey == favorite.logicalKey && variant.variantKey == preferredKey
        }
        if (preferredSaved != null) {
            val preferredFavorite = favorite.copy(
                preferredPlaylistId = preferredSaved.playlistId,
                preferredChannelId = preferredSaved.legacyChannelId
            )
            val resolved = UnifiedFavoritePersistence.resolvePlaybackContext(
                requestedChannelId = preferredSaved.legacyChannelId,
                favorite = preferredFavorite,
                persistedVariants = persistedVariants,
                liveChannels = emptyList()
            )
            return resolved.copy(
                availableVariantCount = countVariants(
                    favorite = favorite,
                    persistedVariants = persistedVariants,
                    liveChannels = liveChannels
                )
            )
        }

        return UnifiedFavoritePersistence.resolvePlaybackContext(
            requestedChannelId = requestedChannelId,
            favorite = favorite,
            persistedVariants = persistedVariants,
            liveChannels = liveChannels
        )
    }

    private fun countVariants(
        favorite: FavoriteChannelEntity,
        persistedVariants: List<FavoriteChannelVariantEntity>,
        liveChannels: List<ChannelEntity>
    ): Int = buildSet {
        liveChannels
            .filter { channel -> UnifiedFavoritePersistence.logicalKey(channel) == favorite.logicalKey }
            .forEach { channel -> add(UnifiedFavoritePersistence.variantKey(channel.streamUrl)) }
        persistedVariants
            .filter { variant -> variant.logicalKey == favorite.logicalKey }
            .forEach { variant -> add(variant.variantKey) }
        if (favorite.preferredStreamUrl.isNotBlank()) {
            add(UnifiedFavoritePersistence.variantKey(favorite.preferredStreamUrl))
        }
    }.size.coerceAtLeast(1)

    private fun String?.toChannelHealthSafe(): ChannelHealth =
        this?.let { value -> runCatching { ChannelHealth.valueOf(value) }.getOrNull() }
            ?: ChannelHealth.UNKNOWN
}
