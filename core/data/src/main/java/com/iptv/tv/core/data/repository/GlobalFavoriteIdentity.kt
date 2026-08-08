package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.ChannelStableIdentity

/**
 * Compatibility adapter for the existing Favorites repository.
 *
 * The canonical logical-channel algorithm now lives in core:model so catalog adapters,
 * deduplication and Favorites cannot drift into separate identity systems. This wrapper keeps
 * the current repository call sites and tvg-id -> normalized name -> URL behavior unchanged.
 */
internal object GlobalFavoriteIdentity {
    fun key(tvgId: String?, name: String, streamUrl: String): String =
        ChannelStableIdentity.key(tvgId = tvgId, name = name, streamUrl = streamUrl)

    internal fun normalizeName(value: String): String = ChannelStableIdentity.normalizeName(value)
}
