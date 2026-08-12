package com.iptv.tv.core.data.repository

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedHashMap

internal class EpgInputLimitExceededException(
    val maxBytes: Long,
    val observedBytes: Long? = null
) : IOException(
    buildString {
        append("EPG input exceeds the ")
        append(maxBytes)
        append(" byte safety limit")
        observedBytes?.let {
            append(" (reported=")
            append(it)
            append(')')
        }
    }
)

/**
 * Streaming hard limit for XMLTV bodies.
 *
 * Unlike ResponseBody.bytes(), this never allocates a second byte array containing the complete
 * guide. If the server does not provide Content-Length, the limit is still enforced while the XML
 * parser consumes the response.
 */
internal class EpgBoundedInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {
    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
    }

    private var consumedBytes: Long = 0L

    override fun read(): Int {
        if (consumedBytes >= maxBytes) {
            val extra = super.read()
            if (extra == -1) return -1
            throw EpgInputLimitExceededException(maxBytes)
        }
        val value = super.read()
        if (value != -1) consumedBytes += 1L
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (consumedBytes >= maxBytes) {
            val extra = super.read()
            if (extra == -1) return -1
            throw EpgInputLimitExceededException(maxBytes)
        }
        val remaining = maxBytes - consumedBytes
        val allowed = minOf(length.toLong(), remaining).toInt()
        val read = super.read(buffer, offset, allowed)
        if (read > 0) consumedBytes += read.toLong()
        return read
    }
}

internal data class EpgFailureBackoffEntry(
    val failedAtMs: Long,
    val retryAfterMs: Long,
    val reason: String
)

/**
 * Bounded negative-cache. It intentionally stores no Throwable so parser/network failures cannot
 * retain large exception graphs or response objects in memory.
 */
internal class EpgFailureBackoffCache(
    private val maxEntries: Int,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val entries = LinkedHashMap<String, EpgFailureBackoffEntry>(16, 0.75f, true)

    @Synchronized
    fun active(url: String): EpgFailureBackoffEntry? {
        val entry = entries[url] ?: return null
        if (nowMs() - entry.failedAtMs < entry.retryAfterMs) return entry
        entries.remove(url)
        return null
    }

    @Synchronized
    fun record(url: String, reason: String, retryAfterMs: Long) {
        entries[url] = EpgFailureBackoffEntry(
            failedAtMs = nowMs(),
            retryAfterMs = retryAfterMs.coerceAtLeast(1L),
            reason = reason.take(240)
        )
        while (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
    }

    @Synchronized
    fun remove(url: String) {
        entries.remove(url)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
