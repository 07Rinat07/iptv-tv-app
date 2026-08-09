package com.iptv.tv.core.p2p

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Explicit description of an Ace Live transport source.
 *
 * Ace Live transport files use a protocol that is distinct from standard BitTorrent metadata.
 * Detecting them before generic P2P parsing prevents `.acelive` sources from accidentally entering
 * the embedded libtorrent path while keeping the raw descriptor intact for the compatibility engine.
 *
 * [metadata] is intentionally optional: URL/data detection does not decrypt the proprietary
 * transport body. A future verified metadata provider can attach independently decoded public live
 * fields without changing this routing contract.
 */
data class AceLiveDescriptor(
    val original: String,
    val transport: String,
    val metadata: AceLiveTransportMetadata? = null
)

object AceLiveDescriptorParser {
    fun parse(raw: String): AceLiveDescriptor? {
        val value = raw.trim()
        if (value.isBlank()) return null

        if (looksLikeAceLiveTransport(value)) {
            return AceLiveDescriptor(original = value, transport = value)
        }

        if (!isAceScheme(value)) return null

        listOf("url", "data").forEach { name ->
            val parameter = queryParameter(value, name) ?: return@forEach
            val decoded = decodeQueryValue(parameter)
            if (looksLikeAceLiveTransport(decoded)) {
                return AceLiveDescriptor(original = value, transport = decoded)
            }
        }

        return null
    }

    private fun isAceScheme(value: String): Boolean =
        value.startsWith("acestream:", ignoreCase = true) ||
            value.startsWith("ace:", ignoreCase = true)

    private fun queryParameter(value: String, name: String): String? {
        val query = value.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return null

        return query.split('&').firstNotNullOfOrNull { part ->
            val key = part.substringBefore('=', missingDelimiterValue = part).trim()
            if (!key.equals(name, ignoreCase = true)) {
                null
            } else {
                part.substringAfter('=', missingDelimiterValue = "").trim().takeIf { it.isNotBlank() }
            }
        }
    }

    private fun decodeQueryValue(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun looksLikeAceLiveTransport(value: String): Boolean {
        val withoutFragment = value.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        return path.endsWith(".acelive", ignoreCase = true)
    }
}
