package com.iptv.tv.core.p2p

/**
 * Keeps the stateful buffer-pressure hysteresis isolated per loopback consumer.
 *
 * Media3 can briefly overlap an old and a replacement HTTP reader. Sharing one
 * [AceLiveBufferController] across those readers would let one client's hysteresis affect another
 * client's classification. This bounded LRU tracker gives every reader an independent controller.
 */
internal class AceLiveConsumerBufferPressureTracker(
    private val settings: AceLiveBufferPressureSettings = AceLiveBufferPressureSettings(),
    private val maxTrackedReaders: Int = DEFAULT_MAX_TRACKED_READERS
) {
    private val lock = Any()
    private val controllers = linkedMapOf<Long, AceLiveBufferController>()

    init {
        require(maxTrackedReaders > 0) { "maxTrackedReaders must be positive" }
    }

    fun evaluate(consumer: AceLiveMediaConsumerSnapshot): AceLiveBufferPressureSnapshot =
        synchronized(lock) {
            val controller = controllers.remove(consumer.readerId)
                ?: AceLiveBufferController(settings)
            controllers[consumer.readerId] = controller
            while (controllers.size > maxTrackedReaders) {
                val oldestReaderId = controllers.keys.firstOrNull() ?: break
                controllers.remove(oldestReaderId)
            }
            controller.evaluate(
                playableBytes = consumer.playableBytes,
                consumerBytesPerSecond = consumer.consumerBytesPerSecond
            )
        }

    private companion object {
        const val DEFAULT_MAX_TRACKED_READERS = 8
    }
}
