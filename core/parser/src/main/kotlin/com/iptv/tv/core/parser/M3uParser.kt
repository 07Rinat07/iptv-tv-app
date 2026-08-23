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
        val catchUpByChannelOrderIndex = linkedMapOf<Int, ChannelCatchUpMetadata>()
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
                            isHidden = false,
                            catchUp = meta.catchUp
                        )
                        meta.catchUp?.let { catchUpByChannelOrderIndex[index] = it }
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
            epgUrls = epgUrls,
            catchUpByChannelOrderIndex = catchUpByChannelOrderIndex
        )
    }

    private fun parseMeta(extInf: String): ExtInfMeta {
        val payload = extInf.removePrefix("#EXTINF:")
        val titleSeparator = findExtInfTitleSeparator(payload)
        val attributeSection = if (titleSeparator >= 0) payload.substring(0, titleSeparator) else payload
        val title = if (titleSeparator >= 0) {
            payload.substring(titleSeparator + 1).ifBlank { "Unknown" }
        } else {
            "Unknown"
        }
        val attributes = parseExtInfAttributes(attributeSection)

        val catchUpMode = attributes["catchup"]
        val rawCatchUpDays = attributes["catchup-days"]
        val catchUpDays = rawCatchUpDays?.toIntOrNull()?.takeIf { it > 0 }
        val catchUpSourceTemplate = attributes["catchup-source"]
        val catchUp = if (catchUpMode != null || rawCatchUpDays != null || catchUpSourceTemplate != null) {
            ChannelCatchUpMetadata(
                mode = catchUpMode,
                days = catchUpDays,
                sourceTemplate = catchUpSourceTemplate,
                daysDeclared = rawCatchUpDays != null
            )
        } else {
            null
        }

        return ExtInfMeta(
            tvgId = attributes["tvg-id"],
            group = attributes["group-title"],
            logo = attributes["tvg-logo"],
            name = title,
            catchUp = catchUp
        )
    }

    private fun findExtInfTitleSeparator(payload: String): Int {
        var quote: Char? = null
        var escaped = false
        for (index in payload.indices) {
            val char = payload[index]
            if (escaped) {
                escaped = false
                continue
            }
            if (quote != null && char == '\\') {
                escaped = true
                continue
            }
            if (quote != null) {
                if (char == quote) quote = null
                continue
            }
            if (char == '"' || char == '\'') {
                quote = char
            } else if (char == ',') {
                return index
            }
        }
        return -1
    }

    private fun parseExtInfAttributes(attributeSection: String): Map<String, String> =
        EXTINF_ATTRIBUTE_REGEX.findAll(attributeSection).associate { match ->
            val name = match.groupValues[1].lowercase()
            val value = match.groupValues
                .drop(2)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
                .trim()
            name to value
        }

    private fun parseHeaderEpgUrls(lines: List<String>): List<String> {
        val attributeRegex = Regex(
            pattern = """\b(?:url-tvg|x-tvg-url|tvg-url)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""",
            option = RegexOption.IGNORE_CASE
        )
        val httpUrlRegex = Regex("""https?://[^,;\s"']+""", RegexOption.IGNORE_CASE)
        return lines
            .asSequence()
            .filter { it.startsWith("#EXTM3U", ignoreCase = true) }
            .flatMap { line ->
                attributeRegex.findAll(line).flatMap { match ->
                    val rawValue = match.groupValues
                        .drop(1)
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                        .trim()
                    val urls = httpUrlRegex.findAll(rawValue).map { it.value.trim() }.toList()
                    if (urls.isNotEmpty()) urls.asSequence() else rawValue
                        .split(',', ';')
                        .asSequence()
                        .map(String::trim)
                        .filter { value -> value.startsWith("http://", true) || value.startsWith("https://", true) }
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
        val EXTINF_ATTRIBUTE_REGEX = Regex(
            pattern = """(?:^|\s)([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""",
            option = RegexOption.IGNORE_CASE
        )
    }
}

typealias ChannelCatchUpMetadata = com.iptv.tv.core.model.ChannelCatchUpMetadata

data class ExtInfMeta(
    val tvgId: String?,
    val group: String?,
    val logo: String?,
    val name: String,
    val catchUp: ChannelCatchUpMetadata? = null
)

sealed interface ParseResult {
    data class Valid(
        val channels: List<Channel>,
        val warnings: List<String>,
        val epgUrls: List<String> = emptyList(),
        val catchUpByChannelOrderIndex: Map<Int, ChannelCatchUpMetadata> = emptyMap()
    ) : ParseResult

    data class Invalid(val reason: String) : ParseResult
}
