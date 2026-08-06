package com.iptv.tv.feature.diagnostics

import com.iptv.tv.core.model.SyncLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsLogResetPolicyTest {
    @Test
    fun keepsOnlyLogsCreatedAfterResetPoint() {
        val logs = listOf(
            log(id = 1L, createdAt = 99L),
            log(id = 2L, createdAt = 100L),
            log(id = 3L, createdAt = 101L)
        )

        assertEquals(listOf(3L), activeDiagnosticsLogs(logs, resetAt = 100L).map { it.id })
    }

    @Test
    fun resetStateClearsVisibleCountersAndSearch() {
        val state = DiagnosticsUiState(
            logs = listOf(log(id = 1L, createdAt = 10L)),
            logSearchQuery = "error",
            playerStartupAvgMs = 500L,
            playerErrorCount = 3,
            playerRebufferCount = 2
        ).afterLogReset(resetAt = 20L)

        assertTrue(state.logs.isEmpty())
        assertEquals("", state.logSearchQuery)
        assertEquals(0, state.playerErrorCount)
        assertEquals(0, state.playerRebufferCount)
        assertEquals(20L, state.logResetAt)
    }

    private fun log(id: Long, createdAt: Long) = SyncLog(
        id = id,
        playlistId = null,
        status = "player_error",
        message = "error",
        createdAt = createdAt
    )
}
