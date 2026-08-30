package com.iptv.tv.feature.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pMedia3BoundaryCallbackWiringRegressionTest {
    @Test
    fun `p2p sessions own a generation scoped boundary telemetry tracker`() {
        val surface = stableMedia3Surface()

        assertOrdered(
            surface,
            "val p2pBoundaryTelemetryTracker = remember(",
            "session.sessionId",
            "session.playbackStartedAtMillis",
            "session.isP2pPlayback",
            "if (session.isP2pPlayback)",
            "P2pPlayerBoundaryTelemetryTracker(",
            "sessionId = session.sessionId",
            "playbackStartedAtMillis = session.playbackStartedAtMillis"
        )
    }

    @Test
    fun `media3 analytics callbacks publish load lifecycle without owning recovery`() {
        val surface = stableMedia3Surface()

        assertOrdered(
            surface,
            "override fun onLoadStarted(",
            "tracker.onLoadStarted(",
            "?.let(::emitP2pBoundaryTelemetry)",
            "override fun onLoadCompleted(",
            "tracker.onLoadCompleted(",
            "?.let(::emitP2pBoundaryTelemetry)",
            "override fun onLoadError(",
            "tracker.onLoadError(",
            ").let(::emitP2pBoundaryTelemetry)",
            "if (!wasCanceled)",
            "tracker.onLoadRetry(",
            ").let(::emitP2pBoundaryTelemetry)"
        )

        val loadError = functionBody(surface, "override fun onLoadError(")
        assertFalse(
            "Media3 load telemetry must not restart the P2P generation itself",
            loadError.contains("player.prepare()")
        )
        assertFalse(
            "Media3 load telemetry must not resolve or prepare a new P2P runtime itself",
            loadError.contains("resolveTorrentStream(")
        )
    }

    @Test
    fun `player callbacks publish buffering ready audio video and terminal milestones`() {
        val surface = stableMedia3Surface()

        assertOrdered(
            surface,
            "override fun onAudioPositionAdvancing(",
            "?.onFirstAudio(System.currentTimeMillis())",
            "?.let(::emitP2pBoundaryTelemetry)",
            "override fun onPlaybackStateChanged(playbackState: Int)",
            "Player.STATE_READY ->",
            "?.onReady(now)",
            "?.let(::emitP2pBoundaryTelemetry)",
            "if (!readyReported)",
            "onReady()",
            "Player.STATE_BUFFERING ->",
            "?.onBuffering(now)",
            "?.let(::emitP2pBoundaryTelemetry)",
            "override fun onRenderedFirstFrame()",
            "?.onFirstVideoFrame(System.currentTimeMillis())",
            "?.let(::emitP2pBoundaryTelemetry)",
            "onDispose {",
            "?.onTerminal(System.currentTimeMillis())",
            "?.let(::emitP2pBoundaryTelemetry)"
        )
    }

    @Test
    fun `p2p media source keeps zero media3 retry budget`() {
        val surface = stableMedia3Surface()

        assertOrdered(
            surface,
            "if (session.isP2pPlayback)",
            "setLoadErrorHandlingPolicy(",
            "DefaultLoadErrorHandlingPolicy(",
            "StableP2pMedia3RecoveryPolicy.MIN_LOADABLE_RETRY_COUNT"
        )
    }

    private fun stableMedia3Surface(): String = functionBody(
        source = stablePlayerVideoSurfaceSource(),
        signature = "private fun StableMedia3VideoSurface("
    )

    private fun stablePlayerVideoSurfaceSource(): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = cursor ?: return@repeat
            listOf(
                File(
                    directory,
                    "src/main/java/com/iptv/tv/feature/player/StablePlayerVideoSurface.kt"
                ),
                File(
                    directory,
                    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerVideoSurface.kt"
                )
            ).firstOrNull(File::isFile)?.let { return it.readText() }
            cursor = directory.parentFile
        }
        error("StablePlayerVideoSurface.kt was not found from Gradle test working directory")
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureStart = source.indexOf(signature)
        require(signatureStart >= 0) { "Missing production function: $signature" }
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "Missing production function body: $signature" }

        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("Unbalanced production function body: $signature")
    }

    private fun assertOrdered(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = source.indexOf(token, startIndex = previous + 1)
            assertTrue("Missing or out-of-order Media3 boundary token: $token", index > previous)
            previous = index
        }
    }
}
