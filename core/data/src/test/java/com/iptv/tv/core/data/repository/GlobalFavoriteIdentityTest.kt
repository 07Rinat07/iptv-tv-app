package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GlobalFavoriteIdentityTest {
    @Test
    fun tvgIdKeepsSameChannelFavoriteAcrossPlaylists() {
        val first = GlobalFavoriteIdentity.key("  Discovery.HD ", "Discovery HD", "https://a/1")
        val second = GlobalFavoriteIdentity.key("discovery.hd", "Discovery Channel", "https://b/2")

        assertEquals(first, second)
    }

    @Test
    fun normalizedNameIsFallbackWhenTvgIdMissing() {
        val first = GlobalFavoriteIdentity.key(null, "Матч! ТВ HD", "https://a/1")
        val second = GlobalFavoriteIdentity.key("", "Матч ТВ", "https://b/2")

        assertEquals(first, second)
    }

    @Test
    fun differentNamedChannelsRemainIndependent() {
        val first = GlobalFavoriteIdentity.key(null, "Канал 1", "https://a/1")
        val second = GlobalFavoriteIdentity.key(null, "Канал 2", "https://a/1")

        assertNotEquals(first, second)
    }
}
