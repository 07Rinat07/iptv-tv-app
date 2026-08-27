package com.iptv.tv.core.p2p

import java.io.File
import java.io.FileOutputStream

internal data class AceLivePeerReputationSnapshot(
    val lastHandshakeAtMillis: Long?,
    val lastProducedAtMillis: Long?,
    val consecutiveFailures: Int,
    val lastFailureAtMillis: Long?
) {
    init {
        require(consecutiveFailures >= 0) { "consecutiveFailures must be non-negative" }
    }

    fun priorityRank(nowMillis: Long): Int {
        val lastFailure = lastFailureAtMillis
        val produced = lastProducedAtMillis
        if (
            produced != null &&
            ageMillis(produced, nowMillis) <= RECENT_PRODUCER_MILLIS &&
            (lastFailure == null || produced >= lastFailure)
        ) {
            return 0
        }

        val handshake = lastHandshakeAtMillis
        if (
            handshake != null &&
            ageMillis(handshake, nowMillis) <= RECENT_HANDSHAKE_MILLIS &&
            (lastFailure == null || handshake >= lastFailure)
        ) {
            return 1
        }

        if (
            consecutiveFailures >= 2 &&
            lastFailure != null &&
            ageMillis(lastFailure, nowMillis) <= RECENT_FAILURE_MILLIS
        ) {
            return 3
        }
        return 2
    }

    private fun ageMillis(timestamp: Long, nowMillis: Long): Long =
        if (nowMillis >= timestamp) nowMillis - timestamp else Long.MAX_VALUE

    private companion object {
        const val RECENT_PRODUCER_MILLIS = 30 * 60 * 1_000L
        const val RECENT_HANDSHAKE_MILLIS = 15 * 60 * 1_000L
        const val RECENT_FAILURE_MILLIS = 5 * 60 * 1_000L
    }
}

/** Swarm-scoped cross-runtime peer evidence. It never turns a peer into a permanent allow/deny. */
internal interface AceLivePeerReputationStore {
    fun snapshot(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ): AceLivePeerReputationSnapshot?

    fun recordHandshakeAccepted(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    )

    fun recordMediaProduced(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    )

    fun recordFinalFailure(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    )
}

/**
 * Bounded app-private persistence for same-swarm peer reputation.
 *
 * Positive evidence is intentionally short-lived because live peer endpoints churn. Negative
 * evidence only affects ranking; it never permanently suppresses a candidate. This keeps recovery
 * possible when a previously bad endpoint becomes healthy again.
 */
