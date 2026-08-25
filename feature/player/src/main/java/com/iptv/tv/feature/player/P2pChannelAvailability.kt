package com.iptv.tv.feature.player

import androidx.compose.runtime.mutableStateMapOf

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

/**
 * Process-local UI cache of the last observed result for channels the user actually tried.
 *
 * We intentionally do not probe the whole catalog: doing so would create hundreds of concurrent
 * tracker/DHT operations for a large Torrent TV list. Untouched channels stay UNCHECKED.
 */
internal object P2pChannelAvailabilityUiCache {
    val statuses = mutableStateMapOf<Long, P2pChannelAvailability>()

    /** A superseded playback request must not leave another channel visibly searching forever. */
    fun resetSupersededSearches(activeChannelId: Long?) {
        statuses.entries
            .filter { (channelId, status) ->
                channelId != activeChannelId &&
                    status.state == P2pChannelAvailabilityState.SEARCHING
            }
            .map { it.key }
            .forEach { channelId -> statuses[channelId] = P2pChannelAvailability() }
    }

    fun beginSearch(
        channelId: Long,
        peers: Int = 0,
        speedKbps: Int = 0
    ) {
        resetSupersededSearches(activeChannelId = channelId)
        mark(
            channelId = channelId,
            state = if (peers > 0) P2pChannelAvailabilityState.PEERS else P2pChannelAvailabilityState.SEARCHING,
            peers = peers,
            speedKbps = speedKbps
        )
    }

    fun mark(
        channelId: Long,
        state: P2pChannelAvailabilityState,
        peers: Int = statuses[channelId]?.peers ?: 0,
        speedKbps: Int = statuses[channelId]?.speedKbps ?: 0
    ) {
        statuses[channelId] = P2pChannelAvailability(
            state = state,
            peers = peers.coerceAtLeast(0),
            speedKbps = speedKbps.coerceAtLeast(0)
        )
    }
}

internal fun p2pChannelAvailabilityLabel(status: P2pChannelAvailability?): String {
    val value = status ?: P2pChannelAvailability()
    val peers = value.peers.coerceAtLeast(0)
    val speed = value.speedKbps.coerceAtLeast(0)
    return when (value.state) {
        P2pChannelAvailabilityState.UNCHECKED -> "P2P · не проверен"
        P2pChannelAvailabilityState.SEARCHING ->
            if (peers > 0) "P2P · поиск · $peers пир." else "P2P · поиск пиров…"
        P2pChannelAvailabilityState.PEERS ->
            buildString {
                append("P2P · ")
                append(peers)
                append(if (peers == 1) " пир" else " пира")
                if (speed > 0) {
                    append(" · ")
                    append(speed)
                    append(" Кбит/с")
                } else {
                    append(" · подключение…")
                }
            }
        P2pChannelAvailabilityState.READY ->
            if (peers > 0) "P2P · поток готов · $peers пир." else "P2P · поток готов"
        P2pChannelAvailabilityState.PLAYING ->
            buildString {
                append("P2P · играет")
                if (peers > 0) {
                    append(" · ")
                    append(peers)
                    append(" пир.")
                }
                if (speed > 0) {
                    append(" · ")
                    append(speed)
                    append(" Кбит/с")
                }
            }
        P2pChannelAvailabilityState.NO_PEERS -> "P2P · нет доступных пиров"
        P2pChannelAvailabilityState.ERROR ->
            if (peers > 0) "P2P · $peers пир. · нет данных" else "P2P · ошибка"
    }
}

internal fun p2pAvailabilityFromResolveError(message: String): P2pChannelAvailabilityState {
    val normalized = message.lowercase()
    if (
        normalized.contains("пир был найден") &&
        normalized.contains("данные потока не поступили")
    ) {
        return P2pChannelAvailabilityState.ERROR
    }
    return if (
        normalized.contains("did not connect to any peer") ||
        normalized.contains("доступный пир не найден") ||
        normalized.contains("не разда") ||
        normalized.contains("no peer")
    ) {
        P2pChannelAvailabilityState.NO_PEERS
    } else {
        P2pChannelAvailabilityState.ERROR
    }
}
