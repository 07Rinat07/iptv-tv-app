package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingUrlImportBatchingTest {
    @Test
    fun firstEntityBatchDoesNotMaterializeWholeChannelList() {
        var reads = 0
        val channels = object : AbstractList<Channel>() {
            override val size: Int = 10_000

            override fun get(index: Int): Channel {
                reads += 1
                return channel(
                    index = index,
                    name = "Channel $index",
                    tvgId = "id-$index",
                    streamUrl = "udp://239.10.${index / 255}.${index % 255}:${20_000 + index}"
                )
            }
        }

        val firstBatch = prepareUrlImportEntityChunks(
            channels = channels,
            playlistId = 42L,
            chunkSize = 500,
            resolveLogo = { null }
        ).first()

        assertEquals(500, firstBatch.size)
        assertEquals(42L, firstBatch.first().playlistId)
        assertEquals(0, firstBatch.first().orderIndex)
        assertEquals(499, firstBatch.last().orderIndex)
        assertTrue("first batch should not traverse all 10,000 channels", reads <= 501)
    }

    @Test
    fun entityBatchesPreserveFirstWinsDedupOrderAndLogoSemantics() {
        val logoRequests = mutableListOf<String>()
        val channels = listOf(
            channel(index = 0, name = "Alpha", tvgId = "alpha", streamUrl = "HTTP://example/a"),
            channel(index = 1, name = "Other", tvgId = "other", streamUrl = "http://EXAMPLE/a"),
            channel(index = 2, name = "Alpha", tvgId = "ALPHA", streamUrl = "http://example/b"),
            channel(index = 3, name = "Beta", tvgId = null, streamUrl = "http://example/c"),
            channel(
                index = 4,
                name = "Gamma",
                tvgId = "gamma",
                streamUrl = "http://example/d",
                logo = "https://logos.example/gamma.png"
            )
        )

        val batches = prepareUrlImportEntityChunks(
            channels = channels,
            playlistId = 77L,
            chunkSize = 2,
            resolveLogo = { channel ->
                logoRequests += channel.name
                "https://logos.example/${channel.name.lowercase()}.png"
            }
        ).toList()
        val entities = batches.flatten()

        assertEquals(listOf(2, 1), batches.map { it.size })
        assertEquals(listOf("Alpha", "Beta", "Gamma"), entities.map { it.name })
        assertEquals(listOf(0, 1, 2), entities.map { it.orderIndex })
        assertEquals(listOf(77L, 77L, 77L), entities.map { it.playlistId })
        assertEquals("https://logos.example/alpha.png", entities[0].logo)
        assertEquals("https://logos.example/beta.png", entities[1].logo)
        assertEquals("https://logos.example/gamma.png", entities[2].logo)
        assertEquals(listOf("Alpha", "Beta"), logoRequests)
    }

    private fun channel(
        index: Int,
        name: String,
        tvgId: String?,
        streamUrl: String,
        logo: String? = null
    ): Channel = Channel(
        id = index.toLong(),
        playlistId = 0L,
        tvgId = tvgId,
        name = name,
        group = "Group",
        logo = logo,
        streamUrl = streamUrl,
        health = ChannelHealth.UNKNOWN,
        orderIndex = index,
        isHidden = false
    )
}
