package com.iptv.tv.core.data.repository

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

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

internal class EpgHttpStatusException(
    val statusCode: Int
) : IOException("HTTP $statusCode")

internal class EpgMalformedXmlException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

internal class EpgLowMemoryException(
    message: String
) : IOException(message)

internal enum class EpgFailureKind {
    TRANSIENT,
    PERMANENT_HTTP,
    MALFORMED,
    LOW_MEMORY
}

internal enum class EpgCacheFreshness {
    FRESH,
    STALE_FALLBACK,
    EXPIRED
}

internal enum class EpgSourceFormat {
    XMLTV,
    HTML,
    GZIP,
    XML_OTHER,
    TEXT_OTHER,
    BINARY_OR_UNKNOWN,
    EMPTY
}

internal data class EpgPreparedSource(
    val input: InputStream,
    val sourceFormat: EpgSourceFormat
)

/**
 * Small fail-closed preflight for the response body that is about to enter the XMLTV parser.
 *
 * Raw XMLTV is passed through unchanged. Raw gzip is supported because the TV Box field run proved
 * that a production EPG source is delivered as a gzip payload without HTTP content decoding. Both
 * paths remain streaming: only an 8 KiB prefix is inspected and pushed back before parsing.
 */
internal object EpgSourceFormatPolicy {
    const val PREFIX_BYTES = 8 * 1024

    fun classify(prefix: ByteArray, length: Int = prefix.size): EpgSourceFormat {
        val boundedLength = length.coerceIn(0, prefix.size)
        if (boundedLength == 0) return EpgSourceFormat.EMPTY

        if (
            boundedLength >= 2 &&
            prefix[0].toInt() and 0xFF == 0x1F &&
            prefix[1].toInt() and 0xFF == 0x8B
        ) {
            return EpgSourceFormat.GZIP
        }

        if (containsBinaryControl(prefix, boundedLength)) {
            return EpgSourceFormat.BINARY_OR_UNKNOWN
        }

        var text = prefix.copyOfRange(0, boundedLength)
            .toString(Charsets.UTF_8)
            .removePrefix("\uFEFF")
            .trimStart()
        if (text.isEmpty()) return EpgSourceFormat.EMPTY

        text = stripXmlPreamble(text)
        val lowered = text.lowercase()
        return when {
            isXmlTvRoot(text) -> EpgSourceFormat.XMLTV
            lowered.startsWith("<!doctype html") ||
                lowered.startsWith("<html") ||
                lowered.startsWith("<head") ||
                lowered.startsWith("<body") -> EpgSourceFormat.HTML
            text.startsWith('<') -> EpgSourceFormat.XML_OTHER
            text.any { it.isLetterOrDigit() } -> EpgSourceFormat.TEXT_OTHER
            else -> EpgSourceFormat.BINARY_OR_UNKNOWN
        }
    }

    fun prepareXmlTv(input: InputStream, sourceMaxBytes: Long): EpgPreparedSource {
        require(sourceMaxBytes > 0L) { "sourceMaxBytes must be positive" }
        val boundedSource = EpgByteLimitInputStream(input, sourceMaxBytes)
        val raw = inspectAndReplay(boundedSource)
        return when (raw.format) {
            EpgSourceFormat.XMLTV -> EpgPreparedSource(raw.input, EpgSourceFormat.XMLTV)
            EpgSourceFormat.GZIP -> prepareGzipXmlTv(raw.input)
            else -> throw EpgMalformedXmlException("EPG source is not XMLTV: format=${raw.format}")
        }
    }

    fun requireXmlTv(input: InputStream): InputStream {
        val inspected = inspectAndReplay(input)
        if (inspected.format != EpgSourceFormat.XMLTV) {
            throw EpgMalformedXmlException("EPG source is not XMLTV: format=${inspected.format}")
        }
        return inspected.input
    }

    private fun prepareGzipXmlTv(input: InputStream): EpgPreparedSource {
        val gzip = try {
            GZIPInputStream(input, 32 * 1024)
        } catch (failure: ZipException) {
            throw EpgMalformedXmlException("EPG gzip source has an invalid header", failure)
        }

        val decoded = try {
            inspectAndReplay(gzip)
        } catch (failure: ZipException) {
            throw EpgMalformedXmlException("EPG gzip source cannot be decoded", failure)
        }
        if (decoded.format != EpgSourceFormat.XMLTV) {
            throw EpgMalformedXmlException(
                "EPG gzip payload is not XMLTV: format=${decoded.format}"
            )
        }
        return EpgPreparedSource(decoded.input, EpgSourceFormat.GZIP)
    }

