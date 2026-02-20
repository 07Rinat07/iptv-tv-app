package com.iptv.tv.core.parser

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth

class M3uParser {
    fun parse(playlistId: Long, raw: String): ParseResult {
        val sanitizedRaw = raw.removePrefix(UTF8_BOM)
        val normalizedStart = sanitizedRaw.trimStart()
        val headerMissing = !normalizedStart.startsWith("#EXTM3U", ignoreCase = true)
        val canRecoverMissingHeader = headerMissing && looksLikeHeaderlessPlaylist(sanitizedRaw)
        if (headerMissing && !canRecoverMissingHeader) {
            return ParseResult.Invalid("Missing #EXTM3U header")
        }

        val rawToParse = if (canRecoverMissingHeader) {
            "#EXTM3U\n$sanitizedRaw"
        } else {
            sanitizedRaw
        }

        val lines = rawToParse.lineSequence().map { it.trim() }.toList()
        val epgUrls = parseHeaderEpgUrls(lines)
        val channels = mutableListOf<Channel>()
        val warnings = mutableListOf<String>()
        if (canRecoverMissingHeader) {
            warnings += "Missing #EXTM3U header; auto-added"
        }
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

        return ParseResult.Valid(
            channels = channels,
            warnings = warnings,
            epgUrls = epgUrls
        )
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

    private fun parseHeaderEpgUrls(lines: List<String>): List<String> {
        val attrNames = listOf("url-tvg", "x-tvg-url", "tvg-url")
        return lines
            .asSequence()
            .filter { it.startsWith("#EXTM3U", ignoreCase = true) }
            .flatMap { line ->
                attrNames.asSequence().mapNotNull { attr ->
                    Regex("$attr=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                        .find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
            }
            .distinct()
            .toList()
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

    private fun looksLikeHeaderlessPlaylist(raw: String): Boolean {
        val lines = raw.lineSequence().map { it.trim() }
        val hasExtInf = lines.any { line -> line.startsWith("#EXTINF", ignoreCase = true) }
        val hasStreamUrl = lines.any { line ->
            line.isNotBlank() &&
                !line.startsWith("#") &&
                isLikelyStreamUrl(line)
        }
        return hasExtInf && hasStreamUrl
    }

    private companion object {
        const val UTF8_BOM = "\uFEFF"
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
        val warnings: List<String>,
        val epgUrls: List<String> = emptyList()
    ) : ParseResult

    data class Invalid(val reason: String) : ParseResult
}
