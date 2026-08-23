package com.iptv.tv.core.data.repository

/**
 * Conservative fallback matching for XMLTV channel identifiers.
 *
 * Exact tvg-id/display-name/channel-id matching is handled by the repository before this
 * fallback is consulted. A partial match is accepted only when every matching normalized key
 * resolves to the same XMLTV channel. This keeps the result independent from XMLTV/map order and
 * fails closed when multiple channels are plausible.
 */
internal object EpgChannelMatchPolicy {
    fun uniquePartialChannelId(
        normalizedChannelName: String,
        channelIdByTextKey: Map<String, String>
    ): String? {
        if (normalizedChannelName.isBlank()) return null

        var candidate: String? = null
        for ((channelKey, channelId) in channelIdByTextKey) {
            if (channelKey.isBlank()) continue
            val matches = channelKey.contains(normalizedChannelName) ||
                normalizedChannelName.contains(channelKey)
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
}
