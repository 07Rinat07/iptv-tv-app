package com.iptv.tv.feature.player

import com.iptv.tv.core.model.CatchUpPlaybackResolution
import com.iptv.tv.core.model.CatchUpPlaybackResolver
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram

internal enum class StableCatchUpActionState {
    HIDDEN,
    AVAILABLE,
    UNAVAILABLE
}

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

    fun state(
        channel: Channel?,
        program: EpgProgram,
        nowMs: Long
    ): StableCatchUpActionState {
        if (channel == null || program.endEpochMs > nowMs) {
            return StableCatchUpActionState.HIDDEN
        }
        val resolution = resolve(channel, program, nowMs)
        return if (resolution?.supported == true && !resolution.playbackUrl.isNullOrBlank()) {
            StableCatchUpActionState.AVAILABLE
        } else {
            StableCatchUpActionState.UNAVAILABLE
        }
    }

    fun isAvailable(
        channel: Channel?,
        program: EpgProgram,
        nowMs: Long
    ): Boolean = state(channel, program, nowMs) == StableCatchUpActionState.AVAILABLE
}
