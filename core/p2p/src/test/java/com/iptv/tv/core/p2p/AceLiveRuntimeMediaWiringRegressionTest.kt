package com.iptv.tv.core.p2p

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveRuntimeMediaWiringRegressionTest {
    @Test
    fun `runtime emitPieces preserves authenticated output before producing evidence`() {
        val source = embeddedEngineSource()
        val emitPieces = functionBody(
            source = source,
            signature = "private fun emitPieces(pieces: List<AceLiveReassembledPiece>)"
        )

        assertOrdered(
            emitPieces,
            "authenticator.verifyAndStrip(piece.data)",
            "session.reportPieceAuthenticated(",
            "resynchronizer.consume(verified.data)",
            "session.reportTsResyncOutput(",
            "discontinuityGate.consume(resynchronized)",
            "val acceptedOutputBytes = mediaBuffer.append(media)",
            "if (acceptedOutputBytes > 0)",
            "session.reportMediaAppended(",
            "startupTimelineDiagnostics.onFirstMedia(now)",
            "pool.recordMediaProduced(",
            "refillCoordinator.markMediaProduced(",
            "lastMediaAppendAt.set(now)"
        )
        assertTrue(
            "runtime must attribute only bytes that survived authentication/output acceptance",
            emitPieces.contains("minOf(\n                                    acceptedOutputBytes,\n                                    verified.data.size")
        )
    }

    @Test
    fun `runtime authentication rejection fails startup and output without producing media`() {
        val emitPieces = functionBody(
            source = embeddedEngineSource(),
            signature = "private fun emitPieces(pieces: List<AceLiveReassembledPiece>)"
        )

        assertOrdered(
            emitPieces,
            "is P2pResult.Error ->",
            "session.reportAuthenticationRejected(",
            "startup.completeExceptionally(",
            "mediaBuffer.fail("
        )
        val errorBranch = emitPieces.substringAfter("is P2pResult.Error ->")
        assertTrue(
            "authentication rejection branch must not publish producing evidence",
            !errorBranch.contains("pool.recordMediaProduced(")
        )
    }

    @Test
    fun `both ingress and recovery emitted pieces use the same runtime media boundary`() {
        val source = embeddedEngineSource()

        assertEquals(
            1,
            source.windowed(
                size = "emitPieces(event.result.emittedPieces)".length,
                step = 1,
                partialWindows = false
            ).count { it == "emitPieces(event.result.emittedPieces)" }
        )
        assertEquals(
            1,
            source.windowed(
                size = "emitPieces(applied.emittedPieces)".length,
                step = 1,
                partialWindows = false
            ).count { it == "emitPieces(applied.emittedPieces)" }
        )
    }

    private fun embeddedEngineSource(): String {
        var cursor: File? = File(System.getProperty("user.dir")).canonicalFile
        repeat(8) {
            val directory = cursor ?: return@repeat
            val candidates = listOf(
                File(
                    directory,
                    "src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt"
                ),
                File(
                    directory,
                    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt"
                )
            )
            candidates.firstOrNull(File::isFile)?.let { return it.readText() }
            cursor = directory.parentFile
        }
        error("AceLiveEmbeddedEngine.kt was not found from Gradle test working directory")
    }

    private fun functionBody(source: String, signature: String): String {
        val signatureStart = source.indexOf(signature)
        require(signatureStart >= 0) { "Missing runtime function: $signature" }
        val bodyStart = source.indexOf('{', signatureStart)
        require(bodyStart >= 0) { "Missing runtime function body: $signature" }

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
        error("Unbalanced runtime function body: $signature")
    }

    private fun assertOrdered(source: String, vararg tokens: String) {
        var previous = -1
        tokens.forEach { token ->
            val index = source.indexOf(token, startIndex = previous + 1)
            assertTrue("Missing or out-of-order runtime wiring token: $token", index > previous)
            previous = index
        }
    }
}
