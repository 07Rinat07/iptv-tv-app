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

        val channelAliases = qualityAliases(normalizedChannelName)
        var candidate: String? = null
        for ((channelKey, channelId) in channelIdsByTextKey) {
            if (channelKey.isBlank()) continue
            val keyAliases = qualityAliases(channelKey)
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
     * XMLTV catalogues frequently omit display-quality suffixes that are present in M3U names.
     * Input is already normalized to letters/digits, so strip only one well-known suffix at the
     * very end. The original key is retained and ambiguity is still handled by the caller.
     */
    internal fun qualityAliases(normalized: String): Set<String> {
        val value = normalized.trim()
        if (value.isBlank()) return emptySet()
        val aliases = linkedSetOf(value)
        val suffix = QUALITY_SUFFIXES.firstOrNull { candidate ->
            value.length >= candidate.length + MIN_BASE_KEY_LENGTH && value.endsWith(candidate)
        }
        if (suffix != null) aliases += value.dropLast(suffix.length)
        return aliases
    }

    private const val MIN_BASE_KEY_LENGTH = 4
    private val QUALITY_SUFFIXES = listOf("fullhd", "fhd", "uhd", "4k", "hd", "sd")
}
