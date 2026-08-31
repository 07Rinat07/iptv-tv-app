package com.iptv.tv.feature.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class StableCatchUpPlaybackWiringRegressionTest {
    @Test
    fun `archive launch revalidates capability before entering primary playback pipeline`() {
        val playCatchUp = functionBody(
            source = playerViewModelSource(),
            signature = "fun playCatchUpProgram("
        )

        assertOrdered(
            playCatchUp,
            "StableCatchUpActionPolicy.resolve(",
            "?.takeIf { it.supported }",
            "?.playbackUrl",
            "if (playbackUrl == null)",
            "val requestId = beginPrimaryPlaybackRequest()",
            "primaryPlaybackJob = viewModelScope.launch",
            "engineRepository.stopTorrentStream()",
            "if (!isCurrentPrimaryPlaybackRequest(requestId))",
            "val archiveChannel = channel.copy(streamUrl = playbackUrl)",
            "PlayerType.INTERNAL -> startInternalPlayback(",
            "channel = archiveChannel",
            "requestId = requestId",
            "PlayerType.VLC -> launchExternalVlcOrFallback(",
            "channel = archiveChannel",
            "requestId = requestId"
        )
    }

    @Test
    fun `programme dialog exposes archive callback only behind fail closed policy`() {
        val dialog = functionBody(
            source = programmeDialogSource(),
            signature = "internal fun StableProgrammeDialog("
        )

        assertOrdered(
            dialog,
            "StableCatchUpActionPolicy.isAvailable(",
            "channel = channel",
            "program = program",
            "OutlinedButton(",
            "onClick = { onPlayCatchUp(program) }",
            "Text(\"Архив\")"
        )
    }

    private fun playerViewModelSource(): String = sourceFile(
        moduleRelative = "src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt",
        repositoryRelative = "feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt"
    )

    private fun programmeDialogSource(): String = sourceFile(
        moduleRelative = "src/main/java/com/iptv/tv/feature/player/StableProgrammeDialog.kt",
        repositoryRelative = "feature/player/src/main/java/com/iptv/tv/feature/player/StableProgrammeDialog.kt"
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
            assertTrue("Missing or out-of-order archive playback token: $token", index > previous)
            previous = index
        }
    }
}
