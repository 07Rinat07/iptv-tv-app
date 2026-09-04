package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.FavoritePlaybackContext
import com.iptv.tv.core.model.FavoriteSourceVariant
import com.iptv.tv.core.model.FavoritesPortableExport
import com.iptv.tv.core.model.FavoritesPortableImportResult
import com.iptv.tv.core.model.FavoritesShareableExport
import com.iptv.tv.core.model.FavoritesShareableExportFormat
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest

/**
 * Feature-facing Favorites facade.
 *
 * Large catalog invalidations are intentionally represented by a cheap scalar query. The facade
 * resolves only live rows matching durable favorite logical keys; it must never subscribe to a
 * full `Flow<List<ChannelEntity>>` over the whole catalog.
 */
@Singleton
class FavoritesRepositoryFacade @Inject constructor(
    private val delegate: UnifiedFavoritesRepositoryImpl,
    private val favoriteSnapshotDao: FavoriteSnapshotDao,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao,
    private val favoriteLiveChannelResolver: FavoriteLiveChannelResolver,
    private val portableBackupService: FavoritesPortableBackupService,
    private val shareableExportService: FavoritesShareableExportService,
    private val sourceVariantService: FavoriteSourceVariantService
) : FavoritesRepository by delegate {
    override fun observeFavorites(): Flow<List<Channel>> {
        return combine(
            delegate.observeFavorites(),
            favoriteSnapshotDao.observeFavoriteChannels(),
            favoriteSnapshotDao.observeFavoriteVariants(),
            favoriteChannelLookupDao.observeChannelTableInvalidation()
        ) { _, favorites, persistedVariants, _ ->
            val liveChannels = favoriteLiveChannelResolver.findMatchingChannels(
                favorites.mapTo(hashSetOf(), FavoriteChannelEntity::logicalKey)
            )
            resolvedFavoriteRepresentatives(
                favorites = favorites,
                persistedVariants = persistedVariants,
                liveChannels = liveChannels
            )
        }
    }

    override fun observeFavoriteCount(): Flow<Int> = delegate.observeFavoriteCount()

    override fun observeFavoriteChannelIds(): Flow<Set<Long>> {
        return delegate.observeFavoriteChannelIds().mapLatest { liveIds ->
            favoriteRepresentativeIds(
                liveIds = liveIds,
                representativeIds = favoriteSnapshotDao.getFavoriteChannels()
                    .map(FavoriteChannelEntity::preferredChannelId)
            )
        }
    }

    override suspend fun resolvePlaybackContext(favoriteChannelId: Long): FavoritePlaybackContext? {
        sourceVariantService.resolvePlaybackContext(favoriteChannelId)?.let { return it }
        // Normal v10 calls resolve above without duplicate database work. Only a first operation
        // after v9->v10 may need the delegate to materialize legacy seeds before one retry.
        delegate.resolvePlaybackContext(favoriteChannelId) ?: return null
        return sourceVariantService.resolvePlaybackContext(favoriteChannelId)
    }

    override suspend fun getSourceVariants(favoriteChannelId: Long): List<FavoriteSourceVariant> {
        val variants = sourceVariantService.getSourceVariants(favoriteChannelId)
        if (variants.isNotEmpty()) return variants
        // Preserve lazy migration for the edge case where source APIs are the first Favorites call.
        delegate.resolvePlaybackContext(favoriteChannelId) ?: return emptyList()
        return sourceVariantService.getSourceVariants(favoriteChannelId)
    }

    override suspend fun selectPreferredSource(favoriteChannelId: Long, variantKey: String): Boolean {
        if (sourceVariantService.selectPreferredSource(favoriteChannelId, variantKey)) return true
        // False can mean the durable snapshot has not yet been materialized from legacy seeds.
        // Retrying after the migration barrier is harmless for a genuinely unknown variant.
        delegate.resolvePlaybackContext(favoriteChannelId) ?: return false
        return sourceVariantService.selectPreferredSource(favoriteChannelId, variantKey)
    }

    override suspend fun exportShareableFavorites(
        format: FavoritesShareableExportFormat
    ): FavoritesShareableExport = shareableExportService.export(format)

    override suspend fun exportPortableBackup(): FavoritesPortableExport =
        portableBackupService.exportPortableBackup()

    override suspend fun importPortableBackup(content: String): FavoritesPortableImportResult =
        portableBackupService.importPortableBackup(content)
}

internal fun resolvedFavoriteRepresentatives(
    favorites: List<FavoriteChannelEntity>,
    persistedVariants: List<FavoriteChannelVariantEntity>,
    liveChannels: List<ChannelEntity>
): List<Channel> {
    val variantsByLogicalKey = persistedVariants.groupBy(FavoriteChannelVariantEntity::logicalKey)
    val liveByLogicalKey = liveChannels.groupBy(UnifiedFavoritePersistence::logicalKey)

    return favorites.map { favorite ->
        val playback = FavoriteSourceVariantSelection.resolvePlaybackContext(
            requestedChannelId = favorite.preferredChannelId,
            favorite = favorite,
            persistedVariants = variantsByLogicalKey[favorite.logicalKey].orEmpty(),
            liveChannels = liveByLogicalKey[favorite.logicalKey].orEmpty()
        )
        playback.channel.copy(id = favorite.preferredChannelId)
    }
}

internal fun favoriteRepresentativeIds(
    liveIds: Set<Long>,
    representativeIds: Iterable<Long>
): Set<Long> = buildSet {
    addAll(liveIds)
    addAll(representativeIds)
}
