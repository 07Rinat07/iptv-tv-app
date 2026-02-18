package com.iptv.tv

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.iptv.tv.feature.importer.TAG_IMPORTER_FILE_PATH
import com.iptv.tv.feature.importer.TAG_IMPORTER_IMPORT_FILE
import com.iptv.tv.feature.importer.TAG_IMPORTER_LIST
import com.iptv.tv.feature.importer.TAG_IMPORTER_PLAYLIST_NAME
import com.iptv.tv.feature.importer.TAG_IMPORTER_PRIMARY
import com.iptv.tv.feature.playlists.TAG_PLAYLISTS_LIST
import com.iptv.tv.feature.playlists.TAG_PLAYLISTS_REFRESH
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SyncSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun manualRefreshUpdatesPlaylistsSyncState() {
        unlockApp()
        val playlistName = "SyncSmoke"
        val rawPlaylist = """
            #EXTM3U
            #EXTINF:-1 tvg-id="sync-smoke-1" group-title="Smoke",Sync Smoke Channel
            udp://239.20.20.20:1234
        """.trimIndent()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val playlistFile = File(targetContext.cacheDir, "sync-smoke-playlist.m3u")
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

        composeRule.onNodeWithTag(TAG_PLAYLISTS_LIST).performScrollToNode(hasTestTag(TAG_PLAYLISTS_REFRESH))
        composeRule.onNodeWithTag(TAG_PLAYLISTS_REFRESH).performClick()
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
