package com.iptv.tv.core.engine.acestream

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

sealed interface AceStreamDescriptor {
    data class ContentId(val value: String) : AceStreamDescriptor
    data class TransportUrl(val value: String) : AceStreamDescriptor
    data class PlayableLocalUrl(val value: String) : AceStreamDescriptor
}

/** Parses playlist values accepted by the Ace Stream HTTP API. */
object AceStreamDescriptorParser {
    private val contentIdRegex = Regex("^[a-fA-F0-9]{40}$")

    fun parse(raw: String): AceStreamDescriptor? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val lowercase = value.lowercase(Locale.US)

        if (isLocalAcePlaybackUrl(lowercase)) {
            return AceStreamDescriptor.PlayableLocalUrl(value)
        }

        if (lowercase.startsWith("acestream://") || lowercase.startsWith("ace://")) {
            val contentId = value.substringAfter("://").trim().trimStart('/').substringBefore('?')
            return contentId.takeIf { it.isNotBlank() }
                ?.let(AceStreamDescriptor::ContentId)
        }

        if (lowercase.startsWith("infohash:")) {
            val contentId = value.substringAfter(':').trim()
            return contentId.takeIf(contentIdRegex::matches)
                ?.let(AceStreamDescriptor::ContentId)
        }

        if (contentIdRegex.matches(value)) {
            return AceStreamDescriptor.ContentId(value)
        }

        if (lowercase.startsWith("magnet:")) {
            return AceStreamDescriptor.TransportUrl(value)
        }

        if (lowercase.startsWith("http://") || lowercase.startsWith("https://")) {
            val path = lowercase.substringBefore('?').substringBefore('#')
            if (path.endsWith(".torrent") || path.endsWith(".acelive")) {
                return AceStreamDescriptor.TransportUrl(value)
            }
        }

        return null
    }

    fun buildPlaybackUrl(endpoint: String, descriptor: AceStreamDescriptor): String {
        if (descriptor is AceStreamDescriptor.PlayableLocalUrl) return descriptor.value

        val base = endpoint.trim().removeSuffix("/")
        val (parameter, value) = when (descriptor) {
            is AceStreamDescriptor.ContentId -> "id" to descriptor.value
            is AceStreamDescriptor.TransportUrl -> "url" to descriptor.value
            is AceStreamDescriptor.PlayableLocalUrl -> return descriptor.value
        }
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        return "$base/ace/getstream?$parameter=$encoded"
    }

    private fun isLocalAcePlaybackUrl(lowercase: String): Boolean {
        val isLocal = lowercase.startsWith("http://127.0.0.1:") ||
            lowercase.startsWith("http://localhost:") ||
            lowercase.startsWith("https://127.0.0.1:") ||
            lowercase.startsWith("https://localhost:")
        return isLocal && (
            lowercase.contains("/ace/getstream") ||
                lowercase.contains("/ace/manifest") ||
                lowercase.contains("/content/")
            )
    }
}
