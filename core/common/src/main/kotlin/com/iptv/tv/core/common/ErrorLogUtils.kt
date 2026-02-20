package com.iptv.tv.core.common

fun Throwable.toLogSummary(
    maxDepth: Int = 5,
    maxSegmentLength: Int = 180
): String {
    val seen = HashSet<Throwable>()
    val segments = ArrayList<String>()
    var current: Throwable? = this
    var depth = 0

    while (current != null && depth < maxDepth && seen.add(current)) {
        val message = current.message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
        val segment = if (message.isBlank()) {
            current.javaClass.simpleName
        } else {
            "${current.javaClass.simpleName}: ${message.take(maxSegmentLength)}"
        }
        segments += segment
        current = current.cause
        depth += 1
    }

    return segments.joinToString(separator = " <- ")
}

fun Throwable.rootCauseSummary(maxSegmentLength: Int = 180): String {
    var current: Throwable = this
    val seen = HashSet<Throwable>()
    while (current.cause != null && seen.add(current)) {
        current = current.cause ?: break
    }
    val message = current.message
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
    return if (message.isBlank()) {
        current.javaClass.simpleName
    } else {
        "${current.javaClass.simpleName}: ${message.take(maxSegmentLength)}"
    }
}
