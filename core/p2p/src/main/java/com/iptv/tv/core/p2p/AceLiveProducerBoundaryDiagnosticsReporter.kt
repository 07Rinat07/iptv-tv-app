package com.iptv.tv.core.p2p

import android.util.Log

/**
 * Bounded observational telemetry for the V4d producer boundary.
 *
 * This reporter is intentionally side-effect free with respect to scheduling, ownership, request
 * depth, recovery and media acceptance. It only records which stage was actually crossed so field
 * evidence can distinguish a scheduler stall from request-routing or chunk-ingress/reassembly
 * failures.
 *
 * The first observation of every stage is emitted immediately. Repeated observations are aggregated
 * and emitted at most once per [periodicIntervalMillis], preventing healthy live traffic from
 * flooding logcat while still preserving progress evidence during a persistent producer gap.
 */
class AceLiveProducerBoundaryDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit = { status, message ->
        Log.i(LOG_TAG, "$status $message")
    },
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS,
    private val context: AceLiveRuntimeDiagnosticsContext? = null
) {
    private val counts = linkedMapOf<AceLiveProducerBoundaryStage, Long>()
    private val seenStages = linkedSetOf<AceLiveProducerBoundaryStage>()
    private var lastReportedAtMillis: Long? = null

    init {
        require(periodicIntervalMillis > 0L) { "periodicIntervalMillis must be positive" }
    }

    @Synchronized
    fun record(
        sessionId: Long,
        stage: AceLiveProducerBoundaryStage,
        peerId: Long? = null,
        piece: Long? = null,
        disposition: String? = null,
        bytes: Long? = null,
        acceptedChunks: Int? = null,
        assignmentAgeMillis: Long? = null,
        progressAgeMillis: Long? = null,
        nowMillis: Long = System.currentTimeMillis()
    ) {
        require(sessionId >= 0L) { "sessionId must be non-negative" }
        require(peerId == null || peerId >= 0L) { "peerId must be non-negative" }
        require(piece == null || piece >= 0L) { "piece must be non-negative" }
        require(bytes == null || bytes >= 0L) { "bytes must be non-negative" }
        require(acceptedChunks == null || acceptedChunks >= 0) { "acceptedChunks must be non-negative" }
        require(assignmentAgeMillis == null || assignmentAgeMillis >= 0L) {
            "assignmentAgeMillis must be non-negative"
        }
        require(progressAgeMillis == null || progressAgeMillis >= 0L) {
            "progressAgeMillis must be non-negative"
        }

        counts[stage] = (counts[stage] ?: 0L) + 1L
        val firstForStage = seenStages.add(stage)
        val previousReportAt = lastReportedAtMillis
        val periodicRefresh = previousReportAt == null ||
            nowMillis - previousReportAt >= periodicIntervalMillis
        if (!firstForStage && !periodicRefresh) return

        lastReportedAtMillis = nowMillis
        runCatching {
            observer(
                STATUS,
                formatMessage(
                    sessionId = sessionId,
                    stage = stage,
                    peerId = peerId,
                    piece = piece,
                    disposition = disposition,
                    bytes = bytes,
                    acceptedChunks = acceptedChunks,
                    assignmentAgeMillis = assignmentAgeMillis,
                    progressAgeMillis = progressAgeMillis
                )
            )
        }
    }

    @Synchronized
    internal fun formatMessage(
        sessionId: Long,
        stage: AceLiveProducerBoundaryStage,
        peerId: Long?,
        piece: Long?,
        disposition: String?,
        bytes: Long? = null,
        acceptedChunks: Int? = null,
        assignmentAgeMillis: Long? = null,
        progressAgeMillis: Long? = null
    ): String = buildString {
        append("session=")
        append(sessionId)
        append(" stage=")
        append(stage.wireName)
        append(" peer=")
        append(peerId?.toString() ?: "none")
        append(" piece=")
        append(piece?.toString() ?: "none")
        append(" disposition=")
        append(disposition ?: "none")
        append(" bytes=")
        append(bytes?.toString() ?: "none")
        if (acceptedChunks != null || assignmentAgeMillis != null || progressAgeMillis != null) {
            append(" accepted_chunks=")
            append(acceptedChunks?.toString() ?: "none")
            append(" assignment_age_ms=")
            append(assignmentAgeMillis?.toString() ?: "none")
            append(" progress_age_ms=")
            append(progressAgeMillis?.toString() ?: "none")
        }
        context?.let { correlation ->
            append(" startup_id=")
            append(correlation.startupId)
            append(" runtime_id=")
            append(correlation.runtimeId)
            append(" generation=")
            append(correlation.generation)
            append(" path=")
            append(correlation.path)
        }
        AceLiveProducerBoundaryStage.values().forEach { knownStage ->
            append(' ')
            append(knownStage.counterName)
            append('=')
            append(counts[knownStage] ?: 0L)
        }
    }

    private companion object {
        const val STATUS = "embedded_ace_live_producer_boundary"
        const val LOG_TAG = "P2P/AceBoundary"
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS = 5_000L
    }
}

enum class AceLiveProducerBoundaryStage(
    val wireName: String,
    val counterName: String
) {
    SCHEDULED("scheduled", "scheduled"),
    SELECTED("selected", "selected"),
    SENT("sent", "sent"),
    REQUEST_TIMEOUT("request_timeout", "request_timeout"),
    CHUNK_INGRESS("chunk_ingress", "chunk_ingress"),
    CHUNK_ACCEPTED("chunk_accepted", "chunk_accepted"),
    CHUNK_REJECTED("chunk_rejected", "chunk_rejected"),
    PIECE_COMPLETED("piece_completed", "piece_completed"),
    AUTHENTICATED("authenticated", "authenticated"),
    AUTHENTICATION_REJECTED("authentication_rejected", "authentication_rejected"),
    TS_RESYNC_OUTPUT("ts_resync_output", "ts_resync_output"),
    MEDIA_APPENDED("media_appended", "media_appended")
}

data class AceLiveRuntimeDiagnosticsContext(
    val startupId: Long,
    val runtimeId: Long,
    val generation: Long,
    val path: String
) {
    init {
        require(startupId >= 0L) { "startupId must be non-negative" }
        require(runtimeId >= 0L) { "runtimeId must be non-negative" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(path.isNotBlank() && path.all { it == '_' || it in 'a'..'z' }) {
            "path must contain only lowercase ASCII letters and underscores"
        }
    }
}
