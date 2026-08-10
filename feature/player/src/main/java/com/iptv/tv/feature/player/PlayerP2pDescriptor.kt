package com.iptv.tv.feature.player

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/** Pure player-side P2P descriptor classifier; it does not depend on libtorrent or Ace APIs. */
internal object PlayerP2pDescriptor {
    private val hash40Regex = Regex("^[a-fA-F0-9]{40}$")
    private val hash32Base32Regex = Regex("^[A-Za-z2-7]{32}$")
    private val aceContentIdRegex = Regex("^[A-Za-z0-9_-]{20,128}$")
    private val infoHashQueryKeys = setOf("infohash", "hash")
    private val aceContentIdQueryKeys = setOf("content_id", "id")
    private const val URN_BTIH_PREFIX = "urn:btih:"

    fun detect(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val nested = extractDescriptorFromUrl(trimmed)
        if (!nested.isNullOrBlank()) return nested

        return trimmed.takeIf(::isDescriptor)?.let(::normalize)
    }

    fun describe(raw: String): String {
        val descriptor = detect(raw) ?: return "IPTV поток (прямой URL)"
        return when {
            descriptor.startsWith("acestream://", ignoreCase = true) -> "Ace Stream поток (P2P/Ace)"
            else -> "BitTorrent поток (встроенный P2P)"
        }
    }

    private fun isDescriptor(raw: String): Boolean {
        val normalized = raw.trim()
        val lowered = normalized.lowercase(Locale.ROOT)
        return lowered.startsWith("magnet:") ||
            lowered.startsWith("acestream://") ||
            lowered.startsWith("ace://") ||
            lowered.startsWith("infohash:") ||
            lowered.startsWith(URN_BTIH_PREFIX) ||
            hash40Regex.matches(normalized) ||
            hash32Base32Regex.matches(normalized) ||
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
            return normalizeInfoHash(hash)?.let { "magnet:?xt=urn:btih:$it" } ?: trimmed
        }
        if (trimmed.startsWith(URN_BTIH_PREFIX, ignoreCase = true)) {
            val hash = trimmed.substring(URN_BTIH_PREFIX.length).trim()
            return normalizeInfoHash(hash)?.let { "magnet:?xt=urn:btih:$it" } ?: trimmed
        }
        normalizeInfoHash(trimmed)?.let { normalizedHash ->
            return "magnet:?xt=urn:btih:$normalizedHash"
        }
        return trimmed
    }

    private fun extractDescriptorFromUrl(raw: String): String? {
        val source = raw.substringBefore('|').trim()
        val uri = runCatching { URI(source) }.getOrNull() ?: return null
        val query = uri.rawQuery ?: return null
        val params = query.split('&').mapNotNull(::decodeQueryPair)

        // A real infohash is usable by the embedded BitTorrent engine and must win even when
        // the same Ace API URL also carries a separate content_id.
        params.firstOrNull { it.first in infoHashQueryKeys }
            ?.second
            ?.let(::normalizeInfoHashParameter)
            ?.let { return it }

        params.firstOrNull { it.first == "url" }
            ?.second
            ?.takeIf { it != source }
            ?.let(::detect)
            ?.let { return it }

        // Ace content_id/id is deliberately NOT treated as a bare BitTorrent infohash. Legacy
        // 127.0.0.1:6878 playlist URLs are descriptor syntax and normalize through this same path.
        params.firstOrNull { it.first in aceContentIdQueryKeys }
            ?.second
            ?.let(::normalizeAceContentIdParameter)
            ?.let { return it }

        return null
    }

    private fun decodeQueryPair(pair: String): Pair<String, String>? {
        val key = pair.substringBefore('=').trim().lowercase(Locale.ROOT)
        val encodedValue = pair.substringAfter('=', "").trim()
        if (key.isBlank() || encodedValue.isBlank()) return null
        val value = runCatching {
            URLDecoder.decode(encodedValue, StandardCharsets.UTF_8.toString()).trim()
        }.getOrDefault(encodedValue)
        return key to value
    }

    private fun normalizeInfoHashParameter(raw: String): String? {
        val value = raw.trim()
        normalizeInfoHash(value)?.let { normalized ->
            return "magnet:?xt=urn:btih:$normalized"
        }
        return when {
            value.startsWith("infohash:", ignoreCase = true) ||
                value.startsWith(URN_BTIH_PREFIX, ignoreCase = true) -> {
                normalize(value).takeIf { it.startsWith("magnet:", ignoreCase = true) }
            }
            value.startsWith("magnet:?", ignoreCase = true) -> value
            else -> null
        }
    }

    private fun normalizeInfoHash(raw: String): String? = when {
        hash40Regex.matches(raw) -> raw.lowercase(Locale.ROOT)
        hash32Base32Regex.matches(raw) -> raw.uppercase(Locale.ROOT)
        else -> null
    }

    private fun normalizeAceContentIdParameter(raw: String): String? {
        val value = raw.trim()
        return when {
            value.startsWith("acestream://", ignoreCase = true) ||
                value.startsWith("ace://", ignoreCase = true) -> normalize(value)
            aceContentIdRegex.matches(value) -> "acestream://$value"
            else -> null
        }
    }
}
