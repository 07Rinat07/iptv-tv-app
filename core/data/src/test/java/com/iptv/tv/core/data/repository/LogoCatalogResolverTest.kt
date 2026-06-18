package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogoCatalogResolverTest {

    @Test
    fun resolve_prefersTvgIdOverName() {
        val resolver = LogoCatalogResolver(
            listOf(
                LogoCatalogResolver.LogoCatalogEntry(
                    logoUrl = "https://example.com/name.svg",
                    names = setOf(LogoCatalogResolver.normalizeName("News"))
                ),
                LogoCatalogResolver.LogoCatalogEntry(
                    logoUrl = "https://example.com/tvg.svg",
                    tvgIds = setOf("news.us")
                )
            )
        )

        val result = resolver.resolve(
            name = "News",
            tvgId = "news.us",
            playlistSource = null
        )

        assertEquals("https://example.com/tvg.svg", result?.url)
        assertEquals("logo-pack:tvg-id", result?.source)
    }

    @Test
    fun resolve_matchesNormalizedName() {
        val resolver = LogoCatalogResolver(
            listOf(
                LogoCatalogResolver.LogoCatalogEntry(
                    logoUrl = "https://example.com/cafe.svg",
                    names = setOf(LogoCatalogResolver.normalizeName("Cafe TV"))
                )
            )
        )

        val result = resolver.resolve(
            name = "Cafe-TV HD",
            tvgId = null,
            playlistSource = null
        )

        assertEquals("https://example.com/cafe.svg", result?.url)
        assertEquals("logo-pack:name", result?.source)
    }

    @Test
    fun resolve_matchesSourceHostWhenChannelKeysAreUnknown() {
        val resolver = LogoCatalogResolver(
            listOf(
                LogoCatalogResolver.LogoCatalogEntry(
                    logoUrl = "https://example.com/provider.svg",
                    sourceHosts = setOf("provider.example")
                )
            )
        )

        val result = resolver.resolve(
            name = "Unknown",
            tvgId = null,
            playlistSource = "https://provider.example/live/list.m3u"
        )

        assertEquals("https://example.com/provider.svg", result?.url)
        assertEquals("logo-pack:source", result?.source)
    }

    @Test
    fun resolve_returnsNullWhenNoCatalogEntryMatches() {
        val resolver = LogoCatalogResolver(emptyList())

        assertNull(
            resolver.resolve(
                name = "Unknown Channel",
                tvgId = "unknown.tv",
                playlistSource = "https://unknown.example/list.m3u"
            )
        )
    }
}
