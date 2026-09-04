package com.iptv.tv.core.data.repository

import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.model.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualFavoriteCountTest {
    @Test
    fun scalarCountDoesNotSubscribeFavoriteChannelObjects() = runTest {
        var favoriteSubscriptions = 0
        val repository = object : FavoritesRepository {
            override fun observeFavorites(): Flow<List<Channel>> = flow {
                favoriteSubscriptions += 1
                error("Full Favorites must not be collected for playlist count")
            }

            override fun observeFavoriteCount(): Flow<Int> = flowOf(12_345)

            override fun observeFavoriteChannelIds(): Flow<Set<Long>> = flowOf(emptySet())

            override suspend fun toggleFavorite(channelId: Long) = Unit
        }

        val count = observeVirtualFavoriteCount(repository).first()

        assertEquals(12_345, count)
        assertEquals(0, favoriteSubscriptions)
    }
}
