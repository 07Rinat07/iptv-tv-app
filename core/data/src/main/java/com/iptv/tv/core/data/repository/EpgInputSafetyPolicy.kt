package com.iptv.tv.core.data.repository

/**
 * Hard input envelopes for XMLTV downloads.
 *
 * Field evidence shows a legitimate raw-gzip guide with a reported body size of 88,578,547 bytes,
 * so the source/network envelope must remain large enough for that payload. Raw gzip is decoded
 * streaming; the expanded XMLTV stream gets its own bounded envelope so compressed input cannot
 * turn into an unbounded gzip expansion in memory or CPU work.
 */
internal object EpgInputSafetyPolicy {
    const val MAX_INPUT_BYTES: Long = 128L * 1024L * 1024L
    const val MAX_DECODED_XMLTV_BYTES: Long = 256L * 1024L * 1024L

    fun allowsReportedLength(contentLength: Long): Boolean =
        contentLength < 0L || contentLength <= MAX_INPUT_BYTES
}