internal class FileAceLivePeerReputationStore(
    private val file: File,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) : AceLivePeerReputationStore {
    private val lock = Any()
    private val entries = LinkedHashMap<PeerKey, Entry>()

    init {
        require(ttlMillis in 60_000L..MAX_TTL_MILLIS) { "peer reputation TTL is out of range" }
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) { "peer reputation maxEntries is out of range" }
        restore()
    }

    override fun snapshot(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ): AceLivePeerReputationSnapshot? = synchronized(lock) {
        pruneLocked(nowMillis)
        entries[key(swarmKey, endpoint)]?.toSnapshot()
    }

    override fun recordHandshakeAccepted(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ) = mutate(swarmKey, endpoint, nowMillis) { entry ->
        entry.lastHandshakeAtMillis = nowMillis
        entry.consecutiveFailures = 0
    }

    override fun recordMediaProduced(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ) = mutate(swarmKey, endpoint, nowMillis) { entry ->
        entry.lastProducedAtMillis = nowMillis
        entry.lastHandshakeAtMillis = maxOf(entry.lastHandshakeAtMillis ?: 0L, nowMillis)
        entry.consecutiveFailures = 0
    }

    override fun recordFinalFailure(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ) = mutate(swarmKey, endpoint, nowMillis) { entry ->
        entry.lastFailureAtMillis = nowMillis
        entry.consecutiveFailures = (entry.consecutiveFailures + 1).coerceAtMost(MAX_FAILURES)
    }

    private fun mutate(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long,
        block: (Entry) -> Unit
    ) {
        val snapshot = synchronized(lock) {
            pruneLocked(nowMillis)
            val peerKey = key(swarmKey, endpoint)
            val entry = entries.remove(peerKey) ?: Entry(
                swarm = peerKey.swarm,
                host = peerKey.host,
                port = peerKey.port,
                updatedAtMillis = nowMillis
            )
            block(entry)
            entry.updatedAtMillis = nowMillis
            while (entries.size >= maxEntries) {
                val eldest = entries.keys.firstOrNull() ?: break
                entries.remove(eldest)
            }
            entries[peerKey] = entry
            entries.values.map(Entry::copy)
        }
        persist(snapshot)
    }

    private fun restore() {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_FILE_BYTES) return
        val now = clockMillis()
        val restored = runCatching {
            file.useLines { lines ->
                lines.asSequence()
                    .take(maxEntries)
                    .mapNotNull(::decodeLine)
                    .filter { entry -> ageMillis(entry.updatedAtMillis, now) < ttlMillis }
                    .sortedBy(Entry::updatedAtMillis)
                    .toList()
            }
        }.getOrDefault(emptyList())
        synchronized(lock) {
            restored.forEach { entry ->
                entries[PeerKey(entry.swarm, entry.host, entry.port)] = entry
            }
            pruneLocked(now)
        }
    }

    private fun persist(snapshot: List<Entry>) {
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return
        val temp = File(parent, "${file.name}.tmp")
        runCatching {
            FileOutputStream(temp, false).use { output ->
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    snapshot
                        .sortedByDescending(Entry::updatedAtMillis)
                        .take(maxEntries)
                        .forEach { entry -> writer.append(encodeLine(entry)).append('\n') }
                    writer.flush()
                }
                output.fd.sync()
            }
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Cannot replace peer reputation cache")
            }
            if (!temp.renameTo(file)) {
                throw IllegalStateException("Cannot commit peer reputation cache")
            }
        }.onFailure {
            runCatching { temp.delete() }
        }
    }

    private fun pruneLocked(nowMillis: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (ageMillis(iterator.next().value.updatedAtMillis, nowMillis) >= ttlMillis) {
                iterator.remove()
            }
        }
    }

    private fun key(swarmKey: ByteArray, endpoint: AceLiveTcpPeerEndpoint): PeerKey = PeerKey(
        swarm = swarmHex(swarmKey),
        host = endpoint.host.lowercase(),
        port = endpoint.port
    )

    private fun swarmHex(swarmKey: ByteArray): String {
        require(swarmKey.size == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) {
            "swarmKey must be ${AceLivePeerHandshakeCodec.SWARM_KEY_BYTES} bytes"
        }
        return swarmKey.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun encodeLine(entry: Entry): String = listOf(
        entry.swarm,
        entry.host,
        entry.port.toString(),
        entry.updatedAtMillis.toString(),
        entry.lastHandshakeAtMillis?.toString().orEmpty(),
        entry.lastProducedAtMillis?.toString().orEmpty(),
        entry.consecutiveFailures.toString(),
        entry.lastFailureAtMillis?.toString().orEmpty()
    ).joinToString("\t")

    private fun decodeLine(line: String): Entry? {
        if (line.isBlank() || line.length > MAX_LINE_CHARS) return null
        val fields = line.split('\t')
        if (fields.size != 8) return null
        val swarm = fields[0].takeIf { it.length == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES * 2 && it.all(Char::isHexDigit) }
            ?: return null
        val host = fields[1].takeIf {
            it.isNotBlank() && it.length <= MAX_HOST_CHARS && '\n' !in it && '\r' !in it
        } ?: return null
        val port = fields[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val updatedAt = fields[3].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val handshake = fields[4].toLongOrNull()?.takeIf { it >= 0L }
        val produced = fields[5].toLongOrNull()?.takeIf { it >= 0L }
        val failures = fields[6].toIntOrNull()?.takeIf { it in 0..MAX_FAILURES } ?: return null
        val failureAt = fields[7].toLongOrNull()?.takeIf { it >= 0L }
        return Entry(
            swarm = swarm.lowercase(),
            host = host.lowercase(),
            port = port,
            updatedAtMillis = updatedAt,
            lastHandshakeAtMillis = handshake,
            lastProducedAtMillis = produced,
            consecutiveFailures = failures,
            lastFailureAtMillis = failureAt
        )
    }

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun ageMillis(timestamp: Long, nowMillis: Long): Long =
        if (nowMillis >= timestamp) nowMillis - timestamp else Long.MAX_VALUE

    private data class PeerKey(
        val swarm: String,
        val host: String,
        val port: Int
    )

    private data class Entry(
        val swarm: String,
        val host: String,
        val port: Int,
        var updatedAtMillis: Long,
        var lastHandshakeAtMillis: Long? = null,
        var lastProducedAtMillis: Long? = null,
        var consecutiveFailures: Int = 0,
        var lastFailureAtMillis: Long? = null
    ) {
        fun toSnapshot() = AceLivePeerReputationSnapshot(
            lastHandshakeAtMillis = lastHandshakeAtMillis,
            lastProducedAtMillis = lastProducedAtMillis,
            consecutiveFailures = consecutiveFailures,
            lastFailureAtMillis = lastFailureAtMillis
        )
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 30 * 60 * 1_000L
        const val MAX_TTL_MILLIS = 24 * 60 * 60 * 1_000L
        const val DEFAULT_MAX_ENTRIES = 512
        const val MAX_ALLOWED_ENTRIES = 2_048
        const val MAX_FAILURES = 16
        const val MAX_FILE_BYTES = 512L * 1024L
        const val MAX_LINE_CHARS = 768
        const val MAX_HOST_CHARS = 253
    }
}
