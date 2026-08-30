package com.iptv.tv.feature.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPreparedMedia3HandoffWiringRegressionTest {
    @Test
    fun `resolved p2p source reaches primary session only through current request ownership`() {
        val playSelected = functionBody(
            source = playerViewModelSource(),
            signature = "private fun playSelectedWith("
        )

        assertOrdered(
            playSelected,
            "val resolvedChannel = when (val resolved = resolvePlayableChannel(channel, forceAceResolution))",
            "is AppResult.Success -> channel.copy(streamUrl = resolved.data)",
            "AppResult.Loading -> return@launch",
            "if (!isCurrentPrimaryPlaybackRequest(requestId))",
            "status = \"player_play_request_stale\"",
            "PlayerType.INTERNAL -> startInternalPlayback(",
            "channel = resolvedChannel",
            "requestId = requestId"
        )
    }

    @Test
    fun `primary session preserves prepared url headers request ownership and p2p identity`() {
        val startInternal = functionBody(
            source = playerViewModelSource(),
            signature = "private fun startInternalPlayback("
        )

        assertOrdered(
            startInternal,
            "if (!isCurrentPrimaryPlaybackRequest(requestId))",
            "return",
            "val preparedStream = parseKodiStyleStream(channel.streamUrl)",
            "val originalSourceUrl = state.channels",
            "?.streamUrl",
            "?: channel.streamUrl",
            "val isP2pPlayback = PlayerP2pDescriptor.detect(originalSourceUrl) != null",
            "internalSession = InternalPlaybackSession(",
            "streamUrl = preparedStream.streamUrl",
            "requestHeaders = preparedStream.headers",
            "requestId = requestId",
            "isP2pPlayback = isP2pPlayback"
        )
    }

    @Test
    fun `media3 consumes the exact primary session url and headers before playback starts`() {
        val media3Surface = functionBody(
            source = stablePlayerVideoSurfaceSource(),
            signature = "private fun StableMedia3VideoSurface("
        )

        assertOrdered(
            media3Surface,
            "val requestHeaders = session.requestHeaders",
            ".setDefaultRequestProperties(requestHeaders)",
            "val mediaItem = MediaItem.Builder()",
            ".setUri(session.streamUrl)",
            "stableInferMimeType(session.streamUrl)",
            ".build()",
            "player.setMediaItem(mediaItem)",
            "player.prepare()",
            "player.playWhenReady = true"
        )
        assertTrue(
            "P2P sessions must keep a generation-specific Media3 lifecycle key",
            media3Surface.contains(
                "StableP2pMedia3RecoveryPolicy.playerLifecycleKey(\n" +
                    "        isP2pPlayback = session.isP2pPlayback,\n" +
                    "        sessionId = session.sessionId"
            )
        )
    }

    private fun playerViewModelSource(): String = sourceFile(
        moduleRelative = "src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt",
        repositoryRelative = "feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt"
    )

    private fun stablePlayerVideoSurfaceSource(): String = sourceFile(
        moduleRelative = "src/main/java/com/iptv/tv/feature/player/StablePlayerVideoSurface.kt",
        repositoryRelative = "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerVideoSurface.kt"
    )

    private fun sourceFile(moduleRelative: String, repositoryRelative: String): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = cursor ?: return@repeat
            listOf(
                File(directory, moduleRelative),
                File(directory, repositoryRelative)
            ).firstOrNull(File::isFile)?.let { return it.readText() }
            cursor = directory.parentFile
        }
        error("Production source was not found from Gradle test working directory: $repositoryRelative")
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
            assertTrue("Missing or out-of-order player handoff token: $token", index > previous)
            previous = index
        }
    }
}
