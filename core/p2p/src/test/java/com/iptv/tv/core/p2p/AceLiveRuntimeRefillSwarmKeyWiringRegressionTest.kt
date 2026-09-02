package com.iptv.tv.core.p2p

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveRuntimeRefillSwarmKeyWiringRegressionTest {
    @Test
    fun `runtime refill swarm key is independent of optional reputation persistence`() {
        val source = embeddedEngineSource()
        val wiring = source
            .substringAfter("private val refillCoordinator = AceLivePeerRefillCoordinator(")
            .substringBefore("private val refillLoop = AceLivePeerRefillLoop(")

        assertTrue(
            "runtime must always scope shared transport backoff to the active swarm",
            wiring.contains("swarmKey = transport.swarmKey.toByteArray()")
        )
        assertTrue(
            "optional reputation persistence must remain wired independently",
            wiring.contains("reputationStore = peerReputationStore")
        )
        assertFalse(
            "swarm key wiring must not depend on the optional reputation store",
            wiring.contains("peerReputationStore?.let")
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
}
