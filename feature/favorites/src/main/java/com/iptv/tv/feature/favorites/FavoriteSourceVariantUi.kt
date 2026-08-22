package com.iptv.tv.feature.favorites

import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.FavoriteSourceVariant

/** User-facing source labels intentionally omit stream URLs and credentials. */
internal fun favoriteSourceVariantTitle(variant: FavoriteSourceVariant): String =
    variant.playlistName
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?: variant.sourceType
            ?.trim()
            ?.takeIf(String::isNotBlank)
        ?: "Источник канала"

internal fun favoriteSourceVariantSummary(variant: FavoriteSourceVariant): String {
    val title = favoriteSourceVariantTitle(variant)
    return buildList {
        variant.sourceType
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != title }
            ?.let(::add)
        variant.catalogOrigin
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let(::add)
        add(if (variant.isLive) "доступен сейчас" else "сохраненный вариант")
        add(
            when (variant.health) {
                ChannelHealth.AVAILABLE -> "доступен"
                ChannelHealth.UNSTABLE -> "нестабилен"
                ChannelHealth.UNKNOWN -> "статус неизвестен"
                ChannelHealth.UNAVAILABLE -> "недоступен"
            }
        )
    }.distinct().joinToString(separator = " · ")
}

internal fun favoriteSourceSelectionMessage(variant: FavoriteSourceVariant): String =
    "Источник выбран: ${favoriteSourceVariantTitle(variant)}"
