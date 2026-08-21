package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalChannelVariantIndexTest {
    @Test
    fun sameLogicalChannelAcrossSourcesKeepsBothProvenanceVariants() {
        val groups = CanonicalChannelVariantIndex.build(
            listOf(
                snapshot(
                    playlistId = 1L,
                    source = "https://provider-a.test/list.m3u",
                    origin = CatalogOriginKind.PROVIDER,
                    channelId = 101L,
                    tvgId = "world-news",
                    url = "https://provider-a.test/live/news"
                ),
                snapshot(
                    playlistId = 2L,
                    source = "https://provider-b.test/list.m3u",
                    origin = CatalogOriginKind.USER_IMPORT,
                    channelId = 202L,
                    tvgId = "WORLD-NEWS",
                    url = "https://provider-b.test/live/news"
                )
            )
        )

        assertEquals(1, groups.size)
        val logical = groups.single()
        assertEquals("tvg:world-news", logical.logicalKey)
        assertEquals(setOf(101L, 202L), logical.variants.map { it.legacyChannelId }.toSet())
        assertEquals(2, logical.variants.map { it.sourceNodeId }.distinct().size)
        assertEquals(
            setOf(CatalogOriginKind.PROVIDER, CatalogOriginKind.USER_IMPORT),
            logical.variants.map { it.provenance.origin }.toSet()
        )
        assertNotEquals(logical.variants[0].channelNodeId, logical.variants[1].channelNodeId)
    }

    @Test
    fun unrelatedLogicalChannelsRemainSeparateAggregateEntries() {
        val groups = CanonicalChannelVariantIndex.build(
            listOf(
                snapshot(
                    playlistId = 1L,
                    source = "https://example.test/a.m3u",
                    channelId = 10L,
                    tvgId = "news",
                    name = "News",
                    url = "https://example.test/news"
                ),
                snapshot(
                    playlistId = 2L,
                    source = "https://example.test/b.m3u",
                    channelId = 20L,
                    tvgId = "sport",
                    name = "Sport",
                    url = "https://example.test/sport"
                )
            )
        )

        assertEquals(listOf("tvg:news", "tvg:sport"), groups.map { it.logicalKey })
        assertTrue(groups.all { it.variants.size == 1 })
    }

    @Test
    fun duplicateSnapshotDoesNotDuplicateConcreteVariant() {
        val same = snapshot(
            playlistId = 7L,
            source = "https://example.test/list.m3u",
            channelId = 70L,
            tvgId = "news",
            url = "https://example.test/news"
        )

        val groups = CanonicalChannelVariantIndex.build(listOf(same, same))

        assertEquals(1, groups.size)
        assertEquals(1, groups.single().variants.size)
        assertEquals(70L, groups.single().variants.single().legacyChannelId)
    }

    @Test
    fun logicalIdentityDoesNotReplaceParentScopedCatalogIdentity() {
        val first = snapshot(
            playlistId = 1L,
            source = "https://one.test/list.m3u",
            channelId = 11L,
            tvgId = "same",
            url = "https://one.test/live"
        )
        val second = snapshot(
            playlistId = 2L,
            source = "https://two.test/list.m3u",
            channelId = 22L,
            tvgId = "same",
            url = "https://two.test/live"
        )

        val logical = CanonicalChannelVariantIndex.build(listOf(first, second)).single()

        assertEquals("tvg:same", logical.logicalKey)
        assertEquals(2, logical.variants.size)
        assertNotEquals(logical.variants[0].sourceNodeId, logical.variants[1].sourceNodeId)
        assertNotEquals(logical.variants[0].playlistNodeId, logical.variants[1].playlistNodeId)
        assertNotEquals(logical.variants[0].channelNodeId, logical.variants[1].channelNodeId)
    }

    private fun snapshot(
        playlistId: Long,
        source: String,
        channelId: Long,
        tvgId: String?,
        url: String,
        origin: CatalogOriginKind = CatalogOriginKind.USER_IMPORT,
        name: String = "News"
    ) = CanonicalPlaylistSnapshot(
        playlist = Playlist(
            id = playlistId,
            name = "Playlist $playlistId",
            sourceType = PlaylistSourceType.URL,
            source = source,
            scheduleHours = 12,
            lastSyncedAt = null,
            channelCount = 1,
            isCustom = false,
            catalogOrigin = origin
        ),
        channels = listOf(
            Channel(
                id = channelId,
                playlistId = playlistId,
                tvgId = tvgId,
                name = name,
                group = "General",
                logo = null,
                streamUrl = url,
                health = ChannelHealth.UNKNOWN,
                orderIndex = 0,
                isHidden = false
            )
        )
    )
}
