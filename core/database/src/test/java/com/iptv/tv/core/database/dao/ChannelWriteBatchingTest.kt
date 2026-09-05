package com.iptv.tv.core.database.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelWriteBatchingTest {
    @Test
    fun batchesLargeSelectionWithinSqliteBindLimitAndDeduplicatesIds() {
        val ids = (1L..901L).toList() + listOf(1L, 900L, 901L)

        val batches = ChannelWriteBatching.batches(ids)

        assertEquals(2, batches.size)
        assertEquals((1L..900L).toList(), batches[0])
        assertEquals(listOf(901L), batches[1])
        assertTrue(batches.all { it.size <= ChannelWriteBatching.MAX_IDS_PER_QUERY })
    }

    @Test
    fun emptySelectionProducesNoWriteBatches() {
        assertTrue(ChannelWriteBatching.batches(emptyList()).isEmpty())
    }
}
