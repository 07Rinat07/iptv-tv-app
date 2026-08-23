package com.iptv.tv.core.data.mapper

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.model.ChannelCatchUpMetadata
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelCatchUpEntityMapperTest {
    @Test
    fun roundTripPreservesInvalidDeclaredRangeState() {
        val entity = channelEntity().copy(
            catchUpMode = "default",
            catchUpDays = null,
            catchUpSourceTemplate = "https://archive.test/replay?start={utc}",
            catchUpDaysDeclared = true
        )

        val model = entity.toModel()
        val persisted = model.toEntity()

        assertEquals(
            ChannelCatchUpMetadata(
                mode = "default",
                days = null,
                sourceTemplate = "https://archive.test/replay?start={utc}",
                daysDeclared = true
            ),
            model.catchUp
        )
        assertEquals("default", persisted.catchUpMode)
        assertNull(persisted.catchUpDays)
        assertTrue(persisted.catchUpDaysDeclared)
    }

    @Test
    fun liveOnlyEntityRemainsWithoutCatchUpCapability() {
        val model = channelEntity().toModel()

        assertNull(model.catchUp)
        assertFalse(model.toEntity().catchUpDaysDeclared)
    }

    private fun channelEntity() = ChannelEntity(
        id = 1L,
        playlistId = 2L,
        tvgId = "news",
        name = "News",
        groupName = "News",
        logo = null,
        streamUrl = "https://example.test/live.m3u8",
        health = ChannelHealth.UNKNOWN.name,
        orderIndex = 0,
        isHidden = false
    )
}
