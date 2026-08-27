package com.iptv.tv.feature.diagnostics

import com.iptv.tv.core.model.SyncLog

/**
 * Builds a bounded, read-only forensic summary from the structured diagnostics already emitted by
 * the embedded Ace Live runtime and Media3 player boundary.
 *
 * This analyzer is deliberately observational. It does not change discovery, retries, peer
 * selection, buffering, timeouts or playback ownership. Its only purpose is to make field logs
 * actionable by identifying the first missing stage in the Torrent TV startup chain.
 */
internal object TorrentForensicReportBuilder {
    private val timelinePhaseRegex = Regex("(?:^|[,\\s])phase=([^,\\s]+)")
    private val boundaryEventRegex = Regex("(?:^|[,\\s])event=([^,\\s]+)")
    private val fortyHexRegex = Regex("(?i)\\b[a-f0-9]{40}\\b")
    private val urlRegex = Regex("(?i)https?://[^\\s,]+")
    private val magnetRegex = Regex("(?i)magnet:\\?[^\\s,]+")
    private val secretParameterRegex = Regex(
        "(?i)(access_token|token|password|passwd|authorization|cookie)=([^,\\s]+)"
    )

    fun build(logs: List<SyncLog>, maxAttempts: Int = DEFAULT_MAX_ATTEMPTS): String {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
        val ordered = logs.sortedBy { it.createdAt }
        val startIndexes = ordered.indices.filter { index -> ordered[index].isTorrentStartupStart() }

        val attempts = when {
            startIndexes.isNotEmpty() -> startIndexes.mapIndexed { position, startIndex ->
                val endExclusive = startIndexes.getOrNull(position + 1) ?: ordered.size
                AttemptWindow(
                    logs = ordered.subList(startIndex, endExclusive),
                    playerRequest = ordered
                        .subList(0, startIndex)
                        .lastOrNull { candidate ->
                            candidate.status == "player_play_request" &&
                                ordered[startIndex].createdAt - candidate.createdAt in 0..PLAYER_CONTEXT_WINDOW_MS
                        }
                )
            }
            ordered.any { it.isTorrentRelevant() } -> listOf(
                AttemptWindow(
                    logs = ordered.filter { it.isTorrentRelevant() },
                    playerRequest = ordered.lastOrNull { it.status == "player_play_request" }
                )
            )
            else -> emptyList()
        }

        if (attempts.isEmpty()) return ""

        return buildString {
            append("=== Torrent TV forensic summary ===\n")
            append("mode=observational-only; attempts=")
            append(attempts.size)
            append("; exported=")
            append(minOf(attempts.size, maxAttempts))
            append('\n')
            attempts.takeLast(maxAttempts).forEachIndexed { index, attempt ->
                if (index > 0) append('\n')
                appendAttempt(index + 1, attempt)
            }
        }
    }

