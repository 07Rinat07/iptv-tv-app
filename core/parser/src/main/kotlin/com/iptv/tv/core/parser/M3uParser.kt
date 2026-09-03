package com.iptv.tv.core.parser

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import java.io.BufferedReader
import java.io.Reader
import java.io.StringReader

class M3uParser {
    fun parse(playlistId: Long, raw: String): ParseResult =
        parse(playlistId = playlistId, reader = StringReader(raw))

    fun parse(playlistId: Long, reader: Reader): ParseResult {
        val bufferedReader = if (reader is BufferedReader) reader else BufferedReader(reader)
        val epgUrls = linkedSetOf<String>()
        val channels = mutableListOf<Channel>()
        val catchUpByChannelOrderIndex = linkedMapOf<Int, ChannelCatchUpMetadata>()
        val parseWarnings = mutableListOf<String>()
        var currentMeta: ExtInfMeta? = null
        var channelIndex = 0
        var sourceLineNumber = 0
        var headerMissing: Boolean? = null
        var hasExtInf = false
        var hasStreamUrl = false

        while (true) {
            val rawLine = bufferedReader.readLine() ?: break
            sourceLineNumber += 1
            val lineWithoutBom = if (sourceLineNumber == 1) rawLine.removePrefix(UTF8_BOM) else rawLine
            val line = lineWithoutBom.trim()

            if (headerMissing == null && line.isNotBlank()) {
                headerMissing = !line.startsWith("#EXTM3U", ignoreCase = true)
            }

            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                hasExtInf = true
            }
            if (line.isNotBlank() && !line.startsWith("#") && isLikelyStreamUrl(line)) {
                hasStreamUrl = true
            }
            if (line.startsWith("#EXTM3U", ignoreCase = true)) {
                epgUrls += parseHeaderEpgUrls(line)
            }

            val effectiveLineNumber = sourceLineNumber + if (headerMissing == true) 1 else 0
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
                            orderIndex = channelIndex,
                            isHidden = false,
                            catchUp = meta.catchUp
                        )
                        meta.catchUp?.let { catchUpByChannelOrderIndex[channelIndex] = it }
                        channelIndex += 1
                    } else {
                        parseWarnings += "Line $effectiveLineNumber: URL without #EXTINF skipped"
                    }
                    currentMeta = null
                }
                else -> {
                    if (currentMeta != null) {
                        parseWarnings += "Line $effectiveLineNumber: invalid URL for channel '${currentMeta.name}'"
                        currentMeta = null
                    }
                }
            }
        }

        val isHeaderMissing = headerMissing != false
        val canRecoverMissingHeader = isHeaderMissing && hasExtInf && hasStreamUrl
        if (isHeaderMissing && !canRecoverMissingHeader) {
            return ParseResult.Invalid("Missing #EXTM3U header")
        }

        val warnings = mutableListOf<String>()
        if (canRecoverMissingHeader) {
            warnings += "Missing #EXTM3U header; auto-added"
        }
        warnings += parseWarnings

        if (currentMeta != null) {
            warnings += "Playlist ends with #EXTINF entry without URL"
        }

        if (channels.isEmpty()) {
            return ParseResult.Invalid("No valid channels found")
        }

        return ParseResult.Valid(
            channels = channels,
            warnings = warnings,
            epgUrls = epgUrls.toList(),
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

    private fun parseHeaderEpgUrls(line: String): List<String> =
        EPG_ATTRIBUTE_REGEX.findAll(line)
            .flatMap { match ->
                val rawValue = match.groupValues
                    .drop(1)
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                    .trim()
                val urls = HTTP_URL_REGEX.findAll(rawValue).map { it.value.trim() }.toList()
                if (urls.isNotEmpty()) {
                    urls.asSequence()
                } else {
                    rawValue
                        .split(',', ';')
                        .asSequence()
                        .map(String::trim)
                        .filter { value -> value.startsWith("http://", true) || value.startsWith("https://", true) }
                }
            }
            .toList()

    private fun isLikelyStreamUrl(raw: String): Boolean {
        val normalized = raw.trim()
        if (normalized.isBlank()) return false
        if (normalized.contains(' ')) return false

        val scheme = normalized.substringBefore(':').lowercase()
        return scheme in STREAM_SCHEMES
    }

    private companion object {
        const val UTF8_BOM = "\uFEFF"
        val STREAM_SCHEMES = setOf(
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
        val EXTINF_ATTRIBUTE_REGEX = Regex(
            pattern = """(?:^|\s)([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""",
            option = RegexOption.IGNORE_CASE
        )
        val EPG_ATTRIBUTE_REGEX = Regex(
            pattern = """\b(?:url-tvg|x-tvg-url|tvg-url)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s]+))""",
            option = RegexOption.IGNORE_CASE
        )
        val HTTP_URL_REGEX = Regex("""https?://[^,;\s"']+""", RegexOption.IGNORE_CASE)
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
