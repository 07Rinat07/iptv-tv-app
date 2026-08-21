package com.iptv.tv.core.model

/**
 * Reserved navigation-only playlist ID for opening a durable favorite in the existing Player route.
 *
 * Room playlist IDs are generated as positive values. Keeping this sentinel in core:model lets the
 * Favorites UI and Player agree on the mode without exposing database implementation details or
 * adding a second navigation stack.
 */
const val FAVORITE_PLAYBACK_PLAYLIST_ID: Long = Long.MIN_VALUE + 45L

/**
 * Resolved source used when a logical favorite is opened in Player.
 *
 * [channel] may be a current live Room channel or an independently persisted favorite snapshot.
 * Player must treat [channel.playlistId] as provenance only when [isLiveVariant] is false.
 */
data class FavoritePlaybackContext(
    val logicalKey: String,
    val channel: Channel,
    val selectedVariantKey: String?,
    val isLiveVariant: Boolean,
    val availableVariantCount: Int
)
