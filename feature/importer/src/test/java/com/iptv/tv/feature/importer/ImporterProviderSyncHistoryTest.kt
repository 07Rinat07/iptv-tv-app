package com.iptv.tv.feature.importer

import com.iptv.tv.core.model.SyncLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterProviderSyncHistoryTest {

    @Test
    fun extractProviderSyncHistory_groupsByProviderAndLimitsItems() {
        val history = extractProviderSyncHistory(
            logs = listOf(
                SyncLog(1L, null, "provider_sync_item_ok", "providerId=7, type=PLEX, playlistId=99", 400L),
                SyncLog(2L, null, "provider_sync_item_error", "providerId=7, type=PLEX, reason=auth, detail=HTTP 401", 300L),
                SyncLog(3L, null, "provider_sync_item_start", "providerId=7, type=PLEX, name=Plex", 200L),
                SyncLog(4L, null, "provider_sync_item_loading", "providerId=7, type=PLEX", 100L),
                SyncLog(5L, null, "provider_sync_item_ok", "providerId=7, type=PLEX, playlistId=100", 50L),
                SyncLog(6L, null, "provider_sync_item_ok", "providerId=8, type=M3U, playlistId=12", 250L),
                SyncLog(7L, null, "other_status", "providerId=7", 999L)
            ),
            perProviderLimit = 4
        )

        assertEquals(2, history.size)
        assertEquals(4, history.getValue(7L).size)
        assertEquals("Синхронизация OK, playlistId=99", history.getValue(7L).first().summary)
        assertEquals("PLEX", history.getValue(7L).first().providerType)
        assertEquals(99L, history.getValue(7L).first().playlistId)
        assertEquals("Синхронизация OK, playlistId=12", history.getValue(8L).first().summary)
    }

    @Test
    fun providerSyncSummary_formatsErrorReasonAndDetail() {
        val summary = providerSyncSummary(
            SyncLog(
                id = 1L,
                playlistId = null,
                status = "provider_sync_item_error",
                message = "providerId=7, type=PLEX, reason=parser, detail=Unexpected HTML response",
                createdAt = 100L
            )
        )

        assertTrue(summary.contains("Ошибка: parser"))
        assertTrue(summary.contains("Unexpected HTML response"))
    }

    @Test
    fun extractProviderSyncHistory_extractsDiagnosticFields() {
        val history = extractProviderSyncHistory(
            logs = listOf(
                SyncLog(
                    id = 1L,
                    playlistId = null,
                    status = "provider_sync_item_error",
                    message = "providerId=7, type=XTREAM, reason=auth, detail=Invalid credentials",
                    createdAt = 100L
                )
            )
        )

        val item = history.getValue(7L).single()
        assertEquals("XTREAM", item.providerType)
        assertEquals("auth", item.reason)
        assertEquals("Invalid credentials", item.detail)
        assertTrue(item.isError)
    }
}
