package com.iptv.tv.core.data.repository

internal object RecordingLimits {
    const val DEFAULT_RECORDING_DURATION_MS = 2L * 60L * 60L * 1000L
    const val MAX_RECORDING_DURATION_MS = 6L * 60L * 60L * 1000L
    const val MIN_RECORDING_FREE_BYTES = 64L * 1024L * 1024L
    const val FREE_SPACE_RESERVE_BYTES = 256L * 1024L * 1024L
    const val ABSOLUTE_MAX_RECORDING_BYTES = 8L * 1024L * 1024L * 1024L

    fun defaultScheduledEndAt(startedAt: Long): Long {
        return startedAt + DEFAULT_RECORDING_DURATION_MS
    }

    fun hardEndAt(startedAt: Long, scheduledEndAt: Long?): Long {
        val requestedEndAt = scheduledEndAt
            ?.takeIf { it > startedAt }
            ?: defaultScheduledEndAt(startedAt)
        return requestedEndAt.coerceAtMost(startedAt + MAX_RECORDING_DURATION_MS)
    }

    fun maxRecordingBytes(usableSpace: Long): Long {
        if (usableSpace <= MIN_RECORDING_FREE_BYTES) return 0L
        val cappedByReserve = (usableSpace - FREE_SPACE_RESERVE_BYTES).coerceAtLeast(0L)
        return cappedByReserve.coerceAtMost(ABSOLUTE_MAX_RECORDING_BYTES)
    }
}
