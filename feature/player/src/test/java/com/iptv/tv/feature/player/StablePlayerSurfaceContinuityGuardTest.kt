package com.iptv.tv.feature.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StablePlayerSurfaceContinuityGuardTest {
    @Test
    fun `production shell delegates both placements to the movable surface owner`() {
        val shell = productionSource("StablePlayerShell.kt")

        assertFalse(shell.contains("StableVideoSurface("))
        assertEquals(2, shell.windowed("videoSurfaceContent(".length).count { it == "videoSurfaceContent(" })
        assertTrue(shell.contains("expanded = false"))
        assertTrue(shell.contains("expanded = true"))
    }

    @Test
    fun `production screen remembers one surface owner and shares it with every placement`() {
        val screen = productionSource("StablePlayerScreenReplacement.kt")
        val owner = productionSource("StablePlayerSurfaceContinuity.kt")

        assertEquals(1, "rememberStablePlayerVideoSurfaceContent\\(\\)".toRegex().findAll(screen).count())
        assertEquals(3, "videoSurfaceContent = videoSurfaceContent".toRegex().findAll(screen).count())
        assertEquals(1, "StableVideoSurface\\(".toRegex().findAll(owner).count())
        assertTrue(owner.contains("movableContentOf"))
    }

    @Test
    fun `embedded Android video views opt into reuse during movable relocation`() {
        val media3 = productionSource("StablePlayerVideoSurface.kt")
        val libVlc = productionSource("StableLibVlcVideoSurface.kt")

        assertTrue(media3.contains("onReset = { }"))
        assertTrue(libVlc.contains("onReset = { }"))
    }

    @Test
    fun `retained input handlers synchronize from the current Compose controls state`() {
        val media3 = productionSource("StablePlayerVideoSurface.kt")
        val libVlc = productionSource("StableLibVlcVideoSurface.kt")
        val input = productionSource("StablePlayerInput.kt")

        assertTrue(media3.contains("controlsVisible = showControls"))
        assertTrue(libVlc.contains("controlsVisible = showControls"))
        assertTrue(input.contains("controlsVisible: Boolean"))
        assertTrue(input.contains("this.controlsVisible = controlsVisible"))
    }

    private fun productionSource(fileName: String): String {
        val moduleRelative = File("src/main/java/com/iptv/tv/feature/player/$fileName")
        val rootRelative = File("feature/player/src/main/java/com/iptv/tv/feature/player/$fileName")
        val source = listOf(moduleRelative, rootRelative).firstOrNull(File::isFile)
            ?: error("Unable to locate production source $fileName")
        return source.readText()
    }
}
