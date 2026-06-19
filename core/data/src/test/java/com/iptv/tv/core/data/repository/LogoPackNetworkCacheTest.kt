package com.iptv.tv.core.data.repository

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogoPackNetworkCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun fetch_downloadsNetworkPackAndStoresCache() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"logos":[{"name":"News","logo":"https://cdn.example/news.png"}]}"""))
            server.start()
            val cache = cache()

            val result = cache.fetch(server.url("/logos.json").toString())

            assertFalse(result.fromCache)
            assertEquals("network", result.detail)
            assertTrue(result.json.contains("News"))
            assertEquals(1, temp.root.walkTopDown().count { it.isFile && it.extension == "json" })
        }
    }

    @Test
    fun fetch_fallsBackToCacheWhenNetworkFails() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"logos":[{"name":"Cached","logo":"https://cdn.example/cached.png"}]}"""))
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.start()
            val cache = cache()
            val url = server.url("/logos.json").toString()

            val network = cache.fetch(url)
            val cached = cache.fetch(url)

            assertFalse(network.fromCache)
            assertTrue(cached.fromCache)
            assertTrue(cached.json.contains("Cached"))
            assertTrue(cached.detail.contains("HTTP 500"))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun fetch_rejectsNonHttpUrls() {
        cache().fetch("file:///tmp/logos.json")
    }

    private fun cache(): LogoPackNetworkCache {
        return LogoPackNetworkCache(
            cacheRootProvider = { temp.root },
            okHttpClient = OkHttpClient()
        )
    }
}
