package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelIdentityRow
import com.iptv.tv.core.model.ChannelStableIdentity
import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteChannelIdentityReconciliationTest {
    @Test
    fun collectsOnlyRowsMatchingFavoriteLogicalKeys() {
        val rows = listOf(
            FavoriteChannelIdentityRow(
                id = 10,
                tvgId = " natgeo ",
                name = "National Geographic HD",
                streamUrl = "http://one.example/live"
            ),
            FavoriteChannelIdentityRow(
                id = 11,
                tvgId = null,
                name = "Viasat History FHD",
                streamUrl = "http://two.example/live"
            ),
            FavoriteChannelIdentityRow(
                id = 12,
                tvgId = null,
                name = "Unrelated Channel",
                streamUrl = "http://three.example/live"
            )
        )
        val keys = setOf(
            ChannelStableIdentity.key("natgeo", "ignored", "http://ignored"),
            ChannelStableIdentity.key(null, "Viasat History", "http://ignored")
        )
        val destination = linkedSetOf<Long>()

        FavoriteChannelIdentityReconciliation.collectMatchingIds(
            rows = rows,
            logicalKeys = keys,
            destination = destination
        )

        assertEquals(linkedSetOf(10L, 11L), destination)
    }

    @Test
    fun emptyFavoriteSetDoesNotRetainAnyChannelIds() {
        val destination = linkedSetOf<Long>()
        FavoriteChannelIdentityReconciliation.collectMatchingIds(
            rows = listOf(
                FavoriteChannelIdentityRow(
                    id = 1,
                    tvgId = "one",
                    name = "One",
                    streamUrl = "http://example.org/one"
                )
            ),
            logicalKeys = emptySet(),
            destination = destination
        )

        assertEquals(emptySet<Long>(), destination)
    }

    @Test
    fun repeatedPagesKeepStableInsertionOrderWithoutDuplicates() {
        val key = ChannelStableIdentity.key(null, "Discovery HD", "http://ignored")
        val destination = linkedSetOf<Long>()

        FavoriteChannelIdentityReconciliation.collectMatchingIds(
            rows = listOf(
                FavoriteChannelIdentityRow(20, null, "Discovery HD", "http://a.example/live"),
                FavoriteChannelIdentityRow(21, null, "Other", "http://b.example/live")
            ),
            logicalKeys = setOf(key),
            destination = destination
        )
        FavoriteChannelIdentityReconciliation.collectMatchingIds(
            rows = listOf(
                FavoriteChannelIdentityRow(20, null, "Discovery HD", "http://a.example/live"),
                FavoriteChannelIdentityRow(22, null, "Discovery FHD", "http://c.example/live")
            ),
            logicalKeys = setOf(key),
            destination = destination
        )

        assertEquals(linkedSetOf(20L, 22L), destination)
    }
}
