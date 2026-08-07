package com.iptv.tv.core.engine.data

internal object AceTransportMetadataParser {
    private val bitTorrentInfoHash = Regex("^[a-fA-F0-9]{40}$")

    fun parse(response: Map<String, Any?>): AceTransportMetadata {
        val root = unwrap(response)
        val files = (root["files"] as? Iterable<*>)
            ?.mapNotNull { parseFile(it as? Map<*, *>) }
            .orEmpty()

        return AceTransportMetadata(
            infoHash = normalizeInfoHash(root["infohash"]),
            mediaType = normalizeString(root["type"]) ?: files.firstNotNullOfOrNull { it.mediaType },
            transportType = normalizeString(root["transport_type"])
                ?: files.firstNotNullOfOrNull { it.transportType },
            name = stringValue(root["name"]),
            files = files,
            transportFileData = stringValue(root["transport_file_data"]),
            transportFileCacheKey = stringValue(root["transport_file_cache_key"])
        )
    }

    private fun unwrap(response: Map<String, Any?>): Map<String, Any?> {
        val nested = response["result"] ?: response["response"]
        return if (nested is Map<*, *>) {
            nested.entries
                .filter { it.key is String }
                .associate { it.key as String to it.value }
        } else {
            response
        }
    }

    private fun parseFile(map: Map<*, *>?): AceTransportFile? {
        if (map == null) return null
        return AceTransportFile(
            index = intValue(map["index"]),
            infoHash = normalizeInfoHash(map["infohash"]),
            mediaType = normalizeString(map["type"]),
            transportType = normalizeString(map["transport_type"]),
            filename = stringValue(map["filename"]),
            mime = stringValue(map["mime"]),
            size = longValue(map["size"])
        )
    }

    private fun normalizeInfoHash(value: Any?): String? = stringValue(value)
        ?.trim()
        ?.lowercase()
        ?.takeIf(bitTorrentInfoHash::matches)

    private fun normalizeString(value: Any?): String? = stringValue(value)
        ?.trim()
        ?.lowercase()

    private fun stringValue(value: Any?): String? = (value as? String)?.takeIf { it.isNotBlank() }

    private fun intValue(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun longValue(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}
