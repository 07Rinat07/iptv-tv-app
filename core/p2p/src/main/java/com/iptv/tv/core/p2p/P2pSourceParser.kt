package com.iptv.tv.core.p2p

import java.util.Locale

object P2pSourceParser {
    private val sha1InfoHashHex = Regex("^[0-9a-fA-F]{40}$")
    private val sha1InfoHashBase32 = Regex("^[A-Za-z2-7]{32}$")

    fun parse(raw: String): P2pResult<P2pSource> {
        val value = raw.trim()
        if (value.isBlank()) return P2pResult.Error("P2P source is empty")

        return when {
            value.startsWith("magnet:?", ignoreCase = true) -> {
                P2pResult.Success(P2pSource.Magnet(value))
            }

            value.startsWith("acestream://", ignoreCase = true) -> {
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
