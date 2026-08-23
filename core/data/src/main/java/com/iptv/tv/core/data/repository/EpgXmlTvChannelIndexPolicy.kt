package com.iptv.tv.core.data.repository

/**
 * Keeps XMLTV channel identity independent from retained programme coverage.
 *
 * Declared `<channel id>` values must remain matchable even when all programmes for that channel
 * fall outside the bounded retention window. Programme-only channel ids are retained as a
 * compatibility fallback for feeds that omit channel declarations.
 */
internal object EpgXmlTvChannelIndexPolicy {
    fun knownChannelIds(
        declaredChannelIds: Iterable<String>,
        programmedChannelIds: Iterable<String>
    ): List<String> {
        val result = linkedSetOf<String>()
        declaredChannelIds.forEach { channelId ->
            channelId.takeIf { it.isNotBlank() }?.let(result::add)
        }
        programmedChannelIds.forEach { channelId ->
            channelId.takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result.toList()
    }
}
