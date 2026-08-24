package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.CatchUpPlaybackResolver as ModelCatchUpPlaybackResolver
import com.iptv.tv.core.parser.ChannelCatchUpMetadata

internal typealias CatchUpPlaybackResolution = com.iptv.tv.core.model.CatchUpPlaybackResolution

/**
 * Compatibility boundary for existing core:data callers and tests.
 *
 * The canonical fail-closed catch-up policy now lives in core:model so feature modules can reuse
 * it without taking a dependency on the data implementation layer.
 */
internal object CatchUpPlaybackResolver {
    fun resolve(
        rawLiveStreamUrl: String,
        metadata: ChannelCatchUpMetadata?,
        programStartEpochMs: Long,
        programEndEpochMs: Long,
        nowEpochMs: Long
    ): CatchUpPlaybackResolution = ModelCatchUpPlaybackResolver.resolve(
        rawLiveStreamUrl = rawLiveStreamUrl,
        metadata = metadata,
        programStartEpochMs = programStartEpochMs,
        programEndEpochMs = programEndEpochMs,
        nowEpochMs = nowEpochMs
    )
}
