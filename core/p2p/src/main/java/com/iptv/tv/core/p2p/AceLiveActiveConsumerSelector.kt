package com.iptv.tv.core.p2p

/**
 * Lifecycle signal for loopback media consumers.
 *
 * A newly opened reader is not authoritative until it has actually delivered media to its socket.
 * This prevents speculative/replacement HTTP connections from stealing buffer-pressure ownership
 * before Media3 has consumed any bytes from them.
 */
internal enum class AceLiveConsumerCloseReason(val wireName: String) {
    END_OF_STREAM("end_of_stream"),
    CLIENT_DISCONNECTED("client_disconnected"),
    SERVER_CLOSED("server_closed"),
    SOURCE_IO("source_io"),
    UNKNOWN("unknown")
}

internal sealed interface AceLiveConsumerLifecycleEvent {
    val readerId: Long

    data class Opened(
        override val readerId: Long,
        val method: String = "GET",
        val rangeHeader: String? = null,
        val requestedStartOffset: Long? = null,
        val actualStartOffset: Long = 0L,
        val liveEdgeOffset: Long = 0L
    ) : AceLiveConsumerLifecycleEvent

    data class Delivered(
        val consumer: AceLiveMediaConsumerSnapshot
    ) : AceLiveConsumerLifecycleEvent {
        override val readerId: Long
            get() = consumer.readerId
    }

    data class Closed(
        override val readerId: Long,
        val reason: AceLiveConsumerCloseReason = AceLiveConsumerCloseReason.UNKNOWN,
        val totalDeliveredBytes: Long = 0L,
        val durationMillis: Long = 0L
    ) : AceLiveConsumerLifecycleEvent
}

/**
 * Selects the one loopback reader whose confirmed headroom is authoritative for future scheduling.
 *
 * Media3 can briefly overlap an old HTTP reader with a replacement. Selection therefore follows
 * confirmed delivery rather than connection-open order:
 * - OPENED never preempts the current consumer;
 * - the first DELIVERED snapshot from a newer reader performs a monotonic handoff;
 * - later delivery from an older reader cannot steal ownership back while the newer reader is open;
 * - when the active reader closes, the newest still-open reader with confirmed delivery is selected;
 * - state is bounded so abandoned HTTP readers cannot accumulate indefinitely.
 *
 * This class deliberately changes no request depth, peer refill, recovery or timeout policy. It is
 * the ownership primitive required before buffer pressure can safely drive those policies.
 */
internal class AceLiveActiveConsumerSelector(
    private val maxTrackedReaders: Int = DEFAULT_MAX_TRACKED_READERS
) {
    private val lock = Any()
    private val readers = linkedMapOf<Long, ReaderState>()
    private var activeReaderId: Long? = null

    init {
        require(maxTrackedReaders > 0) { "maxTrackedReaders must be positive" }
    }

    fun onEvent(event: AceLiveConsumerLifecycleEvent): AceLiveMediaConsumerSnapshot? =
        synchronized(lock) {
            require(event.readerId > 0L) { "readerId must be positive" }
            when (event) {
                is AceLiveConsumerLifecycleEvent.Opened -> onOpenedLocked(event.readerId)
                is AceLiveConsumerLifecycleEvent.Delivered -> onDeliveredLocked(event.consumer)
                is AceLiveConsumerLifecycleEvent.Closed -> onClosedLocked(event.readerId)
            }
            pruneLocked()
            activeSnapshotLocked()
        }

    fun activeSnapshot(): AceLiveMediaConsumerSnapshot? = synchronized(lock) {
        activeSnapshotLocked()
    }

    internal fun trackedReaderCount(): Int = synchronized(lock) { readers.size }

    private fun onOpenedLocked(readerId: Long) {
        readers.getOrPut(readerId) { ReaderState() }.open = true
    }

    private fun onDeliveredLocked(consumer: AceLiveMediaConsumerSnapshot) {
        val state = readers.getOrPut(consumer.readerId) { ReaderState() }
        state.open = true
        state.lastDelivered = consumer

        val current = activeReaderId
        if (current == null || consumer.readerId >= current) {
            activeReaderId = consumer.readerId
        }
    }

    private fun onClosedLocked(readerId: Long) {
        readers[readerId]?.open = false
        if (activeReaderId == readerId) {
            activeReaderId = readers.entries
                .asSequence()
                .filter { (_, state) -> state.open && state.lastDelivered != null }
                .maxOfOrNull { (id, _) -> id }
        }
    }

    private fun activeSnapshotLocked(): AceLiveMediaConsumerSnapshot? {
        val id = activeReaderId ?: return null
        val state = readers[id]
        if (state?.open != true) return null
        return state.lastDelivered
    }

    private fun pruneLocked() {
        while (readers.size > maxTrackedReaders) {
            val removableClosed = readers.entries.firstOrNull { (id, state) ->
                id != activeReaderId && !state.open
            }
            if (removableClosed != null) {
                readers.remove(removableClosed.key)
                continue
            }

            val removableNonActive = readers.keys.firstOrNull { id -> id != activeReaderId }
                ?: break
            readers.remove(removableNonActive)
        }
    }

    private class ReaderState(
        var open: Boolean = false,
        var lastDelivered: AceLiveMediaConsumerSnapshot? = null
    )

    private companion object {
        const val DEFAULT_MAX_TRACKED_READERS = 8
    }
}
