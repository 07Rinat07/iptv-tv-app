package com.iptv.tv.core.p2p

import java.net.URI
import java.nio.charset.StandardCharsets

/** Internal transport-hint prefixes carried through the legacy tracker list into discovery. */
internal const val ACE_LIVE_STARTUP_HINT_PREFIX = "ace-startup:"
internal const val ACE_LIVE_METATRACKER_HINT_PREFIX = "ace-metatracker:"

internal data class AceLiveMetatrackerSnapshot(
    val trackers: List<String>,
    val startupNodes: List<String>,
    val intervalSeconds: Long?
)

/**
 * Pure protocol helpers for the discovery paths that use HTTP.
 *
 * This object performs no network I/O. It keeps binary tracker query encoding, bounded bencoded
 * tracker-response decoding and the small Ace metatracker JSON contract testable on the JVM.
 */
internal object AceLiveDiscoveryHttpProtocol {
    fun encodeStartupHint(rawAddress: String): String? {
        val endpoint = parseHostPort(rawAddress) ?: return null
        return ACE_LIVE_STARTUP_HINT_PREFIX + endpoint.host + ":" + endpoint.port
    }

    fun parseStartupHint(raw: String): AceLiveTcpPeerEndpoint? {
        if (!raw.startsWith(ACE_LIVE_STARTUP_HINT_PREFIX, ignoreCase = true)) return null
        return parseHostPort(raw.substring(ACE_LIVE_STARTUP_HINT_PREFIX.length))
    }

    fun encodeMetatrackerHint(rawUrl: String): String? {
        val normalized = normalizedHttpUrl(rawUrl) ?: return null
        return ACE_LIVE_METATRACKER_HINT_PREFIX + normalized
    }

    fun parseMetatrackerHint(raw: String): String? {
        if (!raw.startsWith(ACE_LIVE_METATRACKER_HINT_PREFIX, ignoreCase = true)) return null
        return normalizedHttpUrl(raw.substring(ACE_LIVE_METATRACKER_HINT_PREFIX.length))
    }

    fun isHttpTracker(raw: String): Boolean = normalizedHttpUrl(raw) != null

    fun httpHost(raw: String): String? =
        normalizedHttpUri(raw)?.host?.trim()?.takeIf(String::isNotEmpty)

    fun buildHttpTrackerAnnounceUrl(
        rawUrl: String,
        swarmKey: AceLiveSwarmKey,
        peerId: ByteArray,
        announcePort: Int,
        key: Int,
        numWant: Int
    ): String? {
        val normalized = normalizedHttpUrl(rawUrl) ?: return null
        require(peerId.size == AceLiveUdpTrackerCodec.PEER_ID_BYTES) {
            "peerId must be exactly ${AceLiveUdpTrackerCodec.PEER_ID_BYTES} bytes"
        }
        require(announcePort in 1..65535) { "announcePort must be in 1..65535" }
        require(numWant in 1..AceLiveUdpTrackerCodec.MAX_NUM_WANT) {
            "numWant must be in 1..${AceLiveUdpTrackerCodec.MAX_NUM_WANT}"
        }
        return appendQuery(
            normalized,
            listOf(
                "info_hash=" + percentEncodeBinary(swarmKey.toByteArray()),
                "peer_id=" + percentEncodeBinary(peerId),
                "port=$announcePort",
                "uploaded=0",
                "downloaded=0",
                "left=${Long.MAX_VALUE}",
                "compact=1",
                "event=started",
                "key=${key.toUInt().toString(16)}",
                "numwant=$numWant"
            )
        )
    }

    fun decodeHttpTrackerResponse(
        bytes: ByteArray,
        maxPeers: Int,
        maxResponseBytes: Int
    ): List<AceLiveTcpPeerEndpoint> {
        require(maxPeers in 1..AceLiveUdpTrackerCodec.MAX_PARSED_PEERS) {
            "maxPeers must be in 1..${AceLiveUdpTrackerCodec.MAX_PARSED_PEERS}"
        }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
        if (bytes.size > maxResponseBytes) {
            throw AceLiveTrackerProtocolException("HTTP tracker response exceeds local byte cap")
        }
        val root = try {
            AceBoundedBencodeParser(
                data = bytes,
                maxDepth = 8,
                maxContainerEntries = maxOf(128, maxPeers + 16),
                maxStringBytes = maxResponseBytes,
                maxTotalNodes = maxOf(1_024, maxPeers * 8)
            ).parseRootDictionary()
        } catch (error: Throwable) {
            throw AceLiveTrackerProtocolException(
                "HTTP tracker returned malformed bencode: ${error.message ?: error.javaClass.simpleName}"
            )
        }
        (root.values["failure reason"] as? AceBencodeValue.Bytes)?.value?.let { reason ->
            val message = reason.toString(StandardCharsets.UTF_8).take(256)
            throw AceLiveTrackerProtocolException("HTTP tracker rejected announce: $message")
        }
        val peersValue = root.values["peers"] ?: return emptyList()
        return when (peersValue) {
            is AceBencodeValue.Bytes -> decodeCompactIpv4Peers(peersValue.value, maxPeers)
            is AceBencodeValue.ListValue -> decodeExpandedPeers(peersValue.values, maxPeers)
            else -> throw AceLiveTrackerProtocolException("HTTP tracker peers field has invalid type")
        }
    }

