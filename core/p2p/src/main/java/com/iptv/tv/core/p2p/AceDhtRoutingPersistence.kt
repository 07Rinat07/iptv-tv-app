package com.iptv.tv.core.p2p

import java.io.File
import java.io.FileOutputStream

internal data class AceDhtPersistedContact(
    val contact: AceLiveDhtNodeContact,
    val lastSeenEpochMillis: Long
) {
    init {
        require(lastSeenEpochMillis >= 0L) { "lastSeenEpochMillis must be non-negative" }
    }
}

/**
 * Small persistence boundary for verified Mainline-DHT routing contacts.
 *
 * Only contacts that have already produced a valid KRPC response are written. The store is private
 * application state; it never persists swarm peers, content ids, tracker credentials, media or user
 * payloads. Implementations must remain bounded because this cache is an optimization, not a source
 * of truth.
 */
internal interface AceDhtRoutingPersistence {
    fun load(): List<AceDhtPersistedContact>
    fun save(contacts: List<AceDhtPersistedContact>)
}

/**
 * Atomic, bounded TSV persistence used by the embedded engine on Android.
 *
 * Format is intentionally trivial and dependency-free:
 * `epochMillis<TAB>nodeIdHex<TAB>ipv4-or-host<TAB>port`.
 * Malformed lines are ignored independently so one damaged entry never poisons the whole cache.
 */
internal class FileAceDhtRoutingPersistence(
    private val file: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES
) : AceDhtRoutingPersistence {
    init {
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) { "maxEntries is out of range" }
        require(maxFileBytes in MIN_FILE_BYTES..MAX_ALLOWED_FILE_BYTES) {
            "maxFileBytes is out of range"
        }
    }

    override fun load(): List<AceDhtPersistedContact> {
        if (!file.isFile || file.length() <= 0L || file.length() > maxFileBytes) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.asSequence()
                    .take(maxEntries)
                    .mapNotNull(::decodeLine)
                    .toList()
            }
        }.getOrDefault(emptyList())
    }

    override fun save(contacts: List<AceDhtPersistedContact>) {
        val bounded = contacts
            .asSequence()
            .sortedByDescending(AceDhtPersistedContact::lastSeenEpochMillis)
            .take(maxEntries)
            .toList()
        val parent = file.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) return

        val temp = File(parent, "${file.name}.tmp")
        runCatching {
            FileOutputStream(temp, false).use { output ->
                val writer = output.bufferedWriter(Charsets.UTF_8)
                bounded.forEach { item ->
                    writer.append(encodeLine(item)).append('\n')
                }
                writer.flush()
                output.fd.sync()
            }
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("Cannot replace DHT routing cache")
            }
            if (!temp.renameTo(file)) {
                throw IllegalStateException("Cannot commit DHT routing cache")
            }
        }.onFailure {
            runCatching { temp.delete() }
        }
    }

    private fun encodeLine(item: AceDhtPersistedContact): String = buildString {
        append(item.lastSeenEpochMillis)
        append('\t')
        append(item.contact.nodeId.toByteArray().toHex())
        append('\t')
        append(item.contact.endpoint.host)
        append('\t')
        append(item.contact.endpoint.port)
    }

    private fun decodeLine(line: String): AceDhtPersistedContact? {
        if (line.isBlank() || line.length > MAX_LINE_CHARS) return null
        val fields = line.split('\t')
        if (fields.size != 4) return null
        val timestamp = fields[0].toLongOrNull()?.takeIf { it >= 0L } ?: return null
        val nodeBytes = fields[1].hexToBytesOrNull(AceLiveDhtNodeId.BYTES) ?: return null
        val host = fields[2].takeIf {
            it.isNotBlank() && it.length <= MAX_HOST_CHARS && '\t' !in it && '\n' !in it && '\r' !in it
        } ?: return null
        val port = fields[3].toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return runCatching {
            AceDhtPersistedContact(
                contact = AceLiveDhtNodeContact(
                    nodeId = AceLiveDhtNodeId.fromBytes(nodeBytes),
                    endpoint = AceLiveTcpPeerEndpoint(host, port)
                ),
                lastSeenEpochMillis = timestamp
            )
        }.getOrNull()
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun String.hexToBytesOrNull(expectedBytes: Int): ByteArray? {
        if (length != expectedBytes * 2) return null
        val result = ByteArray(expectedBytes)
        for (index in 0 until expectedBytes) {
            val value = substring(index * 2, index * 2 + 2).toIntOrNull(16) ?: return null
            result[index] = value.toByte()
        }
        return result
    }

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 128
        const val MAX_ALLOWED_ENTRIES = 512
        const val DEFAULT_MAX_FILE_BYTES = 128L * 1024L
        const val MIN_FILE_BYTES = 4L * 1024L
        const val MAX_ALLOWED_FILE_BYTES = 1024L * 1024L
        const val MAX_LINE_CHARS = 512
        const val MAX_HOST_CHARS = 253
    }
}
