package com.iptv.tv.core.model

/**
 * One selectable playback/source variant for a durable logical favorite.
 *
 * [variantKey] is stable for the normalized stream URL and is the only selector required by the
 * feature layer. Local Room row IDs are intentionally not part of this contract so aggregate
 * Favorites identity stays independent from source-row lifetime.
 */
data class FavoriteSourceVariant(
    val logicalKey: String,
    val variantKey: String,
    val name: String,
    val streamUrl: String,
    val playlistName: String?,
    val sourceType: String?,
    val catalogOrigin: String?,
    val isLive: Boolean,
    val health: ChannelHealth,
    val isPreferred: Boolean
) {
    init {
        require(logicalKey.isNotBlank()) { "Favorite logical key must not be blank" }
        require(variantKey.isNotBlank()) { "Favorite variant key must not be blank" }
        require(name.isNotBlank()) { "Favorite variant name must not be blank" }
        require(streamUrl.isNotBlank()) { "Favorite variant stream URL must not be blank" }
    }
}
