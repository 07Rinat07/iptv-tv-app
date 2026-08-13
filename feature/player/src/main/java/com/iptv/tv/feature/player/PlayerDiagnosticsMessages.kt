package com.iptv.tv.feature.player

@Suppress("UNUSED_PARAMETER")
internal fun epgErrorSignature(
    playlistId: Long?,
    channelId: Long,
    message: String
): String {
    val normalizedPlaylistId = playlistId ?: -1L
    val lowered = message.lowercase()
    if (message.startsWith("EPG source URL is not configured", ignoreCase = true)) {
        return "missing_epg_source|$normalizedPlaylistId"
    }
    val networkKind = when {
        lowered.contains("sockettimeoutexception") || lowered.contains("timed out") ->
            "timeout"
        lowered.contains("unknownhostexception") || lowered.contains("unable to resolve host") ->
            "dns"
        lowered.contains("connectexception") || lowered.contains("connection refused") ->
            "connect"
        else -> null
    }
    if (networkKind != null) {
        val hostOrIp = extractDiagnosticHostOrIp(message)
        return "epg_net|$networkKind|$normalizedPlaylistId|${hostOrIp ?: "-"}"
    }
    val httpCode = Regex("""\bHTTP\s+(\d{3})\b""").find(message)?.groupValues?.getOrNull(1)
    if (!httpCode.isNullOrBlank()) {
        return "epg_http|$httpCode|$normalizedPlaylistId"
    }
    val compact = message
        .replace(Regex("""\bport\s+\d+\b""", RegexOption.IGNORE_CASE), "port")
        .replace(Regex("""\d{1,9}"""), "#")
        .trim()
        .take(160)
    // Repository failures at this boundary describe the playlist EPG source, not an individual
    // channel. Keeping channelId in the signature caused one oversized XMLTV response to evict
    // crash evidence from the 120-row diagnostics window during rapid channel navigation.
    return "epg_other|$normalizedPlaylistId|$compact"
}

/** Playlist-wide failures that should not be downloaded again on every channel selection. */
internal fun epgSourceRetryBackoffMillis(message: String): Long {
    val lowered = message.lowercase()
    return if (
        lowered.contains("epg input exceeds") &&
        lowered.contains("safety limit")
    ) {
        15L * 60_000L
    } else {
        0L
    }
}

private fun extractDiagnosticHostOrIp(message: String): String? {
    val ip = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""").find(message)?.value
    if (!ip.isNullOrBlank()) return ip
    return Regex("""\b[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}\b""").find(message)?.value
}

internal fun conciseP2pResolveError(rawMessage: String): String {
    val lowered = rawMessage.lowercase()
    return when {
        lowered.contains("did not connect to any peer") ->
            "Torrent TV: доступный пир не найден за отведённое время. " +
                "Возможно, канал сейчас не раздаётся."
        lowered.contains("timed out waiting for 60000 ms") &&
            lowered.contains("transport metadata was unavailable") ->
            "Torrent TV: источник не ответил за 60 секунд. " +
                "Возможно, content ID устарел или канал сейчас не раздаётся."
        lowered.contains("transport metadata was unavailable") ->
            "Torrent TV: не удалось получить данные потока. " +
                "Возможно, источник временно недоступен."
        else -> "P2P-поток не подготовлен. " +
            "Подробности сохранены в диагностике."
    }
}