    private fun StringBuilder.appendAttempt(number: Int, attempt: AttemptWindow) {
        val logs = attempt.logs
        val first = logs.firstOrNull() ?: return
        val last = logs.lastOrNull() ?: first
        val timeline = logs
            .filter { it.status == STARTUP_TIMELINE_STATUS }
            .mapNotNull { log ->
                timelinePhaseRegex.find(log.message)?.groupValues?.getOrNull(1)?.let { phase ->
                    phase to extractLong(log.message, "elapsed_ms")
                }
            }
            .distinctBy { it.first }
        val timelinePhases = timeline.map { it.first }.toSet()
        val boundaryEvents = logs
            .filter { it.status == PLAYER_BOUNDARY_STATUS }
            .mapNotNull { log -> boundaryEventRegex.find(log.message)?.groupValues?.getOrNull(1) }
            .distinct()
        val latestPeerQuality = logs.lastOrNull { it.status == PEER_QUALITY_STATUS }
        val discoveryLogs = logs.filter { it.status == PEER_DISCOVERY_STATUS }
        val peerLifecycle = logs.filter { it.status == PEER_LIFECYCLE_STATUS }
        val connectFailures = peerLifecycle.count { it.message.contains("event=connect_failed") }
        val handshakeRejects = peerLifecycle.count { it.message.contains("event=handshake_rejected") }
        val disconnects = peerLifecycle.count { it.message.contains("event=disconnected") }
        val lastFailure = logs.lastOrNull { log ->
            log.status.contains("error", ignoreCase = true) ||
                log.message.contains(" failed", ignoreCase = true) ||
                log.message.contains("exceeded", ignoreCase = true)
        }
        val diagnosis = diagnose(
            timelinePhases = timelinePhases,
            boundaryEvents = boundaryEvents.toSet(),
            playerReady = logs.any { it.status == "player_ready" }
        )

        append("--- Attempt #")
        append(number)
        append(" ---\n")
        append("window: start=")
        append(first.createdAt)
        append(" end=")
        append(last.createdAt)
        append(" duration_ms=")
        append((last.createdAt - first.createdAt).coerceAtLeast(0L))
        append('\n')

        attempt.playerRequest?.let { request ->
            append("player_request: ")
            append(redact(request.message))
            append('\n')
        }

        append("timeline: ")
        if (timeline.isEmpty()) {
            append("none")
        } else {
            append(
                timeline.joinToString(" -> ") { (phase, elapsed) ->
                    if (elapsed == null) phase else "$phase(${elapsed}ms)"
                }
            )
        }
        append('\n')

        append("media3_boundary: ")
        append(if (boundaryEvents.isEmpty()) "none" else boundaryEvents.joinToString(" -> "))
        append('\n')

        if (discoveryLogs.isEmpty()) {
            append("discovery: no structured discovery snapshot\n")
        } else {
            discoveryLogs.takeLast(MAX_DISCOVERY_LINES).forEach { log ->
                append("discovery: ")
                append(redact(log.message))
                append('\n')
            }
        }

        if (latestPeerQuality != null) {
            append("peer_quality: ")
            append(redact(latestPeerQuality.message))
            append('\n')
            append("peer_counts: discovered=")
            append(extractLong(latestPeerQuality.message, "discovered") ?: 0L)
            append(" connected=")
            append(extractLong(latestPeerQuality.message, "connected") ?: 0L)
            append(" handshaked=")
            append(extractLong(latestPeerQuality.message, "handshaked") ?: 0L)
            append(" useful=")
            append(extractLong(latestPeerQuality.message, "windowUseful") ?: 0L)
            append(" unchoked=")
            append(extractLong(latestPeerQuality.message, "unchoked") ?: 0L)
            append(" producing=")
            append(extractLong(latestPeerQuality.message, "producing") ?: 0L)
            append(" aggregate_bps=")
            append(extractLong(latestPeerQuality.message, "aggregate_bps") ?: 0L)
            append('\n')
        }

        append("peer_failures: connect_failed=")
        append(connectFailures)
        append(" handshake_rejected=")
        append(handshakeRejects)
        append(" disconnected=")
        append(disconnects)
        val commonReasons = peerLifecycle
            .mapNotNull(::extractLifecycleReason)
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(MAX_REASON_LINES)
        if (commonReasons.isNotEmpty()) {
            append(" reasons=")
            append(commonReasons.joinToString(",") { (reason, count) -> "$reason:$count" })
        }
        append('\n')

        val producerGap = logs.lastOrNull { it.status == PRODUCER_GAP_STATUS }
        if (producerGap != null) {
            append("producer_gap: ")
            append(redact(producerGap.message))
            append('\n')
        }

        if (lastFailure != null) {
            append("last_failure: status=")
            append(lastFailure.status)
            append(" message=")
            append(redact(lastFailure.message).take(MAX_FAILURE_CHARS))
            append('\n')
        }

        append("first_missing_stage: ")
        append(diagnosis.stage)
        append('\n')
        append("interpretation: ")
        append(diagnosis.interpretation)
        append('\n')
    }

