package com.iptv.tv.core.p2p

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class AceContentCatalogResolverTest {
    private val resolver = AceContentCatalogResolver(OkHttpClient())
    private val contentId = "2123456789abcdef0123456789abcdef01234567"

    @Test
    fun catalogSignatureMatchesKnownProtocolVectors() {
        assertEquals(
            "ee6c8422795ced4cd5c1fb80f10276801ab636c6",
            resolver.signature(contentId, 4_245_094_384_320_117_676L)
        )
        assertEquals(
            "08c4ba548457e883f8711d3f3cb846c20058d912",
            resolver.signature(contentId, 2_142_790_664_659_308_268L)
        )
        assertEquals(
            "c7c3cd3c7268be1a48144653cdbd7912991cb337",
            resolver.signature(contentId, 3_472_462_845_767_567_311L)
        )
    }
}
