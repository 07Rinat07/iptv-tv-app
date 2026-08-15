package com.iptv.tv.core.p2p

/** One scheduler-facing pressure sample from the currently authoritative loopback consumer. */
internal data class AceLiveAuthoritativeConsumerPressureSample(
    val consumer: AceLiveMediaConsumerSnapshot,
    val pressure: AceLiveBufferPressureSnapshot
)

/**
 * Couples active-consumer ownership with per-reader pressure hysteresis.
 *
 * Loopback readers may overlap briefly during Media3 reconnect/replacement. Only confirmed delivery
 * from the selected reader is allowed to advance scheduler-facing pressure. When the active reader
 * closes, the selector may fall back to the newest still-open reader with confirmed delivery; that
 * ownership transition is surfaced once even without a fresh socket write from the fallback reader.
 */
internal class AceLiveAuthoritativeConsumerPressureTracker(
    settings: AceLiveBufferPressureSettings = AceLiveBufferPressureSettings(),
    maxTrackedReaders: Int = DEFAULT_MAX_TRACKED_READERS
) {
    private val lock = Any()
    private val selector = AceLiveActiveConsumerSelector(maxTrackedReaders)
    private val pressureTracker = AceLiveConsumerBufferPressureTracker(
        settings = settings,
        maxTrackedReaders = maxTrackedReaders
    )
    private var lastAuthoritativeReaderId: Long? = null

    init {
        require(maxTrackedReaders > 0) { "maxTrackedReaders must be positive" }
    }

    fun onEvent(
        event: AceLiveConsumerLifecycleEvent
    ): AceLiveAuthoritativeConsumerPressureSample? = synchronized(lock) {
        val active = selector.onEvent(event)
        val activeReaderId = active?.readerId
        val ownershipChanged = activeReaderId != lastAuthoritativeReaderId
        lastAuthoritativeReaderId = activeReaderId

        val shouldEvaluate = when (event) {
            is AceLiveConsumerLifecycleEvent.Opened -> false
            is AceLiveConsumerLifecycleEvent.Delivered -> activeReaderId == event.readerId
            is AceLiveConsumerLifecycleEvent.Closed -> ownershipChanged && active != null
        }
        if (!shouldEvaluate || active == null) return@synchronized null

        AceLiveAuthoritativeConsumerPressureSample(
            consumer = active,
            pressure = pressureTracker.evaluate(active)
        )
    }

    private companion object {
        const val DEFAULT_MAX_TRACKED_READERS = 8
    }
}