    private data class Inspection(
        val input: InputStream,
        val format: EpgSourceFormat
    )

    private fun inspectAndReplay(input: InputStream): Inspection {
        val pushback = PushbackInputStream(input, PREFIX_BYTES)
        val prefix = ByteArray(PREFIX_BYTES)
        var total = 0
        while (total < prefix.size) {
            val read = pushback.read(prefix, total, prefix.size - total)
            if (read <= 0) break
            total += read
        }
        if (total > 0) pushback.unread(prefix, 0, total)
        return Inspection(
            input = pushback,
            format = classify(prefix, total)
        )
    }

    private fun containsBinaryControl(bytes: ByteArray, length: Int): Boolean {
        for (index in 0 until length) {
            val value = bytes[index].toInt() and 0xFF
            if (value == 0 || value in 1..8 || value in 11..12 || value in 14..31) return true
        }
        return false
    }

    private fun stripXmlPreamble(raw: String): String {
        var text = raw.trimStart()
        if (text.startsWith("<?xml", ignoreCase = true)) {
            val end = text.indexOf("?>")
            if (end >= 0) text = text.substring(end + 2).trimStart()
        }
        while (text.startsWith("<!--")) {
            val end = text.indexOf("-->")
            if (end < 0) return text
            text = text.substring(end + 3).trimStart()
        }
        if (text.startsWith("<!DOCTYPE", ignoreCase = true)) {
            val end = text.indexOf('>')
            if (end >= 0) text = text.substring(end + 1).trimStart()
        }
        while (text.startsWith("<!--")) {
            val end = text.indexOf("-->")
            if (end < 0) return text
            text = text.substring(end + 3).trimStart()
        }
        return text
    }

    private fun isXmlTvRoot(text: String): Boolean {
        if (!text.startsWith("<tv", ignoreCase = true)) return false
        val next = text.getOrNull(3) ?: return false
        return next == '>' || next == '/' || next.isWhitespace()
    }
}

internal object EpgStaleFallbackPolicy {
    fun freshness(
        loadedAtMs: Long,
        nowMs: Long,
        freshTtlMs: Long,
        maxStaleAgeMs: Long
    ): EpgCacheFreshness {
        require(freshTtlMs >= 0L) { "freshTtlMs must be non-negative" }
        require(maxStaleAgeMs >= freshTtlMs) { "maxStaleAgeMs must cover freshTtlMs" }
        val ageMs = (nowMs - loadedAtMs).coerceAtLeast(0L)
        return when {
            ageMs <= freshTtlMs -> EpgCacheFreshness.FRESH
            ageMs <= maxStaleAgeMs -> EpgCacheFreshness.STALE_FALLBACK
            else -> EpgCacheFreshness.EXPIRED
        }
    }

    fun allowsStale(failureKind: EpgFailureKind): Boolean =
        failureKind == EpgFailureKind.TRANSIENT
}

internal fun classifyEpgFailure(failure: IOException): EpgFailureKind = when (failure) {
    is EpgInputLimitExceededException,
    is EpgMalformedXmlException -> EpgFailureKind.MALFORMED
    is EpgLowMemoryException -> EpgFailureKind.LOW_MEMORY
    is EpgHttpStatusException -> if (failure.statusCode in 500..599) {
        EpgFailureKind.TRANSIENT
    } else {
        EpgFailureKind.PERMANENT_HTTP
    }
    else -> EpgFailureKind.TRANSIENT
}

/**
 * Yields successful fresh candidates first. A stale snapshot is captured immediately after a
 * fresh failure but deferred until every fresh candidate has had a chance to satisfy the caller.
 * This preserves source selection even when the live cache intentionally holds only one source.
 */
internal data class EpgCandidateLoad<T>(
    val url: String,
    val value: T,
    val servedFromStaleFallback: Boolean
)

