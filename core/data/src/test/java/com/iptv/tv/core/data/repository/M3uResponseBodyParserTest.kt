package com.iptv.tv.core.data.repository

import com.iptv.tv.core.parser.M3uParser
import com.iptv.tv.core.parser.ParseResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class M3uResponseBodyParserTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesLargeHttpBodyThroughReaderPath() {
        val channelCount = 8_100
        val payload = buildString {
            appendLine("#EXTM3U url-tvg=\"https://epg.example/guide.xml\"")
            repeat(channelCount) { index ->
                appendLine("#EXTINF:-1 tvg-id=\"id$index\" group-title=\"Group${index % 20}\",Channel $index")
                appendLine("https://example.com/live/$index.m3u8")
            }
        }
        server.enqueue(MockResponse().setBody(payload))

        val response = OkHttpClient().newCall(
            Request.Builder().url(server.url("/large.m3u")).build()
        ).execute()
        val result = response.use {
            parseM3uResponseBody(
                playlistId = 0L,
                parser = M3uParser(),
                body = it.body
            )
        }

        assertTrue(result is ParseResult.Valid)
        val valid = result as ParseResult.Valid
        assertEquals(channelCount, valid.channels.size)
        assertEquals(listOf("https://epg.example/guide.xml"), valid.epgUrls)
    }

    @Test
    fun emptyHttpBodyPreservesEmptyPlaylistDiagnostic() {
        server.enqueue(MockResponse().setBody(""))

        val response = OkHttpClient().newCall(
            Request.Builder().url(server.url("/empty.m3u")).build()
        ).execute()
        val result = response.use {
            parseM3uResponseBody(
                playlistId = 0L,
                parser = M3uParser(),
                body = it.body
            )
        }

        assertTrue(result is ParseResult.Invalid)
        assertEquals("Playlist content is empty", (result as ParseResult.Invalid).reason)
    }
}
