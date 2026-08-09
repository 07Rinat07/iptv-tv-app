package com.iptv.tv.core.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceRuntimeDiagnosticsTest {
    @Test
    fun sanitizeAceHttpUrl_removesQueryFragmentAndIdentifierPathSegments() {
        val contentId = "11223344556677889900aabbccddeeff00112233"
        val raw = "http://127.0.0.1:6878/ace/stream/$contentId?access_token=secret#fragment"

        val sanitized = sanitizeAceHttpUrl(raw)

        assertEquals("http://127.0.0.1:6878/ace/stream/redacted", sanitized)
        assertTrue(!sanitized.orEmpty().contains(contentId))
        assertTrue(!sanitized.orEmpty().contains("access_token"))
        assertTrue(!sanitized.orEmpty().contains("fragment"))
    }

    @Test
    fun summary_containsOnlySafeRuntimeMetadata() {
        val diagnostics = AceRuntimeDiagnostics(
            stage = AceRuntimeStage.PLAYBACK_READY,
            descriptorKind = "content_id",
            provider = "external_engine",
            route = "installed_engine",
            enginePackage = "org.acestream.live",
            endpoint = "http://127.0.0.1:62062",
            transportType = "bt",
            isLive = true,
            playbackTarget = "http://127.0.0.1:62062/ace/stream/redacted",
            fallbackReason = null,
            failureCode = null
        )

        val summary = diagnostics.toSummary()

        assertTrue(summary.contains("stage=playback_ready"))
        assertTrue(summary.contains("package=org.acestream.live"))
        assertTrue(summary.contains("live=true"))
        assertTrue(summary.contains("target=http://127.0.0.1:62062/ace/stream/redacted"))
    }
}