internal fun <T> loadEpgCandidatesFreshFirst(
    candidates: List<String>,
    loadFresh: (url: String) -> T,
    captureStaleFallback: (url: String) -> T?,
    onLoadError: (Exception) -> Unit
): Sequence<EpgCandidateLoad<T>> = sequence {
    val deferredStale = ArrayList<EpgCandidateLoad<T>>(candidates.size)
    for (url in candidates) {
        try {
            yield(
                EpgCandidateLoad(
                    url = url,
                    value = loadFresh(url),
                    servedFromStaleFallback = false
                )
            )
        } catch (failure: Exception) {
            onLoadError(failure)
            if (failure is EpgLowMemoryException) {
                deferredStale.clear()
            } else {
                captureStaleFallback(url)?.let { stale ->
                    deferredStale += EpgCandidateLoad(
                        url = url,
                        value = stale,
                        servedFromStaleFallback = true
                    )
                }
            }
        }
    }
    deferredStale.forEach { yield(it) }
}

internal data class EpgDiagnosticsCacheStatus(
    val servedFromStaleFallback: Boolean,
    val cacheAgeMs: Long,
    val refreshRetryAtMs: Long?
)

/** Read-only cache/refresh observability; it must never change refresh or source-selection policy. */
internal object EpgDiagnosticsCacheStatusPolicy {
    fun observe(
        loadedAtMs: Long,
        nowMs: Long,
        servedFromStaleFallback: Boolean,
        activeFailure: EpgFailureBackoffEntry?
    ): EpgDiagnosticsCacheStatus {
        val cacheAgeMs = (nowMs - loadedAtMs).coerceAtLeast(0L)
        val refreshRetryAtMs = if (
            servedFromStaleFallback &&
            activeFailure?.kind?.let(EpgStaleFallbackPolicy::allowsStale) == true
        ) {
            activeFailure.let { failure ->
                (failure.failedAtMs + failure.retryAfterMs).coerceAtLeast(failure.failedAtMs)
            }
        } else {
            null
        }
        return EpgDiagnosticsCacheStatus(
            servedFromStaleFallback = servedFromStaleFallback,
            cacheAgeMs = cacheAgeMs,
            refreshRetryAtMs = refreshRetryAtMs
        )
    }
}

/**
 * Streaming hard limit for XMLTV bodies.
 *
 * Raw XMLTV keeps the caller-provided source limit. Raw gzip is first bounded by that same source
 * limit, decoded streaming, then bounded again by [EpgInputSafetyPolicy.MAX_DECODED_XMLTV_BYTES].
 * This preserves the existing network envelope while preventing unbounded gzip expansion.
 */
internal class EpgBoundedInputStream(
    input: InputStream,
    maxBytes: Long,
    validateXmlTvPrefix: Boolean = true,
    maxDecodedBytes: Long = EpgInputSafetyPolicy.MAX_DECODED_XMLTV_BYTES
) : InputStream() {
    init {
        require(maxBytes > 0L) { "maxBytes must be positive" }
        require(maxDecodedBytes > 0L) { "maxDecodedBytes must be positive" }
    }

    private val prepared = if (
        validateXmlTvPrefix && maxBytes >= EpgSourceFormatPolicy.PREFIX_BYTES.toLong()
    ) {
        EpgSourceFormatPolicy.prepareXmlTv(input, maxBytes)
    } else {
        EpgPreparedSource(input, EpgSourceFormat.XMLTV)
    }

    private val delegate = EpgByteLimitInputStream(
        prepared.input,
        if (prepared.sourceFormat == EpgSourceFormat.GZIP) maxDecodedBytes else maxBytes
    )

    override fun read(): Int = delegate.read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun skip(byteCount: Long): Long = delegate.skip(byteCount)

    override fun available(): Int = delegate.available()

    override fun close() = delegate.close()
}

private class EpgByteLimitInputStream(
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
    val reason: String,
    val kind: EpgFailureKind
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

    /** Read-only lookup that preserves both access order and cache membership. */
    @Synchronized
    fun peekActive(url: String): EpgFailureBackoffEntry? {
        for (entry in entries.entries) {
            if (entry.key != url) continue
            val failure = entry.value
            return failure.takeIf { nowMs() - it.failedAtMs < it.retryAfterMs }
        }
        return null
    }

    @Synchronized
    fun record(
        url: String,
        reason: String,
        retryAfterMs: Long,
        kind: EpgFailureKind
    ) {
        entries[url] = EpgFailureBackoffEntry(
            failedAtMs = nowMs(),
            retryAfterMs = retryAfterMs.coerceAtLeast(1L),
            reason = reason.take(240),
            kind = kind
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
