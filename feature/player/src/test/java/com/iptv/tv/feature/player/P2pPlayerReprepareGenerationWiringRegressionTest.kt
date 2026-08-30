package com.iptv.tv.feature.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pPlayerReprepareGenerationWiringRegressionTest {
    @Test
    fun `p2p playback error retries only while request and session ownership are current`() {
        val errorHandler = functionBody(
            source = playerViewModelSource(),
            signature = "fun onInternalPlaybackError("
        )

        assertOrdered(
            errorHandler,
            "val session = state.internalSession ?: return",
            "if (sessionId != null && session.sessionId != sessionId)",
            "if (session.requestId == 0L || !isCurrentPrimaryPlaybackRequest(session.requestId))",
            "val sourceChannel = state.channels.firstOrNull { channel -> channel.id == session.channelId }",
            "val isP2pPlayback = sourceChannel?.streamUrl?.let(::detectAceDescriptor) != null",
            "val requestId = session.requestId",
            "primaryRetryJob = viewModelScope.launch",
            "primaryPlaybackOwnership.ownsSession(",
            "delay(delayMs)",
            "val latestSession = _uiState.value.internalSession",
            "primaryPlaybackOwnership.ownsSession("
        )
    }

    @Test
    fun `p2p retry resolves original descriptor and discards stale resolved generation`() {
        val errorHandler = functionBody(
            source = playerViewModelSource(),
            signature = "fun onInternalPlaybackError("
        )

        assertOrdered(
            errorHandler,
            "if (sourceChannel != null && isP2pPlayback)",
            "when (val resolved = resolvePlayableChannel(sourceChannel))",
            "is AppResult.Success ->",
            "val current = _uiState.value.internalSession",
            "primaryPlaybackOwnership.ownsSession(",
            "status = \"player_retry_superseded\"",
            "message = \"Resolved stale P2P retry discarded:",
            "return@launch",
            "startInternalPlayback(",
            "channel = sourceChannel.copy(streamUrl = resolved.data)",
            "infoMessage = \"P2P-сессия переподключена\"",
            "requestId = requestId",
            "retryAttempt = nextAttempt"
        )

        assertFalse(
            "P2P reprepare must not resolve the previous generation localhost session URL",
            errorHandler.contains("resolvePlayableChannel(session")
        )
        assertFalse(
            "P2P reprepare must not feed the previous prepared session URL back into resolution",
            errorHandler.contains("resolvePlayableChannel(sourceChannel.copy(streamUrl = session.streamUrl")
        )
    }

    @Test
    fun `successful p2p reprepare exits before generic same source retry path`() {
        val errorHandler = functionBody(
            source = playerViewModelSource(),
            signature = "fun onInternalPlaybackError("
        )

        assertOrdered(
            errorHandler,
            "if (sourceChannel != null && isP2pPlayback)",
            "startInternalPlayback(",
            "channel = sourceChannel.copy(streamUrl = resolved.data)",
            "return@launch",
            "val retrySessionId = primaryPlaybackOwnership.nextSessionId()"
        )
    }

    @Test
    fun `reprepared p2p source becomes a newly numbered primary session`() {
        val startInternal = functionBody(
            source = playerViewModelSource(),
            signature = "private fun startInternalPlayback("
        )

        assertOrdered(
            startInternal,
            "if (!isCurrentPrimaryPlaybackRequest(requestId))",
            "val preparedStream = parseKodiStyleStream(channel.streamUrl)",
            "val nextSessionId = primaryPlaybackOwnership.nextSessionId()",
            "internalSession = InternalPlaybackSession(",
            "sessionId = nextSessionId",
            "streamUrl = preparedStream.streamUrl",
            "requestId = requestId",
            "isP2pPlayback = isP2pPlayback"
        )
    }

    private fun playerViewModelSource(): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = cursor ?: return@repeat
            listOf(
                File(
                    directory,
                    "src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt"
                ),
                File(
                    directory,
                    "feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt"
                )
            ).firstOrNull(File::isFile)?.let { return it.readText() }
            cursor = directory.parentFile
        }
        error("PlayerViewModel.kt was not found from Gradle test working directory")
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
            assertTrue("Missing or out-of-order P2P reprepare token: $token", index > previous)
            previous = index
        }
    }
}
