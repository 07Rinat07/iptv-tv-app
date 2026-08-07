package com.iptv.tv.core.p2p

internal sealed interface HttpRangeResolution {
    data class Full(val contentLength: Long) : HttpRangeResolution

    data class Partial(
        val start: Long,
        val endInclusive: Long,
        val contentLength: Long
    ) : HttpRangeResolution {
        val length: Long
            get() = endInclusive - start + 1L
    }

    data class Unsatisfiable(val contentLength: Long) : HttpRangeResolution
}

internal object HttpByteRange {
    fun resolve(headerValue: String?, contentLength: Long): HttpRangeResolution {
        require(contentLength >= 0L) { "contentLength must not be negative" }

        if (headerValue.isNullOrBlank()) {
            return HttpRangeResolution.Full(contentLength)
        }
        if (contentLength == 0L) {
            return HttpRangeResolution.Unsatisfiable(contentLength)
        }

        val header = headerValue.trim()
        val separator = header.indexOf('=')
        if (separator <= 0 || !header.substring(0, separator).trim().equals("bytes", ignoreCase = true)) {
            return HttpRangeResolution.Unsatisfiable(contentLength)
        }

        val specification = header.substring(separator + 1).trim()
        if (specification.isEmpty() || ',' in specification) {
            return HttpRangeResolution.Unsatisfiable(contentLength)
        }

        val dash = specification.indexOf('-')
        if (dash < 0 || specification.indexOf('-', dash + 1) >= 0) {
            return HttpRangeResolution.Unsatisfiable(contentLength)
        }

        val startText = specification.substring(0, dash).trim()
        val endText = specification.substring(dash + 1).trim()

        if (startText.isEmpty()) {
            val suffixLength = endText.toPositiveLongOrNull()
                ?: return HttpRangeResolution.Unsatisfiable(contentLength)
            val actualLength = minOf(suffixLength, contentLength)
            return HttpRangeResolution.Partial(
                start = contentLength - actualLength,
                endInclusive = contentLength - 1L,
                contentLength = contentLength
            )
        }

        val start = startText.toNonNegativeLongOrNull()
            ?: return HttpRangeResolution.Unsatisfiable(contentLength)
        if (start >= contentLength) {
            return HttpRangeResolution.Unsatisfiable(contentLength)
        }

        val requestedEnd = if (endText.isEmpty()) {
            contentLength - 1L
        } else {
            endText.toNonNegativeLongOrNull()
                ?: return HttpRangeResolution.Unsatisfiable(contentLength)
        }
        if (requestedEnd < start) {
            return HttpRangeResolution.Unsatisfiable(contentLength)
        }

        return HttpRangeResolution.Partial(
            start = start,
            endInclusive = minOf(requestedEnd, contentLength - 1L),
            contentLength = contentLength
        )
    }

    private fun String.toNonNegativeLongOrNull(): Long? {
        if (isEmpty() || any { !it.isDigit() }) return null
        return toLongOrNull()?.takeIf { it >= 0L }
    }

    private fun String.toPositiveLongOrNull(): Long? =
        toNonNegativeLongOrNull()?.takeIf { it > 0L }
}
