package com.iptv.tv.core.data.repository

import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.model.Channel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Feature-facing Favorites facade.
 *
 * Logical storage keeps every live equivalent ID favorite. A standalone favorite may have no live
 * channel row, so its durable representative ID is added as a UI compatibility ID. This keeps the
 * Player star/filter state correct after the original playlist has been deleted.
 */
@Singleton
class FavoritesRepositoryFacade @Inject constructor(
    private val delegate: UnifiedFavoritesRepositoryImpl
) : FavoritesRepository by delegate {
    override fun observeFavoriteChannelIds(): Flow<Set<Long>> {
        return combine(
            delegate.observeFavoriteChannelIds(),
            delegate.observeFavorites()
        ) { liveIds, representatives ->
            favoriteRepresentativeIds(liveIds, representatives)
        }
    }
}

internal fun favoriteRepresentativeIds(
    liveIds: Set<Long>,
    representatives: List<Channel>
): Set<Long> = buildSet {
    addAll(liveIds)
    representatives.forEach { channel -> add(channel.id) }
}
