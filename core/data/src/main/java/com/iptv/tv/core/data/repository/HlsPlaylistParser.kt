package com.iptv.tv.core.data.repository

import okhttp3.HttpUrl.Companion.toHttpUrl

internal object HlsPlaylistParser {
    sealed interface Manifest {
        data class Master(
            val variants: List<VariantStream>
        ) : Manifest

        data class Media(
            val segments: List<String>,
            val targetDurationSeconds: Long?,
            val endList: Boolean,
            val encrypted: Boolean,
            val discontinuityCount: Int
        ) : Manifest
    }

    data class VariantStream(
        val url: String,
        val bandwidth: Int?
    )

    fun parse(url: String, content: String): Manifest {
        val lines = content.lines()
        val variants = mutableListOf<VariantStream>()
        val segments = mutableListOf<String>()
        var targetDurationSeconds: Long? = null
        var endList = false
        var encrypted = false
        var discontinuityCount = 0
        var pendingBandwidth: Int? = null

        lines.forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            when {
                line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) -> {
                    pendingBandwidth = parseBandwidth(line.substringAfter(':', ""))
                }

                line.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) -> {
                    targetDurationSeconds = line.substringAfter(':', "")
                        .trim()
                        .toLongOrNull()
                }

                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    endList = true
                }

                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val method = parseAttributeValue(line.substringAfter(':', ""), "METHOD")
                    if (!method.equals("NONE", ignoreCase = true)) {
                        encrypted = true
                    }
                }

                line.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) -> {
                    discontinuityCount += 1
                }

                line.startsWith("#") -> Unit

                pendingBandwidth != null || variants.isNotEmpty() -> {
                    variants += VariantStream(
                        url = resolveUrl(url, line),
                        bandwidth = pendingBandwidth
                    )
                    pendingBandwidth = null
                }

                else -> {
                    segments += resolveUrl(url, line)
                }
            }
        }

        return if (variants.isNotEmpty()) {
            Manifest.Master(variants = variants.toList())
        } else {
            Manifest.Media(
                segments = segments.toList(),
                targetDurationSeconds = targetDurationSeconds,
                endList = endList,
                encrypted = encrypted,
                discontinuityCount = discontinuityCount
            )
        }
    }

    fun selectPreferredVariant(manifest: Manifest.Master): String? {
        return manifest.variants
            .maxWithOrNull(
                compareBy<VariantStream> { it.bandwidth ?: Int.MIN_VALUE }
                    .thenBy { it.url }
            )
            ?.url
    }

    private fun resolveUrl(baseUrl: String, value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val base = runCatching { baseUrl.toHttpUrl() }.getOrNull() ?: return trimmed
        return base.resolve(trimmed)?.toString() ?: trimmed
    }

    private fun parseBandwidth(raw: String): Int? {
        return parseAttributeValue(raw, "BANDWIDTH")?.toIntOrNull()
    }

    private fun parseAttributeValue(raw: String, key: String): String? {
        return raw.split(',')
            .mapNotNull { entry ->
                val pair = entry.split('=', limit = 2)
                if (pair.size != 2) return@mapNotNull null
                pair[0].trim() to pair[1].trim().trim('"')
            }
            .firstOrNull { (name, _) -> name.equals(key, ignoreCase = true) }
            ?.second
    }
}
