package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadyCatalogRefreshMetadataTest {

    @Test
    fun refreshedReadyEpgSourceClearsRetiredPublisherHeader() {
        assertNull(refreshedReadyEpgSource(emptyList()))
    }

    @Test
    fun refreshedReadyEpgSourceUsesFreshTrimmedPublisherHeader() {
        assertEquals(
            "https://epg.example/current.xml.gz",
            refreshedReadyEpgSource(listOf("  https://epg.example/current.xml.gz  "))
        )
    }

    @Test
    fun deduplicateReadyChannelsPreservesDistinctStreamsWithSameMetadata() {
        val first = channel("https://stream.example/primary.m3u8")
        val backup = channel("https://stream.example/backup.m3u8")

        val result = deduplicateReadyChannels(listOf(first, backup))

        assertEquals(listOf(first, backup), result)
    }

    @Test
    fun deduplicateReadyChannelsRemovesOnlyExactTrimmedUrlDuplicates() {
        val first = channel("https://stream.example/live.m3u8")
        val duplicate = channel("  https://stream.example/live.m3u8  ")
        val caseDistinct = channel("https://stream.example/LIVE.m3u8")

        val result = deduplicateReadyChannels(listOf(first, duplicate, caseDistinct))

        assertEquals(listOf(first, caseDistinct), result)
    }

    private fun channel(streamUrl: String): Channel = Channel(
        id = 0L,
        playlistId = 1L,
        tvgId = "news",
        name = "News",
        group = "TV",
        logo = null,
        streamUrl = streamUrl,
        health = ChannelHealth.UNKNOWN,
        orderIndex = 0,
        isHidden = false
    )
}
