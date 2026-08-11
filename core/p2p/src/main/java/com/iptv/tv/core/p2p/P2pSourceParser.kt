package com.iptv.tv.core.p2p

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object P2pSourceParser {
    private val sha1InfoHashHex = Regex("^[0-9a-fA-F]{40}$")
    private val sha1InfoHashBase32 = Regex("^[A-Za-z2-7]{32}$")
    private const val URN_BTIH_PREFIX = "urn:btih:"

    fun parse(raw: String): P2pResult<P2pSource> {
        val value = raw.trim()
        if (value.isBlank()) return P2pResult.Error("P2P source is empty")

        aceDescriptorQueryParameter(value, "infohash")?.let { hash ->
            return normalizeInfoHash(hash)?.let { normalized ->
                P2pResult.Success(P2pSource.InfoHash(normalized))
            } ?: P2pResult.Error("Invalid BitTorrent infohash in Ace Stream descriptor")
        }

        aceDescriptorQueryParameter(value, "magnet")?.let { magnet ->
            return if (magnet.startsWith("magnet:?", ignoreCase = true)) {
                P2pResult.Success(P2pSource.Magnet(magnet))
            } else {
                P2pResult.Error("Invalid magnet URI in Ace Stream descriptor")
            }
        }

        aceDescriptorQueryParameter(value, "url")?.let { nested ->
            parseNestedAceBitTorrentSource(nested)?.let { return it }
        }

        aceDescriptorQueryParameter(value, "data")?.let { nested ->
            parseNestedAceBitTorrentSource(nested)?.let { return it }
        }

        aceDescriptorQueryParameter(value, "content_id")?.let { contentId ->
            if (contentId.isNotBlank()) {
                return P2pResult.Success(P2pSource.AceContentId(contentId))
            }
        }

        aceDescriptorQueryParameter(value, "id")?.let { contentId ->
            if (contentId.isNotBlank()) {
                return P2pResult.Success(P2pSource.AceContentId(contentId))
            }
        }

        return when {
            value.startsWith("magnet:?", ignoreCase = true) -> {
                P2pResult.Success(P2pSource.Magnet(value))
            }

            value.startsWith(URN_BTIH_PREFIX, ignoreCase = true) -> {
                val hash = value.substring(URN_BTIH_PREFIX.length).trim()
                normalizeInfoHash(hash)?.let { normalized ->
                    P2pResult.Success(P2pSource.InfoHash(normalized))
                } ?: P2pResult.Error("Invalid BitTorrent infohash")
            }

            value.startsWith("acestream://", ignoreCase = true) ||
                value.startsWith("ace://", ignoreCase = true) -> {
                val id = value.substringAfter("://").trim().substringBefore('?').substringBefore('#')
                if (id.isBlank()) {
                    P2pResult.Error("Ace Stream content id is empty")
                } else {
                    P2pResult.Success(P2pSource.AceContentId(id))
                }
            }

            value.startsWith("infohash:", ignoreCase = true) -> {
                val hash = value.substringAfter(':').trim()
                normalizeInfoHash(hash)?.let { normalized ->
                    P2pResult.Success(P2pSource.InfoHash(normalized))
                } ?: P2pResult.Error("Invalid BitTorrent infohash")
            }

            isInfoHash(value) -> {
                P2pResult.Success(P2pSource.InfoHash(normalizeInfoHash(value)!!))
            }

            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> {
                if (looksLikeTorrentUrl(value)) {
                    P2pResult.Success(P2pSource.TorrentUrl(value))
                } else {
                    P2pResult.Error("HTTP P2P source is not a .torrent URL")
                }
            }

            value.startsWith("content://", ignoreCase = true) ||
                value.startsWith("file://", ignoreCase = true) -> {
                P2pResult.Success(P2pSource.LocalTorrentUri(value))
            }

            else -> P2pResult.Error("Unsupported P2P source")
        }
    }

    fun toMagnetUri(source: P2pSource): String? = when (source) {
        is P2pSource.Magnet -> source.uri
        is P2pSource.InfoHash -> "magnet:?xt=urn:btih:${source.value}"
        else -> null
    }

    /** Returns a direct Ace Live swarm key only when it is carried by explicit Ace syntax. */
    fun parseAceLiveInfoHash(raw: String): String? {
        val value = raw.trim()
        if (!isAceDescriptor(value)) return null
        val hash = aceDescriptorQueryParameter(value, "infohash") ?: return null
        return AceLiveSwarmKey.parseHex(hash)?.toHex()
    }

    private fun parseNestedAceBitTorrentSource(value: String): P2pResult<P2pSource>? {
        val nested = value.trim()
        return when {
            nested.startsWith("magnet:?", ignoreCase = true) -> {
                P2pResult.Success(P2pSource.Magnet(nested))
            }

            nested.startsWith("http://", ignoreCase = true) ||
                nested.startsWith("https://", ignoreCase = true) -> {
                if (looksLikeTorrentUrl(nested)) {
                    P2pResult.Success(P2pSource.TorrentUrl(nested))
                } else {
                    null
                }
            }

            nested.startsWith("content://", ignoreCase = true) ||
                nested.startsWith("file://", ignoreCase = true) -> {
                P2pResult.Success(P2pSource.LocalTorrentUri(nested))
            }

            nested.startsWith("/") && nested.endsWith(".torrent", ignoreCase = true) -> {
                P2pResult.Success(P2pSource.LocalTorrentUri("file://$nested"))
            }

            else -> null
        }
    }

    /**
     * Accept query parameters only from explicit Ace descriptors. Besides `ace:`/`acestream:` this
     * includes the legacy loopback HTTP shape emitted by many Torrent TV playlists. The loopback
     * URL is treated as descriptor syntax, not as a requirement that another process owns port 6878.
     */
    private fun aceDescriptorQueryParameter(value: String, name: String): String? {
        if (!isAceDescriptor(value)) return null

        val query = value.substringAfter('?', missingDelimiterValue = "")
        if (query.isBlank()) return null

        return query.split('&').firstNotNullOfOrNull { part ->
            val key = decodeQueryValue(part.substringBefore('=', missingDelimiterValue = part).trim())
            if (!key.equals(name, ignoreCase = true)) {
                null
            } else {
                decodeQueryValue(part.substringAfter('=', missingDelimiterValue = "").trim())
            }
        }
    }

    private fun isAceDescriptor(value: String): Boolean =
        value.startsWith("acestream:", ignoreCase = true) ||
            value.startsWith("ace:", ignoreCase = true) ||
            isLoopbackAceGatewayUrl(value)

    private fun isLoopbackAceGatewayUrl(value: String): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true) &&
            !uri.scheme.equals("https", ignoreCase = true)
        ) {
            return false
        }

        val host = uri.host
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?: return false
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        return loopback && uri.path.orEmpty().startsWith("/ace/", ignoreCase = true)
    }

    private fun decodeQueryValue(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun isInfoHash(value: String): Boolean =
        sha1InfoHashHex.matches(value) || sha1InfoHashBase32.matches(value)

    private fun normalizeInfoHash(value: String): String? = when {
        sha1InfoHashHex.matches(value) -> value.lowercase(Locale.ROOT)
        sha1InfoHashBase32.matches(value) -> value.uppercase(Locale.ROOT)
        else -> null
    }

    private fun looksLikeTorrentUrl(value: String): Boolean {
        val withoutFragment = value.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        if (path.endsWith(".torrent", ignoreCase = true)) return true

        val query = withoutFragment.substringAfter('?', missingDelimiterValue = "")
        return query.contains(".torrent", ignoreCase = true) ||
            query.contains("torrent=", ignoreCase = true)
    }
}
