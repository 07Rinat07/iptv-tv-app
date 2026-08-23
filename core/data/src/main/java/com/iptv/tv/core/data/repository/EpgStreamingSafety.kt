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

    /** Read-only lookup that preserves the access-order eviction policy. */
    @Synchronized
    fun peekActive(url: String): EpgFailureBackoffEntry? {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key != url) continue
            val failure = entry.value
            if (nowMs() - failure.failedAtMs < failure.retryAfterMs) return failure
            iterator.remove()
            return null
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
