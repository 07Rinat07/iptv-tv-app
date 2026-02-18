package com.iptv.tv.core.parser

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth

class M3uParser {
    fun parse(playlistId: Long, raw: String): ParseResult {
        if (!raw.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
            return ParseResult.Invalid("Missing #EXTM3U header")
        }

        val lines = raw.lineSequence().map { it.trim() }.toList()
        val channels = mutableListOf<Channel>()
        val warnings = mutableListOf<String>()
        var currentMeta: ExtInfMeta? = null
        var index = 0

        for ((lineIndex, line) in lines.withIndex()) {
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> currentMeta = parseMeta(line)
                line.startsWith("#") || line.isBlank() -> Unit
                isLikelyStreamUrl(line) -> {
                    val meta = currentMeta
                    if (meta != null) {
                        channels += Channel(
                            id = 0,
                            playlistId = playlistId,
                            tvgId = meta.tvgId,
                            name = meta.name,
                            group = meta.group,
                            logo = meta.logo,
                            streamUrl = line,
                            health = ChannelHealth.UNKNOWN,
                            orderIndex = index,
                            isHidden = false
                        )
                        index += 1
                    } else {
                        warnings += "Line ${lineIndex + 1}: URL without #EXTINF skipped"
                    }
                    currentMeta = null
                }
                else -> {
                    if (currentMeta != null) {
                        warnings += "Line ${lineIndex + 1}: invalid URL for channel '${currentMeta.name}'"
                        currentMeta = null
                    }
                }
            }
        }

        if (currentMeta != null) {
            warnings += "Playlist ends with #EXTINF entry without URL"
        }

        if (channels.isEmpty()) {
            return ParseResult.Invalid("No valid channels found")
        }

        return ParseResult.Valid(channels = channels, warnings = warnings)
    }

    private fun parseMeta(extInf: String): ExtInfMeta {
        val payload = extInf.removePrefix("#EXTINF:")
        val title = payload.substringAfter(',').ifBlank { "Unknown" }
        fun attr(name: String): String? =
            Regex("$name=\"([^\"]+)\"").find(extInf)?.groupValues?.getOrNull(1)

        return ExtInfMeta(
            tvgId = attr("tvg-id"),
            group = attr("group-title"),
            logo = attr("tvg-logo"),
            name = title
        )
    }

    private fun isLikelyStreamUrl(raw: String): Boolean {
        val normalized = raw.trim()
        if (normalized.isBlank()) return false
        if (normalized.contains(' ')) return false

        val scheme = normalized.substringBefore(':').lowercase()
        return scheme in setOf(
            "http",
            "https",
            "rtsp",
            "rtmp",
            "udp",
            "mms",
            "file",
            "content",
            "magnet",
            "acestream",
            "sop"
        )
    }
}

data class ExtInfMeta(
    val tvgId: String?,
    val group: String?,
    val logo: String?,
    val name: String
)

sealed interface ParseResult {
    data class Valid(
        val channels: List<Channel>,
        val warnings: List<String>
    ) : ParseResult

    data class Invalid(val reason: String) : ParseResult
}
