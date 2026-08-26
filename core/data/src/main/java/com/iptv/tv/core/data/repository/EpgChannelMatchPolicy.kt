package com.iptv.tv.core.data.repository

/**
 * Conservative fallback matching for XMLTV channel identifiers.
 *
 * Exact tvg-id/display-name/channel-id matching is handled by the repository before this
 * fallback is consulted. A partial/quality-alias match is accepted only when every matching
 * normalized key resolves to the same XMLTV channel. This keeps the result independent from
 * XMLTV/map order and fails closed when multiple channels are plausible.
 */
internal object EpgChannelMatchPolicy {
    fun uniquePartialChannelId(
        normalizedChannelName: String,
        channelIdsByTextKey: Iterable<Pair<String, String>>
    ): String? {
        if (normalizedChannelName.isBlank()) return null

        val channelAliases = presentationAliases(normalizedChannelName)
        var candidate: String? = null
        for ((channelKey, channelId) in channelIdsByTextKey) {
            if (channelKey.isBlank()) continue
            val keyAliases = presentationAliases(channelKey)
            val matches = channelAliases.any { channelAlias ->
                keyAliases.any { keyAlias ->
                    channelAlias == keyAlias ||
                        keyAlias.contains(channelAlias) ||
                        channelAlias.contains(keyAlias)
                }
            }
            if (!matches) continue

            val existing = candidate
            if (existing == null) {
                candidate = channelId
            } else if (existing != channelId) {
                return null
            }
        }
        return candidate
    }

    /**
     * IPTV catalogues commonly append transport/presentation metadata to the human channel name,
     * while XMLTV keeps the base station name. Input is already normalized to letters/digits.
     * Strip only well-known *trailing* decorations and retain every intermediate alias so ambiguity
     * checks still fail closed instead of broadening arbitrary substring matching.
     */
    internal fun presentationAliases(normalized: String): Set<String> {
        val value = normalized.trim()
        if (value.isBlank()) return emptySet()

        val aliases = linkedSetOf(value)
        var current = value
        var changed: Boolean
        do {
            changed = false
            val suffix = PRESENTATION_SUFFIXES.firstOrNull { candidate ->
                current.length >= candidate.length + MIN_BASE_KEY_LENGTH && current.endsWith(candidate)
            }
            if (suffix != null) {
                current = current.dropLast(suffix.length)
                aliases += current
                changed = true
            }
        } while (changed)
        return aliases
    }

    /** Compatibility alias kept for focused regression tests and older callers. */
    internal fun qualityAliases(normalized: String): Set<String> = presentationAliases(normalized)

    private const val MIN_BASE_KEY_LENGTH = 4

    // Order longest/editorial tags first so e.g. "...1080pgeoblocked" becomes base channel name
    // through deterministic, bounded suffix peeling.
    private val PRESENTATION_SUFFIXES = listOf(
        "geoblocked",
        "notavailable",
        "not247",
        "not24x7",
        "2160p",
        "1440p",
        "1080p",
        "900p",
        "720p",
        "576p",
        "540p",
        "480p",
        "360p",
        "240p",
        "fullhd",
        "fhd",
        "uhd",
        "4k",
        "hd",
        "sd"
    )
}
