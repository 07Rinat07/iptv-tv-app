package com.iptv.tv.core.data.repository

/**
 * Hard input envelope for XMLTV downloads.
 *
 * The field source captured on the TV Box is 88,578,547 bytes, so the former 64 MiB ceiling
 * rejected a legitimate guide before the streaming parser could apply its much tighter in-memory
 * channel/program/text limits. Keep the transport envelope bounded, but large enough for real
 * multi-channel XMLTV feeds. Unknown Content-Length values are admitted to the streaming guard,
 * where [EpgBoundedInputStream] enforces the same byte ceiling while parsing.
 */
internal object EpgInputSafetyPolicy {
    const val MAX_INPUT_BYTES: Long = 128L * 1024L * 1024L

    fun allowsReportedLength(contentLength: Long): Boolean =
        contentLength < 0L || contentLength <= MAX_INPUT_BYTES
}