    fun buildMetatrackerRequestUrl(rawUrl: String, swarmKey: AceLiveSwarmKey): String? {
        val normalized = normalizedHttpUrl(rawUrl) ?: return null
        return appendQuery(normalized, listOf("infohash=${swarmKey.toHex()}"))
    }

    fun decodeMetatrackerResponse(
        bytes: ByteArray,
        maxEntries: Int = 64,
        maxStringLength: Int = 2_048
    ): AceLiveMetatrackerSnapshot {
        require(maxEntries in 1..512) { "maxEntries must be in 1..512" }
        require(maxStringLength in 16..16_384) { "maxStringLength is out of range" }
        val text = bytes.toString(StandardCharsets.UTF_8)
        return MetatrackerJsonParser(
            text = text,
            maxEntries = maxEntries,
            maxStringLength = maxStringLength
        ).parse()
    }

    private fun decodeCompactIpv4Peers(
        bytes: ByteArray,
        maxPeers: Int
    ): List<AceLiveTcpPeerEndpoint> {
        if (bytes.size % AceLiveUdpTrackerCodec.COMPACT_IPV4_PEER_BYTES != 0) {
            throw AceLiveTrackerProtocolException("Malformed HTTP tracker compact peer list")
        }
        val count = bytes.size / AceLiveUdpTrackerCodec.COMPACT_IPV4_PEER_BYTES
        if (count > maxPeers) {
            throw AceLiveTrackerProtocolException("HTTP tracker peer count exceeds local cap")
        }
        return buildList(count) {
            var offset = 0
            repeat(count) {
                val a = bytes[offset++].toInt() and 0xff
                val b = bytes[offset++].toInt() and 0xff
                val c = bytes[offset++].toInt() and 0xff
                val d = bytes[offset++].toInt() and 0xff
                val port = ((bytes[offset++].toInt() and 0xff) shl 8) or
                    (bytes[offset++].toInt() and 0xff)
                if (port == 0) {
                    throw AceLiveTrackerProtocolException("HTTP tracker peer advertises port 0")
                }
                add(AceLiveTcpPeerEndpoint("$a.$b.$c.$d", port))
            }
        }
    }

    private fun decodeExpandedPeers(
        values: List<AceBencodeValue>,
        maxPeers: Int
    ): List<AceLiveTcpPeerEndpoint> {
        if (values.size > maxPeers) {
            throw AceLiveTrackerProtocolException("HTTP tracker peer count exceeds local cap")
        }
        return values.mapNotNull { value ->
            val dictionary = value as? AceBencodeValue.Dictionary ?: return@mapNotNull null
            val hostBytes = (dictionary.values["ip"] as? AceBencodeValue.Bytes)?.value
                ?: return@mapNotNull null
            val host = hostBytes.toString(StandardCharsets.US_ASCII).trim()
            val port = (dictionary.values["port"] as? AceBencodeValue.Integer)?.value
                ?.takeIf { it in 1L..65535L }
                ?.toInt()
                ?: return@mapNotNull null
            if (host.isBlank() || host.length > 253) return@mapNotNull null
            AceLiveTcpPeerEndpoint(host, port)
        }
    }

    private fun normalizedHttpUrl(raw: String): String? {
        val trimmed = raw.trim()
        val uri = normalizedHttpUri(trimmed) ?: return null
        return uri.toASCIIString()
    }

    private fun normalizedHttpUri(raw: String): URI? {
        val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return null
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (uri.host.isNullOrBlank()) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null
        return uri
    }

    private fun parseHostPort(raw: String): AceLiveTcpPeerEndpoint? {
        val value = raw.trim()
        val separator = value.lastIndexOf(':')
        if (separator <= 0 || separator == value.lastIndex) return null
        val host = value.substring(0, separator).trim()
        val port = value.substring(separator + 1).trim().toIntOrNull() ?: return null
        // Ace startup-node documentation defines IPv4-address:port. Keep IPv6 out until its exact
        // transport representation is independently verified instead of guessing at bracket rules.
        if (host.isBlank() || host.length > 253 || ':' in host || port !in 1..65535) return null
        return runCatching { AceLiveTcpPeerEndpoint(host, port) }.getOrNull()
    }

    private fun percentEncodeBinary(bytes: ByteArray): String = buildString(bytes.size * 3) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xff
            append('%')
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun appendQuery(url: String, parameters: List<String>): String {
        val separator = when {
            '?' !in url -> "?"
            url.endsWith('?') || url.endsWith('&') -> ""
            else -> "&"
        }
        return url + separator + parameters.joinToString("&")
    }

    private const val HEX = "0123456789ABCDEF"
}

