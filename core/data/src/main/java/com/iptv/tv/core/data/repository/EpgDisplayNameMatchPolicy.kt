package com.iptv.tv.core.data.repository

/**
 * Precomputed, fail-closed XMLTV `<display-name>` alias index.
 *
 * Display names are human-facing identifiers, so the same bounded presentation aliases used by
 * [EpgChannelMatchPolicy] are allowed. Any alias that resolves to more than one XMLTV channel is
 * marked ambiguous and can never produce a match.
 */
internal data class EpgDisplayNameAliasIndex(
    val channelIdByAlias: Map<String, String>,
    val ambiguousAliases: Set<String>
)

internal object EpgDisplayNameMatchPolicy {
    fun buildIndex(
        normalizedDisplayNamesByChannel: Iterable<Pair<String, String>>
    ): EpgDisplayNameAliasIndex {
        val channelIdByAlias = linkedMapOf<String, String>()
        val ambiguousAliases = linkedSetOf<String>()

        normalizedDisplayNamesByChannel.forEach { (normalizedDisplayName, channelId) ->
            if (normalizedDisplayName.isBlank() || channelId.isBlank()) return@forEach

            EpgChannelMatchPolicy.presentationAliases(normalizedDisplayName).forEach { alias ->
                if (alias in ambiguousAliases) return@forEach

                val existing = channelIdByAlias[alias]
                when {
                    existing == null -> channelIdByAlias[alias] = channelId
                    existing == channelId -> Unit
                    else -> {
                        channelIdByAlias.remove(alias)
                        ambiguousAliases += alias
                    }
                }
            }
        }

        return EpgDisplayNameAliasIndex(
            channelIdByAlias = channelIdByAlias,
            ambiguousAliases = ambiguousAliases
        )
    }

    fun uniqueChannelId(
        normalizedChannelName: String,
        index: EpgDisplayNameAliasIndex
    ): String? {
        if (normalizedChannelName.isBlank()) return null

        var candidate: String? = null
        for (alias in EpgChannelMatchPolicy.presentationAliases(normalizedChannelName)) {
            if (alias in index.ambiguousAliases) return null
            val channelId = index.channelIdByAlias[alias] ?: continue

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
