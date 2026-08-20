package com.iptv.tv.core.p2p

import android.util.Log

/** Bounded diagnostics for active stale-pool recovery evidence. */
class AceLiveRecoveryDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit = { status, message ->
        Log.i(LOG_TAG, "$status $message")
    },
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS,
    private val context: AceLiveRuntimeDiagnosticsContext? = null
) {
    private var lastReportedAtMillis: Long? = null

    init {
        require(periodicIntervalMillis > 0L) { "periodicIntervalMillis must be positive" }
    }

    /**
     * A throttled `poolStale=false` plan is not recovery evidence, so only the active level is
     * reported. The first observation is immediate and a persistent stall refreshes periodically.
     */
    @Synchronized
    fun maybeReport(
        plan: AceLiveRecoveryPlan,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        if (!plan.poolStale) return
        val previous = lastReportedAtMillis
        if (previous != null && nowMillis - previous < periodicIntervalMillis) return
        lastReportedAtMillis = nowMillis

        runCatching {
            observer(
                STATUS,
                "pool_stale=true timed_out=${plan.timedOutRequests.size} " +
                    "cursor_advance=${plan.cursorAdvance?.toPiece ?: "none"} " +
                    "gap_beyond_limit=${plan.gapBeyondAdvanceLimit}" +
                    context?.let { correlation ->
                        " startup_id=${correlation.startupId}" +
                            " runtime_id=${correlation.runtimeId}" +
                            " generation=${correlation.generation}" +
                            " path=${correlation.path}"
                    }.orEmpty()
            )
        }
    }

    private companion object {
        const val STATUS = "embedded_ace_live_recovery"
        const val LOG_TAG = "P2P/AceRecovery"
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS = 5_000L
    }
}
