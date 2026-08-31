package com.iptv.tv.core.data.repository

/**
 * Hard input envelopes for XMLTV downloads.
 *
 * The compressed/network body stays tightly bounded. Decoded XMLTV is parsed as a stream and may
 * legitimately be much larger than the heap, so its absolute limit is a scan/CPU budget rather
 * than a memory-allocation budget. Gzip expansion is additionally constrained by
 * [MAX_GZIP_EXPANSION_RATIO]; retained EPG state is bounded independently by the parser's channel,
 * programme, text and time-window limits.
 */
internal object EpgInputSafetyPolicy {
    /** Maximum bytes accepted from the HTTP/source body itself. */
    const val MAX_INPUT_BYTES: Long = 128L * 1024L * 1024L

    /**
     * Absolute decoded XMLTV scan budget. This is intentionally larger than the old 256 MiB
     * all-or-nothing boundary because decoded bytes are not retained as one in-memory document.
     */
    const val MAX_DECODED_XMLTV_BYTES: Long = 1024L * 1024L * 1024L

    /** Maximum decoded/compressed ratio for gzip XMLTV before treating it as pathological input. */
    const val MAX_GZIP_EXPANSION_RATIO: Long = 64L

    fun allowsReportedLength(contentLength: Long): Boolean =
        contentLength < 0L || contentLength <= MAX_INPUT_BYTES
}
