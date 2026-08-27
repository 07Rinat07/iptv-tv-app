package com.iptv.tv.feature.player

/**
 * Media3 policy for the embedded P2P localhost transport.
 *
 * A localhost URL belongs to exactly one prepared P2P runtime. Re-preparing the same URL after
 * that runtime reaches EOF or is closed cannot recover the stream; the owner must prepare a new
 * P2P runtime and therefore a new URL/session instead.
 */
internal object StableP2pMedia3RecoveryPolicy {
    const val MIN_LOADABLE_RETRY_COUNT = 0

    fun playerLifecycleKey(isP2pPlayback: Boolean, sessionId: Long): Long =
        if (isP2pPlayback) sessionId else 0L

    fun shouldReprepareSameSource(isP2pPlayback: Boolean): Boolean = !isP2pPlayback
}
