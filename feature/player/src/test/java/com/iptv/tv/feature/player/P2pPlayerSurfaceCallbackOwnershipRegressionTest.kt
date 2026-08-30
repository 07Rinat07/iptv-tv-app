package com.iptv.tv.feature.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPlayerSurfaceCallbackOwnershipRegressionTest {
    @Test
    fun `render helpers bind callbacks to the concrete session id`() {
        val shell = stablePlayerShellSource()

        assertTrue(
            "dashboard and fullscreen render paths must both bind readiness to the concrete session",
            occurrences(shell, "onReady = { onReady(session.sessionId) }") >= 2
        )
        assertTrue(
            "dashboard and fullscreen render paths must both bind errors to the concrete session",
            occurrences(shell, "onError = { onError(session.sessionId, it) }") >= 2
        )
        assertTrue(
            "both render paths must preserve P2P boundary telemetry forwarding",
            occurrences(shell, "onP2pBoundaryTelemetry = onP2pBoundaryTelemetry") >= 2
        )
    }

    @Test
    fun `fullscreen and inline player routes preserve session ownership`() {
        val source = stablePlayerScreenSource()

        val fullscreen = source.substringAfter("StableFullscreenPlayerReplacement(")
            .substringBefore("\n        } else {")
        assertOrdered(
            fullscreen,
            "onReady = { viewModel.onInternalPlaybackReady(it) }",
            "onP2pBoundaryTelemetry = viewModel::onP2pPlayerBoundaryTelemetry",
            "onError = { sessionId, message ->",
            "viewModel.onInternalPlaybackError(message, context, sessionId)"
        )

        val inline = source.substringAfter("StableCenterPaneReplacement(")
            .substringBefore("StableChannelBrowserReplacement(")
        assertOrdered(
            inline,
            "onReady = viewModel::onInternalPlaybackReady",
            "onP2pBoundaryTelemetry = viewModel::onP2pPlayerBoundaryTelemetry",
            "onError = { sessionId, message ->",
            "viewModel.onInternalPlaybackError(message, context, sessionId)"
        )
    }

    @Test
    fun `stale ready callback cannot publish readiness for a replaced session`() {
        val body = functionBody(
            source = playerViewModelSource(),
            signature = "fun onInternalPlaybackReady(sessionId: Long? = null)"
        )

        assertOrdered(
            body,
            "val currentSession = state.internalSession",
            "!isCurrentPrimaryPlaybackRequest(currentSession.requestId)",
            "return",
            "if (sessionId != null && currentSession?.sessionId != sessionId)",
            "status = \"player_ready_ignored\"",
            "return",
            "_uiState.update { it.copy(isStartingPlayback = false, lastError = null) }"
        )
    }

    @Test
    fun `stale error callback is rejected before recovery classification`() {
        val body = functionBody(
            source = playerViewModelSource(),
            signature = "fun onInternalPlaybackError(message: String, context: Context? = null, sessionId: Long? = null)"
        )

        assertOrdered(
            body,
            "val session = state.internalSession ?: return",
            "if (sessionId != null && session.sessionId != sessionId)",
            "status = \"player_error_ignored\"",
            "return",
            "if (session.requestId == 0L || !isCurrentPrimaryPlaybackRequest(session.requestId))",
            "return",
            "val errorKind = classifyPlaybackError(message, isP2pPlayback)"
        )
    }

    @Test
    fun `p2p boundary telemetry is accepted only for the active p2p session`() {
        val body = functionBody(
            source = playerViewModelSource(),
            signature = "internal fun onP2pPlayerBoundaryTelemetry(telemetry: P2pPlayerBoundaryTelemetry)"
        )

        assertOrdered(
            body,
            "val session = state.internalSession ?: return",
            "if (session.sessionId != telemetry.sessionId || !session.isP2pPlayback) return",
            "status = \"player_p2p_boundary\"",
            "sessionId=${'$'}{telemetry.sessionId}",
            "requestId=${'$'}{session.requestId}"
        )
    }

    private fun stablePlayerShellSource(): String = sourceFile(
        modulePath = "feature/player",
        relativePath = "src/main/java/com/iptv/tv/feature/player/StablePlayerShell.kt"
    )

    private fun stablePlayerScreenSource(): String = sourceFile(
        modulePath = "feature/player",
        relativePath = "src/main/java/com/iptv/tv/feature/player/StablePlayerScreenReplacement.kt"
    )

    private fun playerViewModelSource(): String = sourceFile(
        modulePath = "feature/player",
        relativePath = "src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt"
    )

    private fun sourceFile(modulePath: String, relativePath: String): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = cursor ?: return@repeat
            listOf(
                File(directory, relativePath),
                File(directory, "$modulePath/$relativePath")
            ).firstOrNull(File::isFile)?.let { return it.readText() }
            cursor = directory.parentFile
        }
        error("Production source was not found: $relativePath")
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

    private fun occurrences(source: String, token: String): Int =
        source.windowed(size = token.length, step = 1, partialWindows = false)
            .count { it == token }

    private fun assertOrdered(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = source.indexOf(token, startIndex = previous + 1)
            assertTrue("Missing or out-of-order ownership token: $token", index > previous)
            previous = index
        }
    }
}
