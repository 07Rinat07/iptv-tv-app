package com.iptv.tv.core.p2p

import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class AceLiveLsdPolicy(
    val receiveTimeoutMillis: Int = 1_000,
    val announceIntervalMillis: Long = 5L * 60_000L,
    val maxDatagramBytes: Int = 1_400,
    val maxUniquePeers: Int = 64
) {
    init {
        require(receiveTimeoutMillis in 100..5_000)
        require(announceIntervalMillis in 60_000L..30L * 60_000L)
        require(maxDatagramBytes in 256..1_400)
        require(maxUniquePeers in 1..256)
    }
}

internal data class AceLiveLsdAnnouncement(
    val port: Int,
    val infoHashes: Set<String>,
    val cookie: String?
) {
    init {
        require(port in 1..65535)
        require(infoHashes.isNotEmpty())
    }
}

/** Minimal BEP-14 wire codec. Parsing stays deliberately stricter than HTTP. */
internal object AceLiveLsdCodec {
    fun encode(swarmKey: AceLiveSwarmKey, port: Int, cookie: String): ByteArray {
        require(port in 1..65535)
        require(cookie.isNotBlank() && cookie.length <= MAX_COOKIE_CHARS)
        return buildString {
            append(REQUEST_LINE).append(CRLF)
            append("Host: ").append(HOST_HEADER_VALUE).append(CRLF)
            append("Port: ").append(port).append(CRLF)
            append("Infohash: ").append(swarmKey.toHex()).append(CRLF)
            append("cookie: ").append(cookie).append(CRLF)
            append(CRLF)
        }.toByteArray(StandardCharsets.US_ASCII).also { bytes ->
            require(bytes.size <= MAX_WIRE_BYTES)
        }
    }

    fun decode(bytes: ByteArray): AceLiveLsdAnnouncement? {
        if (bytes.isEmpty() || bytes.size > MAX_WIRE_BYTES || !bytes.all(::isAllowedAsciiByte)) {
            return null
        }
        val text = bytes.toString(StandardCharsets.US_ASCII)
        if (!text.endsWith("$CRLF$CRLF")) return null
        val lines = text.removeSuffix("$CRLF$CRLF").split(CRLF)
        if (lines.isEmpty() || lines.size > MAX_LINES || lines.first() != REQUEST_LINE) return null

        var host: String? = null
        var port: Int? = null
        var cookie: String? = null
        val infoHashes = linkedSetOf<String>()
        for (line in lines.drop(1)) {
            if (line.length > MAX_HEADER_LINE_CHARS) return null
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val name = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            when (name) {
                "host" -> host = value
                "port" -> port = value.toIntOrNull()?.takeIf { it in 1..65535 }
                "infohash" -> AceLiveSwarmKey.parseHex(value)?.toHex()?.let(infoHashes::add)
                "cookie" -> cookie = value.takeIf { it.isNotBlank() && it.length <= MAX_COOKIE_CHARS }
            }
        }
        if (!host.equals(HOST_HEADER_VALUE, ignoreCase = true)) return null
        val validPort = port ?: return null
        if (infoHashes.isEmpty()) return null
        return AceLiveLsdAnnouncement(validPort, infoHashes, cookie)
    }

    private fun isAllowedAsciiByte(byte: Byte): Boolean {
        val value = byte.toInt() and 0xff
        return value == '\r'.code || value == '\n'.code || value == '\t'.code || value in 0x20..0x7e
    }

    const val MULTICAST_ADDRESS = "239.192.152.143"
    const val MULTICAST_PORT = 6771
    const val HOST_HEADER_VALUE = "$MULTICAST_ADDRESS:$MULTICAST_PORT"
    const val MAX_WIRE_BYTES = 1_400
    private const val REQUEST_LINE = "BT-SEARCH * HTTP/1.1"
    private const val CRLF = "\r\n"
    private const val MAX_LINES = 32
    private const val MAX_HEADER_LINE_CHARS = 512
    private const val MAX_COOKIE_CHARS = 64
}

internal fun aceLiveLsdSameIpv4Prefix(
    localAddress: Inet4Address,
    remoteAddress: Inet4Address,
    prefixLength: Int
): Boolean {
    require(prefixLength in 0..32)
    if (prefixLength == 0) return true
    val local = ipv4AsUnsignedInt(localAddress)
    val remote = ipv4AsUnsignedInt(remoteAddress)
    val mask = (-1L shl (32 - prefixLength)) and 0xffff_ffffL
    return (local and mask) == (remote and mask)
}

private fun ipv4AsUnsignedInt(address: Inet4Address): Long = address.address.fold(0L) { value, octet ->
    (value shl 8) or (octet.toLong() and 0xffL)
}

internal class AceLiveLsdPeerCache(
    private val ttlMillis: Long = 6L * 60_000L,
    private val maxPeers: Int = 64
) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    init {
        require(ttlMillis in 60_000L..30L * 60_000L)
        require(maxPeers in 1..256)
    }

    fun record(endpoint: AceLiveTcpPeerEndpoint, nowMillis: Long) = synchronized(lock) {
        require(nowMillis >= 0L)
        pruneLocked(nowMillis)
        val key = endpointKey(endpoint)
        if (key !in entries && entries.size >= maxPeers) return@synchronized
        entries[key] = Entry(endpoint, nowMillis)
    }

    fun snapshot(nowMillis: Long): List<AceLiveTcpPeerEndpoint> = synchronized(lock) {
        require(nowMillis >= 0L)
        pruneLocked(nowMillis)
        entries.values.map(Entry::endpoint)
    }

    private fun pruneLocked(nowMillis: Long) {
        entries.entries.removeAll { (_, entry) -> nowMillis - entry.lastSeenAtMillis > ttlMillis }
    }

    private fun endpointKey(endpoint: AceLiveTcpPeerEndpoint): String =
        "${endpoint.host}:${endpoint.port}"

    private data class Entry(val endpoint: AceLiveTcpPeerEndpoint, val lastSeenAtMillis: Long)
}

internal fun mergeAceLiveSupplementalPeers(
    result: AceLivePeerDiscoveryOrchestrationResult,
    peers: List<AceLiveTcpPeerEndpoint>,
    source: AceLivePeerDiscoverySource,
    maxTotalPeers: Int
): AceLivePeerDiscoveryOrchestrationResult {
    require(maxTotalPeers > 0)
    if (peers.isEmpty()) return result
    val merged = LinkedHashMap<String, Pair<AceLiveTcpPeerEndpoint, MutableSet<AceLivePeerDiscoverySource>>>()
    result.peers.forEach { peer ->
        merged["${peer.endpoint.host}:${peer.endpoint.port}"] =
            peer.endpoint to peer.sources.toMutableSet()
    }
    peers.forEach { endpoint ->
        val key = "${endpoint.host}:${endpoint.port}"
        val existing = merged[key]
        if (existing != null) {
            existing.second += source
        } else if (merged.size < maxTotalPeers) {
            merged[key] = endpoint to linkedSetOf(source)
        }
    }
    return result.copy(
        peers = merged.values.map { (endpoint, sources) ->
            AceLiveDiscoveredPeer(endpoint, sources.toSet())
        }
    )
}
