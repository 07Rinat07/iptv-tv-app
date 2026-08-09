package com.iptv.tv.core.engine.data

import java.net.URI

/**
 * Normalized source accepted by the official Ace Stream Engine HTTP/API layer.
 *
 * The parser is intentionally independent from a concrete engine service so it can be
 * unit-tested and reused by playlist import, player resolution and diagnostics.
 */
sealed interface AceStreamDescriptor {
    val original: String

    data class ContentId(
        override val original: String,
        val value: String
    ) : AceStreamDescriptor

    data class Magnet(
        override val original: String,
        val value: String
    ) : AceStreamDescriptor

    data class TransportFile(
        override val original: String,
        val value: String
    ) : AceStreamDescriptor

    data class LocalEngineUrl(
        override val original: String,
        val value: String
    ) : AceStreamDescriptor

    data class Direct(
        override val original: String,
        val value: String
    ) : AceStreamDescriptor
}

object AceStreamDescriptorParser {
    private val contentIdRegex = Regex("^[a-fA-F0-9]{40}$")
    private const val INFO_HASH_PREFIX = "infohash:"

    fun parse(raw: String): AceStreamDescriptor {
        val value = raw.trim()
        if (value.isBlank()) return AceStreamDescriptor.Direct(raw, value)

        extractInfoHash(value)?.let { infoHash ->
            val magnet = "magnet:?xt=urn:btih:$infoHash"
            return AceStreamDescriptor.Magnet(raw, magnet)
        }

        extractContentId(value)?.let { contentId ->
            return AceStreamDescriptor.ContentId(raw, contentId.lowercase())
        }

        if (value.startsWith("magnet:", ignoreCase = true)) {
            return AceStreamDescriptor.Magnet(raw, value)
        }

        if (isTransportFile(value)) {
            return AceStreamDescriptor.TransportFile(raw, value)
        }

        if (isLocalAceEngineUrl(value)) {
            return AceStreamDescriptor.LocalEngineUrl(raw, value)
        }

        return AceStreamDescriptor.Direct(raw, value)
    }

    fun toEngineRequest(descriptor: AceStreamDescriptor): Map<String, String> = when (descriptor) {
        is AceStreamDescriptor.ContentId -> mapOf("content_id" to descriptor.value)
        is AceStreamDescriptor.Magnet -> mapOf("magnet" to descriptor.value)
        is AceStreamDescriptor.TransportFile -> mapOf("url" to descriptor.value)
        is AceStreamDescriptor.LocalEngineUrl -> mapOf("url" to descriptor.value)
        is AceStreamDescriptor.Direct -> emptyMap()
    }

    private fun extractInfoHash(value: String): String? {
        if (!value.startsWith(INFO_HASH_PREFIX, ignoreCase = true)) return null
        val candidate = value.substring(INFO_HASH_PREFIX.length).trim()
        return candidate.takeIf(contentIdRegex::matches)
    }

    private fun extractContentId(value: String): String? {
        if (contentIdRegex.matches(value)) return value

        val prefixes = listOf("acestream://", "ace://", "content-id:", "content_id:")
        prefixes.firstOrNull { value.startsWith(it, ignoreCase = true) }?.let { prefix ->
            val candidate = value.substring(prefix.length).trimStart('/').substringBefore('?').trim()
            if (contentIdRegex.matches(candidate)) return candidate
        }

        if (
            value.startsWith("acestream:", ignoreCase = true) ||
            value.startsWith("ace:", ignoreCase = true)
        ) {
            extractContentIdFromQuery(value.substringAfter('?', missingDelimiterValue = ""))?.let {
                return it
            }
        }

        if (isLocalAceEngineUrl(value)) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            extractContentIdFromQuery(uri.rawQuery.orEmpty())?.let { return it }
        }

        return null
    }

    private fun extractContentIdFromQuery(query: String): String? {
        if (query.isBlank()) return null
        query.split('&').forEach { pair ->
            val key = pair.substringBefore('=').trim().lowercase()
            val candidate = pair.substringAfter('=', "").trim()
            if (key in setOf("id", "content_id", "content-id") && contentIdRegex.matches(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun isTransportFile(value: String): Boolean {
        val lower = value.lowercase()
        return lower.endsWith(".torrent") || lower.endsWith(".acelive")
    }

    private fun isLocalAceEngineUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        if (!isLoopback) return false
        return uri.port == 6878 || uri.path.orEmpty().startsWith("/ace/")
    }
}
