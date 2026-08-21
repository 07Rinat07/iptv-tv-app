package com.iptv.tv.core.model

/**
 * Resolved source used when a logical favorite is opened for playback.
 *
 * [channel] may be a current live Room channel or an independently persisted favorite snapshot.
 * Consumers must treat [channel.playlistId] as provenance only when [isLiveVariant] is false.
 */
data class FavoritePlaybackContext(
    val logicalKey: String,
    val channel: Channel,
    val selectedVariantKey: String?,
    val isLiveVariant: Boolean,
    val availableVariantCount: Int
)
