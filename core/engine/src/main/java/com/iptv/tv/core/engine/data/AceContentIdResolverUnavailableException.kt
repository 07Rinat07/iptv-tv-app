package com.iptv.tv.core.engine.data

/**
 * Signals that no resolver capable of translating an Ace `content_id` is currently available.
 *
 * The exception deliberately names the resolver capability rather than Ace Engine itself. Today
 * the external Ace Engine is the compatibility implementation, while a future embedded resolver
 * can satisfy the same contract without changing player/domain error handling.
 */
class AceContentIdResolverUnavailableException(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null
) : IllegalStateException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE =
            "Ace content_id resolver is unavailable; an available Ace-specific resolver is required"
    }
}
