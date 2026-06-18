package com.iptv.tv.core.data.repository

import java.util.Locale

internal data class ParentalChannelGate(
    val enabled: Boolean,
    val hideAdultChannels: Boolean,
    val blockedKeywords: List<String>
) {
    val blocksChannels: Boolean = enabled && hideAdultChannels && blockedKeywords.isNotEmpty()
}

internal object ParentalChannelFilter {
    val defaultKeywords = listOf(
        "adult",
        "xxx",
        "18+",
        "porn",
        "porno",
        "erotic",
        "sex",
        "для взрослых",
        "взрослые",
        "эротика"
    )

    fun isBlocked(
        name: String,
        groupName: String?,
        tvgId: String?,
        gate: ParentalChannelGate
    ): Boolean {
        if (!gate.blocksChannels) return false
        val haystack = listOf(name, groupName.orEmpty(), tvgId.orEmpty())
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return gate.blockedKeywords.any { keyword ->
            val normalized = keyword.trim().lowercase(Locale.ROOT)
            normalized.isNotBlank() && haystack.contains(normalized)
        }
    }

    fun decodeKeywords(raw: String?): List<String> {
        return raw
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinctBy { it.lowercase(Locale.ROOT) }
            ?.ifEmpty { defaultKeywords }
            ?: defaultKeywords
    }
}
