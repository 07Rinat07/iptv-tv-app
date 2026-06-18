package com.iptv.tv.feature.importer

import com.iptv.tv.core.model.ProviderDiagnosticKind
import com.iptv.tv.core.model.ProviderSyncHistory
import com.iptv.tv.core.model.ProviderType
import com.iptv.tv.core.model.SyncLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterProviderSyncHistoryTest {

    @Test
    fun extractPersistedProviderSyncHistory_groupsStructuredRows() {
        val history = extractPersistedProviderSyncHistory(
            history = listOf(
                ProviderSyncHistory(
                    id = 1L,
                    providerId = 7L,
                    providerName = "Plex",
                    providerType = ProviderType.PLEX,
                    status = "provider_sync_item_error",
                    playlistId = null,
                    reason = ProviderDiagnosticKind.AUTH,
                    detail = "HTTP 401",
                    createdAt = 300L
                ),
                ProviderSyncHistory(
                    id = 2L,
                    providerId = 7L,
                    providerName = "Plex",
                    providerType = ProviderType.PLEX,
                    status = "provider_sync_item_ok",
                    playlistId = 99L,
                    reason = ProviderDiagnosticKind.OK,
                    detail = "Imported=12",
                    createdAt = 400L
                )
            )
        )

        assertEquals(1, history.size)
        assertEquals("Синхронизация OK, playlistId=99", history.getValue(7L).first().summary)
        assertEquals("PLEX", history.getValue(7L).first().providerType)
        assertEquals("auth", history.getValue(7L)[1].reason)
        assertTrue(history.getValue(7L)[1].isError)
    }

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