    private fun diagnose(
        timelinePhases: Set<String>,
        boundaryEvents: Set<String>,
        playerReady: Boolean
    ): Diagnosis {
        fun missing(phase: String) = phase !in timelinePhases

        return when {
            missing("discovery_completed") -> Diagnosis(
                "discovery_completed",
                "Peer discovery не завершился. Проверять tracker/DHT bootstrap, сетевые таймауты, отмену coroutine и нехватку heap headroom."
            )
            missing("first_candidate") -> Diagnosis(
                "first_candidate",
                "Discovery завершён, но пригодных peer endpoints нет. Основная зона проверки: tracker/DHT/bootstrap, stale endpoints и фильтрация временно недоступных пиров."
            )
            missing("connected") -> Diagnosis(
                "connected",
                "Кандидаты найдены, но TCP-соединение с пиром не установлено. Проверять недоступные/stale endpoints, NAT/firewall и connect failures."
            )
            missing("handshake") -> Diagnosis(
                "handshake",
                "TCP соединение было, но Ace Live handshake не принят. Проверять reject reason, swarm/protocol compatibility и ранние disconnects."
            )
            missing("useful_window") -> Diagnosis(
                "useful_window",
                "Handshake прошёл, но пир не дал пригодное live window/unchoke. Проверять peer qualification и актуальность live-окна."
            )
            missing("first_media") -> Diagnosis(
                "first_media",
                "Есть пригодный пир, но accepted media не поступает. Проверять scheduler/request depth, piece delivery, authentication/resync и producer gap."
            )
            missing("buffer_ready") -> Diagnosis(
                "buffer_ready",
                "Media уже поступает, но startup buffer не достиг готовности. Проверять throughput, headroom, discontinuity и стабильность producer rate."
            )
            missing("http_reader_open") -> Diagnosis(
                "http_reader_open",
                "Embedded P2P подготовил буфер, но Media3 не открыл loopback HTTP reader. Проверять ownership/URL/session handoff."
            )
            missing("http_first_read") -> Diagnosis(
                "http_first_read",
                "Loopback reader открыт, но положительная доставка байтов не подтверждена. Проверять retained buffer, Range/start offset и lifecycle reader-а."
            )
            "load_started" !in boundaryEvents -> Diagnosis(
                "media3_load_started",
                "Loopback уже отдаёт данные, но Media3 load boundary не зафиксирован. Проверять передачу resolved URL/DataSource и session ownership."
            )
            "ready" !in boundaryEvents && !playerReady -> Diagnosis(
                "media3_ready",
                "Media3 начал загрузку, но не достиг READY. Проверять load_error/retry, MPEG-TS extractor, track discovery и decoder initialization."
            )
            "first_video_frame" !in boundaryEvents && "first_audio" !in boundaryEvents -> Diagnosis(
                "first_frame_or_audio",
                "Media3 достиг READY, но первый кадр/аудио не подтверждены. Проверять decoder/render surface и track selection."
            )
            "first_video_frame" !in boundaryEvents -> Diagnosis(
                "first_video_frame",
                "Аудио уже подтверждено, но первый видеокадр отсутствует. Проверять video decoder, codec support и render surface."
            )
            else -> Diagnosis(
                "none",
                "Цепочка дошла до первого видеокадра. Если воспроизведение затем деградирует, анализировать rebuffer, producer gap, peer replacement и terminal boundary."
            )
        }
    }

    private fun extractLifecycleReason(log: SyncLog): String? {
        val message = log.message
        val raw = Regex("(?:reject_reason|reason)=([^,\\s]+)")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return redact(raw).take(MAX_REASON_CHARS)
    }

    private fun extractLong(message: String, key: String): Long? =
        Regex("(?:^|[,\\s])${Regex.escape(key)}=(\\d+)")
            .find(message)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()

    private fun redact(raw: String): String = raw
        .replace(magnetRegex, "<redacted-magnet>")
        .replace(urlRegex, "<redacted-url>")
        .replace(fortyHexRegex, "<redacted-40hex>")
        .replace(secretParameterRegex) { match -> "${match.groupValues[1]}=<redacted>" }
        .replace('\n', ' ')
        .replace('\r', ' ')

    private fun SyncLog.isTorrentStartupStart(): Boolean =
        status == STARTUP_TIMELINE_STATUS &&
            timelinePhaseRegex.find(message)?.groupValues?.getOrNull(1) == "transport_selection"

    private fun SyncLog.isTorrentRelevant(): Boolean =
        status.startsWith("embedded_ace_live_") ||
            status == PLAYER_BOUNDARY_STATUS ||
            status in PLAYER_RELEVANT_STATUSES

    private data class AttemptWindow(
        val logs: List<SyncLog>,
        val playerRequest: SyncLog?
    )

    private data class Diagnosis(
        val stage: String,
        val interpretation: String
    )

    private const val STARTUP_TIMELINE_STATUS = "embedded_ace_live_startup_timeline"
    private const val PEER_DISCOVERY_STATUS = "embedded_ace_live_peer_discovery"
    private const val PEER_LIFECYCLE_STATUS = "embedded_ace_live_peer_lifecycle"
    private const val PEER_QUALITY_STATUS = "embedded_ace_live_peer_quality"
    private const val PRODUCER_GAP_STATUS = "embedded_ace_live_producer_gap"
    private const val PLAYER_BOUNDARY_STATUS = "player_p2p_boundary"
    private const val PLAYER_CONTEXT_WINDOW_MS = 15_000L
    private const val DEFAULT_MAX_ATTEMPTS = 4
    private const val MAX_DISCOVERY_LINES = 4
    private const val MAX_REASON_LINES = 4
    private const val MAX_REASON_CHARS = 80
    private const val MAX_FAILURE_CHARS = 360

    private val PLAYER_RELEVANT_STATUSES = setOf(
        "player_play_request",
        "player_resolve_ok",
        "player_resolve_error",
        "player_start",
        "player_ready",
        "player_error",
        "player_error_detail",
        "player_rebuffer",
        "player_p2p_restart_error"
    )
}
