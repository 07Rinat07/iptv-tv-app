package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.model.Channel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Feature-facing Favorites facade.
 *
 * The durable storage implementation owns logical identity and source selection. This facade turns
 * the selected playback source back into one stable aggregate row per logical favorite: the row ID
 * stays the persisted preferred compatibility ID while playlist/source/URL come from the best
 * current live or persisted variant selected by [UnifiedFavoritePersistence].
 */
@Singleton
class FavoritesRepositoryFacade @Inject constructor(
    private val delegate: UnifiedFavoritesRepositoryImpl,
    private val favoriteSnapshotDao: FavoriteSnapshotDao,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao
) : FavoritesRepository by delegate {
    override fun observeFavorites(): Flow<List<Channel>> {
        return combine(
            delegate.observeFavorites(),
            favoriteSnapshotDao.observeFavoriteChannels(),
            favoriteSnapshotDao.observeFavoriteVariants(),
            favoriteChannelLookupDao.observeAllChannels()
        ) { _, favorites, persistedVariants, liveChannels ->
            resolvedFavoriteRepresentatives(
                favorites = favorites,
                persistedVariants = persistedVariants,
                liveChannels = liveChannels
            )
        }
    }

    override fun observeFavoriteChannelIds(): Flow<Set<Long>> {
        return combine(
            delegate.observeFavoriteChannelIds(),
            observeFavorites()
        ) { liveIds, representatives ->
            favoriteRepresentativeIds(liveIds, representatives)
        }
    }
}

internal fun resolvedFavoriteRepresentatives(
    favorites: List<FavoriteChannelEntity>,
    persistedVariants: List<FavoriteChannelVariantEntity>,
    liveChannels: List<ChannelEntity>
): List<Channel> {
    val variantsByLogicalKey = persistedVariants.groupBy(FavoriteChannelVariantEntity::logicalKey)
    val liveByLogicalKey = liveChannels.groupBy(UnifiedFavoritePersistence::logicalKey)

    return favorites.map { favorite ->
        val playback = UnifiedFavoritePersistence.resolvePlaybackContext(
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
    representatives: List<Channel>
): Set<Long> = buildSet {
    addAll(liveIds)
    representatives.forEach { channel -> add(channel.id) }
}
