package com.iptv.tv.core.model

import java.net.URI
import java.util.Locale

/**
 * Provenance-agnostic logical identity for one TV channel across imported playlists.
 *
 * This key is intentionally different from [CatalogNodeId]: the same logical channel may have
 * several canonical nodes under different sources/playlists, while Favorites and deduplication
 * still need to recognize it as one channel. Existing Favorites semantics are preserved:
 * tvg-id first, normalized display name second, normalized stream URL only as a final fallback.
 */
object ChannelStableIdentity {
    fun key(tvgId: String?, name: String, streamUrl: String): String {
        normalizeToken(tvgId).takeIf { it.isNotBlank() }?.let { return "tvg:$it" }
        normalizeName(name).takeIf { it.isNotBlank() }?.let { return "name:$it" }
        return "url:${normalizeUrl(streamUrl)}"
    }

    fun normalizeName(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("""[\p{Punct}\s]+"""), " ")
        .replace(Regex("""\b(hd|fhd|uhd|4k|1080p|720p)\b"""), "")
        .trim()
        .replace(Regex("""\s+"""), " ")

    private fun normalizeToken(value: String?): String = value
        .orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("""\s+"""), "")

    private fun normalizeUrl(value: String): String {
        val trimmed = value.trim()
        return runCatching {
            val uri = URI(trimmed)
            buildString {
                append(uri.scheme.orEmpty().lowercase(Locale.ROOT))
                append("://")
                append(uri.host.orEmpty().lowercase(Locale.ROOT))
                append(uri.path.orEmpty().trimEnd('/'))
            }
        }.getOrDefault(trimmed.substringBefore('?').lowercase(Locale.ROOT))
    }
}
