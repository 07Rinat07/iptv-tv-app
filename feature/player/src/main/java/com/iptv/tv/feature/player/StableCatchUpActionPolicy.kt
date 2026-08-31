package com.iptv.tv.feature.player

import com.iptv.tv.core.model.CatchUpPlaybackResolution
import com.iptv.tv.core.model.CatchUpPlaybackResolver
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram

/**
 * Presentation boundary for archive actions shown from the Player programme guide.
 *
 * Capability is never inferred from the live URL or from EPG history alone. The canonical
 * fail-closed resolver remains the single source of truth for supported provider metadata.
 */
internal object StableCatchUpActionPolicy {
    fun resolve(
        channel: Channel?,
        program: EpgProgram,
        nowMs: Long
    ): CatchUpPlaybackResolution? {
        channel ?: return null
        return CatchUpPlaybackResolver.resolve(
            rawLiveStreamUrl = channel.streamUrl,
            metadata = channel.catchUp,
            programStartEpochMs = program.startEpochMs,
            programEndEpochMs = program.endEpochMs,
            nowEpochMs = nowMs
        )
    }

    fun isAvailable(
        channel: Channel?,
        program: EpgProgram,
        nowMs: Long
    ): Boolean {
        val resolution = resolve(channel, program, nowMs) ?: return false
        return resolution.supported && !resolution.playbackUrl.isNullOrBlank()
    }
}
