package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.model.ChannelCatchUpMetadata
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedFavoritesCatchUpPropagationTest {
    @Test
    fun liveFavoriteRepresentationPreservesCatchUpCapability() {
        val live = channel(
            id = 71,
            catchUp = ChannelCatchUpMetadata(
                mode = "append",
                days = 7,
                sourceTemplate = "?utc=${'$'}{start}",
                daysDeclared = true
            )
        )

        val represented = UnifiedFavoritePersistence.representFavorites(
            favorites = listOf(favorite(preferredChannelId = 71)),
            liveChannels = listOf(live)
        ).single()

        assertEquals("append", represented.catchUp?.mode)
        assertEquals(7, represented.catchUp?.days)
        assertEquals("?utc=${'$'}{start}", represented.catchUp?.sourceTemplate)
        assertTrue(represented.catchUp?.daysDeclared == true)
    }

    @Test
    fun liveFavoritePlaybackContextPreservesCatchUpCapability() {
        val live = channel(
            id = 72,
            catchUp = ChannelCatchUpMetadata(
                mode = "shift",
                days = 3,
                sourceTemplate = null,
                daysDeclared = true
            )
        )

        val result = UnifiedFavoritePersistence.resolvePlaybackContext(
            requestedChannelId = 72,
            favorite = favorite(preferredChannelId = 72),
            persistedVariants = emptyList(),
            liveChannels = listOf(live)
        )

        assertTrue(result.isLiveVariant)
        assertEquals("shift", result.channel.catchUp?.mode)
        assertEquals(3, result.channel.catchUp?.days)
        assertTrue(result.channel.catchUp?.daysDeclared == true)
    }

    private fun favorite(preferredChannelId: Long) = FavoriteChannelEntity(
        logicalKey = LOGICAL_KEY,
        tvgId = "news.one",
        name = "News One",
        groupName = "News",
        logo = null,
        preferredStreamUrl = "https://live.example/news",
        preferredPlaylistId = 7,
        preferredChannelId = preferredChannelId,
        addedAt = 1,
        updatedAt = 2
    )

    private fun channel(
        id: Long,
        catchUp: ChannelCatchUpMetadata
    ) = ChannelEntity(
        id = id,
        playlistId = 7,
        tvgId = "news.one",
        name = "News One",
        groupName = "News",
        logo = null,
        streamUrl = "https://live.example/news",
        health = ChannelHealth.AVAILABLE.name,
        orderIndex = 0,
        isHidden = false,
        catchUpMode = catchUp.mode,
        catchUpDays = catchUp.days,
        catchUpSourceTemplate = catchUp.sourceTemplate,
        catchUpDaysDeclared = catchUp.daysDeclared
    )

    private companion object {
        const val LOGICAL_KEY = "tvg:news.one"
    }
}
