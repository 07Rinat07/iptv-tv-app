package com.iptv.tv.feature.player

/**
 * User-facing availability snapshot for a Torrent TV / Ace Stream channel.
 *
 * The catalog is never filtered by this state. P2P availability is inherently dynamic, so an
 * unavailable/no-peer result is informational and may change on the next playback attempt.
 */
data class P2pChannelAvailability(
    val state: P2pChannelAvailabilityState = P2pChannelAvailabilityState.UNCHECKED,
    val peers: Int = 0,
    val speedKbps: Int = 0
)

enum class P2pChannelAvailabilityState {
    UNCHECKED,
    SEARCHING,
    PEERS,
    READY,
    PLAYING,
    NO_PEERS,
    ERROR
}

internal fun p2pChannelAvailabilityLabel(status: P2pChannelAvailability?): String {
    val value = status ?: P2pChannelAvailability()
    val peers = value.peers.coerceAtLeast(0)
    val speed = value.speedKbps.coerceAtLeast(0)
    return when (value.state) {
        P2pChannelAvailabilityState.UNCHECKED -> "P2P · не проверен"
        P2pChannelAvailabilityState.SEARCHING ->
            if (peers > 0) "P2P · поиск · пиры $peers" else "P2P · поиск пиров…"
        P2pChannelAvailabilityState.PEERS ->
            buildString {
                append("P2P · пиры ")
                append(peers)
                if (speed > 0) {
                    append(" · ")
                    append(speed)
                    append(" Кбит/с")
                }
            }
        P2pChannelAvailabilityState.READY ->
            if (peers > 0) "P2P · поток готов · пиры $peers" else "P2P · поток готов"
        P2pChannelAvailabilityState.PLAYING ->
            if (peers > 0) "P2P · играет · пиры $peers" else "P2P · играет"
        P2pChannelAvailabilityState.NO_PEERS -> "P2P · нет пиров"
        P2pChannelAvailabilityState.ERROR -> "P2P · ошибка"
    }
}

internal fun p2pAvailabilityFromResolveError(message: String): P2pChannelAvailabilityState {
    val normalized = message.lowercase()
    return if (
        normalized.contains("peer") ||
        normalized.contains("пир") ||
        normalized.contains("не разда") ||
        normalized.contains("no peer")
    ) {
        P2pChannelAvailabilityState.NO_PEERS
    } else {
        P2pChannelAvailabilityState.ERROR
    }
}
