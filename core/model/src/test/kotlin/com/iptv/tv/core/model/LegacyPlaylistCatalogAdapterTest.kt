package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyPlaylistCatalogAdapterTest {
    @Test
    fun playlistRenameKeepsCanonicalIdsStable() {
        val channels = listOf(channel(id = 10L, name = "News", tvgId = "news", group = "General"))
        val before = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(name = "Old name", source = "https://example.test/live/list.m3u"),
            channels = channels
        )
        val after = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(name = "New visible name", source = "https://example.test/live/list.m3u"),
            channels = channels
        )

        assertEquals(before.sourceNodeId, after.sourceNodeId)
        assertEquals(before.playlistNodeId, after.playlistNodeId)
        assertEquals(before.channelNodeIdByChannelId[10L], after.channelNodeIdByChannelId[10L])
    }

    @Test
    fun secretQueryRotationDoesNotLeakOrChangeSourceIdentity() {
        val channels = listOf(channel(id = 1L, name = "News", tvgId = "news"))
        val first = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(
                source = "https://example.test/list.m3u?user=alice&token=secret-one&action=live"
            ),
            channels = channels
        )
        val second = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(
                source = "https://example.test/list.m3u?token=secret-two&action=live&user=alice"
            ),
            channels = channels
        )

        assertEquals(first.sourceNodeId, second.sourceNodeId)
        assertEquals(first.playlistNodeId, second.playlistNodeId)
        val sourceKey = first.nodes.first { it.id == first.sourceNodeId }.provenance.sourceKey
        assertFalse(sourceKey.contains("secret-one"))
        assertFalse(sourceKey.contains("alice"))
        assertTrue(sourceKey.startsWith("legacy:"))
    }

    @Test
    fun inlineImportsUseChannelFingerprintInsteadOfDisplayName() {
        val newsChannels = listOf(channel(id = 1L, name = "News", tvgId = "news"))
        val sportChannels = listOf(channel(id = 2L, name = "Sport", tvgId = "sport"))

        val news = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(name = "Anything", sourceType = PlaylistSourceType.TEXT, source = "inline"),
            channels = newsChannels
        )
        val renamedNews = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(name = "Renamed", sourceType = PlaylistSourceType.TEXT, source = "inline"),
            channels = newsChannels
        )
        val sport = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(name = "Anything", sourceType = PlaylistSourceType.TEXT, source = "inline"),
            channels = sportChannels
        )

        assertEquals(news.sourceNodeId, renamedNews.sourceNodeId)
        assertEquals(news.playlistNodeId, renamedNews.playlistNodeId)
        assertNotEquals(news.playlistNodeId, sport.playlistNodeId)
    }

    @Test
    fun duplicateLogicalChannelsCollapseToOneCanonicalNodeButKeepVariants() {
        val tree = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(source = "https://example.test/list.m3u"),
            channels = listOf(
                channel(id = 10L, name = "News HD", tvgId = "NEWS", group = "General", url = "https://a.test/news"),
                channel(id = 20L, name = "News", tvgId = "news", group = "General", url = "https://b.test/news")
            )
        )

        val firstNode = tree.channelNodeIdByChannelId.getValue(10L)
        val secondNode = tree.channelNodeIdByChannelId.getValue(20L)
        assertEquals(firstNode, secondNode)
        assertEquals(listOf(10L, 20L), tree.channelVariantIdsByNodeId.getValue(firstNode))
        assertEquals(1, tree.nodes.count { it.kind == CatalogNodeKind.CHANNEL })
    }

    @Test
    fun sameLogicalChannelInDifferentGroupsRemainsParentScoped() {
        val playlist = playlist(source = "https://example.test/list.m3u")
        val news = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist,
            channels = listOf(channel(id = 1L, name = "Channel", tvgId = "same", group = "News"))
        )
        val sport = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist,
            channels = listOf(channel(id = 1L, name = "Channel", tvgId = "same", group = "Sport"))
        )

        assertNotEquals(news.channelNodeIdByChannelId.getValue(1L), sport.channelNodeIdByChannelId.getValue(1L))
    }

    @Test
    fun negativeLegacyOrderNeverProducesNegativeCanonicalOrder() {
        val tree = LegacyPlaylistCatalogAdapter.build(
            playlist = playlist(source = "https://example.test/list.m3u"),
            channels = listOf(channel(id = 1L, name = "News", tvgId = "news", group = "General", order = -10))
        )

        assertTrue(tree.nodes.all { it.order >= 0 })
    }

    private fun playlist(
        name: String = "Playlist",
        sourceType: PlaylistSourceType = PlaylistSourceType.URL,
        source: String,
        origin: CatalogOriginKind = CatalogOriginKind.USER_IMPORT
    ) = Playlist(
        id = 100L,
        name = name,
        sourceType = sourceType,
        source = source,
        scheduleHours = 12,
        lastSyncedAt = null,
        channelCount = 1,
        isCustom = false,
        catalogOrigin = origin
    )

    private fun channel(
        id: Long,
        name: String,
        tvgId: String? = null,
        group: String? = null,
        url: String = "https://example.test/live/$id",
        order: Int = id.toInt()
    ) = Channel(
        id = id,
        playlistId = 100L,
        tvgId = tvgId,
        name = name,
        group = group,
        logo = null,
        streamUrl = url,
        health = ChannelHealth.UNKNOWN,
        orderIndex = order,
        isHidden = false
    )
}
