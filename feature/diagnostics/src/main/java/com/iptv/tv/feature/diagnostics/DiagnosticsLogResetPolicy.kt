package com.iptv.tv.feature.diagnostics

import com.iptv.tv.core.model.SyncLog

internal fun activeDiagnosticsLogs(
    logs: List<SyncLog>,
    resetAt: Long
): List<SyncLog> = logs.filter { log -> log.createdAt > resetAt }

internal fun DiagnosticsUiState.afterLogReset(resetAt: Long): DiagnosticsUiState = copy(
    logResetAt = resetAt,
    logs = emptyList(),
    logSearchQuery = "",
    playerStartupAvgMs = 0L,
    playerErrorCount = 0,
    playerRebufferCount = 0,
    exportedLogPath = null,
    lastError = null,
    lastInfo = "Старые ошибки сброшены. Новые записи собираются с этого момента."
)
