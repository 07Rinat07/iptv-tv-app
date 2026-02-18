package com.iptv.tv

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.iptv.tv.feature.importer.TAG_IMPORTER_FILE_PATH
import com.iptv.tv.feature.importer.TAG_IMPORTER_IMPORT_FILE
import com.iptv.tv.feature.importer.TAG_IMPORTER_LIST
import com.iptv.tv.feature.importer.TAG_IMPORTER_PLAYLIST_NAME
import com.iptv.tv.feature.importer.TAG_IMPORTER_PRIMARY
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appShowsMainTitle() {
        unlockApp()
        composeRule.onNodeWithText("myscanerIPTV").assertExists()
    }

    @Test
    fun navigationButtonsRouteSmoke() {
        unlockApp()
        openSection(Routes.SCANNER)
        assertRoute("scanner")

        openSection(Routes.IMPORTER)
        assertRoute("importer")

        openSection(Routes.PLAYLISTS)
        assertRoute("playlists")

        openSection(Routes.PLAYER)
        assertRoute("player")

        openSection(Routes.SETTINGS)
        assertRoute("settings")

        openSection(Routes.DIAGNOSTICS)
        assertRoute("diagnostics")
    }

    @Test
    fun importTextAndOpenPlayerRouteSmoke() {
        unlockApp()
        val playlistName = "Smoke"
        val rawPlaylist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="smoke-1" group-title="Smoke",Smoke Channel
            udp://239.10.10.10:1234
        """.trimIndent()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val playlistFile = File(targetContext.cacheDir, "smoke-playlist.m3u")
        playlistFile.writeText(rawPlaylist)

        openSection(Routes.IMPORTER)
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(TAG_IMPORTER_PLAYLIST_NAME).fetchSemanticsNodes().isNotEmpty()
        }
        assertRoute("importer")

        composeRule.onNodeWithTag(TAG_IMPORTER_LIST).performScrollToNode(hasTestTag(TAG_IMPORTER_PLAYLIST_NAME))
        composeRule.onNodeWithTag(TAG_IMPORTER_PLAYLIST_NAME).performTextClearance()
        composeRule.onNodeWithTag(TAG_IMPORTER_PLAYLIST_NAME).performTextInput(playlistName)
        composeRule.onNodeWithTag(TAG_IMPORTER_LIST).performScrollToNode(hasTestTag(TAG_IMPORTER_FILE_PATH))
        composeRule.onNodeWithTag(TAG_IMPORTER_FILE_PATH).performTextClearance()
        composeRule.onNodeWithTag(TAG_IMPORTER_FILE_PATH).performTextInput(playlistFile.absolutePath)
        composeRule.onNodeWithTag(TAG_IMPORTER_LIST).performScrollToNode(hasTestTag(TAG_IMPORTER_IMPORT_FILE))
        composeRule.onNodeWithTag(TAG_IMPORTER_IMPORT_FILE).performClick()
        composeRule.onNodeWithTag(TAG_IMPORTER_LIST).performScrollToNode(hasTestTag(TAG_IMPORTER_PRIMARY))

        composeRule.onNodeWithTag(TAG_IMPORTER_PRIMARY).performClick()
        assertRoute("playlists")

        openSection(Routes.PLAYER)
        assertRoute("player")
    }

    private fun assertRoute(route: String) {
        composeRule.onNodeWithTag(TAG_ROUTE_LABEL).assertTextContains(route)
    }

    private fun openSection(route: String) {
        composeRule.onNodeWithTag(TAG_SECTIONS_BUTTON).performClick()
        composeRule.onNodeWithTag(TAG_SECTIONS_LIST).performScrollToNode(hasTestTag(navButtonTag(route)))
        composeRule.onNodeWithTag(navButtonTag(route)).performClick()
    }

    private fun unlockApp() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag(TAG_SECTIONS_BUTTON).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
