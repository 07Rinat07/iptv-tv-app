package com.iptv.tv.core.data.repository

import java.net.URI
import java.util.Locale

/**
 * Stable identity used to keep one logical channel favorite across playlists.
 * tvg-id is preferred; when providers omit it, a normalized channel name is used.
 */
internal object GlobalFavoriteIdentity {
    fun key(tvgId: String?, name: String, streamUrl: String): String {
        normalizeToken(tvgId).takeIf { it.isNotBlank() }?.let { return "tvg:$it" }
        normalizeName(name).takeIf { it.isNotBlank() }?.let { return "name:$it" }
        return "url:${normalizeUrl(streamUrl)}"
    }

    internal fun normalizeName(value: String): String = value
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