private class MetatrackerJsonParser(
    private val text: String,
    private val maxEntries: Int,
    private val maxStringLength: Int
) {
    private var index = 0

    fun parse(): AceLiveMetatrackerSnapshot {
        require(text.length <= MAX_JSON_CHARS) { "metatracker JSON exceeds local character cap" }
        skipWhitespace()
        expect('{')
        var trackers: List<String> = emptyList()
        var startupNodes: List<String> = emptyList()
        var interval: Long? = null
        var first = true
        var fields = 0
        while (true) {
            skipWhitespace()
            if (consume('}')) break
            if (!first) {
                expect(',')
                skipWhitespace()
            }
            first = false
            require(fields++ < MAX_OBJECT_FIELDS) { "metatracker JSON has too many fields" }
            val key = parseString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            when (key) {
                "trackers" -> trackers = parseStringArray()
                "startup_nodes" -> startupNodes = parseStringArray()
                "interval" -> interval = parseInteger().takeIf { it >= 0L }
                else -> skipValue(depth = 0)
            }
        }
        skipWhitespace()
        require(index == text.length) { "trailing metatracker JSON data" }
        return AceLiveMetatrackerSnapshot(
            trackers = trackers
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(maxEntries)
                .toList(),
            startupNodes = startupNodes
                .asSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(maxEntries)
                .toList(),
            intervalSeconds = interval
        )
    }

    private fun parseStringArray(): List<String> {
        expect('[')
        val values = ArrayList<String>()
        var first = true
        while (true) {
            skipWhitespace()
            if (consume(']')) break
            if (!first) {
                expect(',')
                skipWhitespace()
            }
            first = false
            require(values.size < maxEntries) { "metatracker array exceeds local entry cap" }
            values += parseString()
        }
        return values
    }

    private fun parseString(): String {
        expect('"')
        val output = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when (char) {
                '"' -> {
                    require(output.length <= maxStringLength) {
                        "metatracker JSON string exceeds local cap"
                    }
                    return output.toString()
                }
                '\\' -> {
                    require(index < text.length) { "truncated JSON escape" }
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> output.append(escaped)
                        'b' -> output.append('\b')
                        'f' -> output.append('\u000c')
                        'n' -> output.append('\n')
                        'r' -> output.append('\r')
                        't' -> output.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length) { "truncated unicode escape" }
                            val code = text.substring(index, index + 4).toIntOrNull(16)
                                ?: error("invalid unicode escape")
                            output.append(code.toChar())
                            index += 4
                        }
                        else -> error("invalid JSON escape: $escaped")
                    }
                }
                else -> {
                    require(char.code >= 0x20) { "control character in JSON string" }
                    output.append(char)
                }
            }
            require(output.length <= maxStringLength) {
                "metatracker JSON string exceeds local cap"
            }
        }
        error("unterminated JSON string")
    }

    private fun parseInteger(): Long {
        val start = index
        if (consume('-')) Unit
        val digitsStart = index
        while (index < text.length && text[index].isDigit()) index += 1
        require(index > digitsStart) { "invalid JSON integer" }
        return text.substring(start, index).toLongOrNull() ?: error("JSON integer overflow")
    }

    private fun skipValue(depth: Int) {
        require(depth <= MAX_DEPTH) { "metatracker JSON nesting too deep" }
        skipWhitespace()
        require(index < text.length) { "missing JSON value" }
        when (text[index]) {
            '"' -> parseString()
            '{' -> {
                index += 1
                var first = true
                var fields = 0
                while (true) {
                    skipWhitespace()
                    if (consume('}')) break
                    if (!first) {
                        expect(',')
                        skipWhitespace()
                    }
                    first = false
                    require(fields++ < MAX_OBJECT_FIELDS) { "JSON object exceeds local field cap" }
                    parseString()
                    skipWhitespace()
                    expect(':')
                    skipValue(depth + 1)
                }
            }
            '[' -> {
                index += 1
                var first = true
                var values = 0
                while (true) {
                    skipWhitespace()
                    if (consume(']')) break
                    if (!first) {
                        expect(',')
                        skipWhitespace()
                    }
                    first = false
                    require(values++ < maxEntries) { "JSON array exceeds local entry cap" }
                    skipValue(depth + 1)
                }
            }
            't' -> expectLiteral("true")
            'f' -> expectLiteral("false")
            'n' -> expectLiteral("null")
            else -> skipNumber()
        }
    }

    private fun skipNumber() {
        val start = index
        if (consume('-')) Unit
        while (index < text.length && text[index].isDigit()) index += 1
        if (consume('.')) {
            while (index < text.length && text[index].isDigit()) index += 1
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index += 1
            while (index < text.length && text[index].isDigit()) index += 1
        }
        require(index > start) { "invalid JSON value" }
    }

    private fun expectLiteral(value: String) {
        require(text.regionMatches(index, value, 0, value.length)) { "invalid JSON literal" }
        index += value.length
    }

    private fun expect(expected: Char) {
        require(index < text.length && text[index] == expected) { "expected '$expected' in JSON" }
        index += 1
    }

    private fun consume(expected: Char): Boolean {
        if (index >= text.length || text[index] != expected) return false
        index += 1
        return true
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }

    private companion object {
        const val MAX_JSON_CHARS = 64 * 1024
        const val MAX_OBJECT_FIELDS = 64
        const val MAX_DEPTH = 8
    }
}
