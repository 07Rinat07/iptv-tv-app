package com.iptv.tv.feature.player

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pure player-side P2P descriptor classifier; it does not depend on libtorrent or Ace APIs. */
internal object PlayerP2pDescriptor {
    private val hash40Regex = Regex("^[a-fA-F0-9]{40}$")
    private val descriptorQueryKeys = setOf("id", "content_id", "infohash", "hash", "url")

    fun detect(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val nested = extractDescriptorFromUrl(trimmed)
        if (!nested.isNullOrBlank()) return normalize(nested)

        return trimmed.takeIf(::isDescriptor)?.let(::normalize)
    }

    fun describe(raw: String): String {
        val descriptor = detect(raw) ?: return "IPTV поток (прямой URL)"
        return if (descriptor.startsWith("acestream://", ignoreCase = true)) {
            "Ace Stream поток (внешний Engine)"
        } else {
            "BitTorrent поток (встроенный P2P)"
        }
    }

    private fun isDescriptor(raw: String): Boolean {
        val normalized = raw.trim()
        val lowered = normalized.lowercase(Locale.ROOT)
        return lowered.startsWith("magnet:") ||
            lowered.startsWith("acestream://") ||
            lowered.startsWith("ace://") ||
            lowered.startsWith("infohash:") ||
            hash40Regex.matches(normalized) ||
            looksLikeTorrentUrl(normalized)
    }

    private fun looksLikeTorrentUrl(raw: String): Boolean {
        val candidate = raw.substringBefore('|').trim()
        if (!candidate.startsWith("http://", ignoreCase = true) &&
            !candidate.startsWith("https://", ignoreCase = true)
        ) {
            return candidate.substringBefore('#').substringBefore('?')
                .endsWith(".torrent", ignoreCase = true)
        }

        val uri = runCatching { URI(candidate) }.getOrNull()
        if (uri != null) {
            if (uri.path.orEmpty().endsWith(".torrent", ignoreCase = true)) return true
            val query = uri.rawQuery.orEmpty()
            if (query.contains(".torrent", ignoreCase = true)) return true
            if (query.split('&').any { token ->
                    token.substringBefore('=').trim().equals("torrent", ignoreCase = true)
                }
            ) {
                return true
            }
        }

        val withoutFragment = candidate.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        if (path.endsWith(".torrent", ignoreCase = true)) return true
        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        return query.contains(".torrent", ignoreCase = true) ||
            query.contains("torrent=", ignoreCase = true)
    }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed

        if (trimmed.startsWith("ace://", ignoreCase = true)) {
            val tail = trimmed.substringAfter("://").trimStart('/')
            return if (tail.isNotBlank()) "acestream://$tail" else trimmed
        }
        if (trimmed.startsWith("acestream://", ignoreCase = true)) {
            val tail = trimmed.substringAfter("://").trimStart('/')
            return if (tail.isNotBlank()) "acestream://$tail" else trimmed
        }
        if (trimmed.startsWith("infohash:", ignoreCase = true)) {
            val hash = trimmed.substringAfter(':').trim()
            return if (hash40Regex.matches(hash)) "magnet:?xt=urn:btih:$hash" else trimmed
        }
        if (hash40Regex.matches(trimmed)) {
            return "magnet:?xt=urn:btih:$trimmed"
        }
        return trimmed
    }

    private fun extractDescriptorFromUrl(raw: String): String? {
        val source = raw.substringBefore('|').trim()
        val uri = runCatching { URI(source) }.getOrNull() ?: return null
        val query = uri.rawQuery ?: return null
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=').trim().lowercase(Locale.ROOT)
            val encodedValue = pair.substringAfter('=', "").trim()
            if (key.isBlank() || encodedValue.isBlank() || key !in descriptorQueryKeys) continue

            val value = runCatching {
                URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.toString()).trim()
            }.getOrDefault(encodedValue)
            if (value.isNotBlank() && isDescriptor(value)) return value
        }
        return null
    }
}
