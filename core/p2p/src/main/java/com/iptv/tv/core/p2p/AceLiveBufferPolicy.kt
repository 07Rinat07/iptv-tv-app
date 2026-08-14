package com.iptv.tv.core.p2p

/**
 * Controls how much media the autonomous Ace Live runtime should retain before exposing the
 * loopback stream to the player.
 *
 * AUTO estimates the actual incoming media rate after the first media bytes arrive and targets a
 * bounded amount of playable time. Discovery/handshake latency is deliberately excluded from the
 * throughput estimate. MANUAL uses an explicit byte threshold. Both modes remain bounded by the
 * live output buffer.
 */
enum class AceLiveBufferMode {
    AUTO,
    MANUAL
}

data class AceLiveBufferSettings(
    val mode: AceLiveBufferMode = AceLiveBufferMode.AUTO,
    val manualStartupBufferBytes: Long = 4L * 1024L * 1024L,
    val autoTargetDurationMillis: Long = 4_000L,
    val autoMinStartupBufferBytes: Long = 1L * 1024L * 1024L,
    val autoMaxStartupBufferBytes: Long = 6L * 1024L * 1024L,
    /** In AUTO mode this budget starts at the first media sample, not at peer discovery. */
    val forcedStartAfterMillis: Long = 12_000L,
    val forcedStartMinBufferBytes: Long = 2L * 1024L * 1024L,
    val outputBufferBytes: Int = 16 * 1024 * 1024,
    val startupTimeoutMillis: Long = 60_000L,
    val mediaStallTimeoutMillis: Long = 20_000L
)

internal data class AceLiveStartupBufferDecision(
    val ready: Boolean,
    val targetBytes: Long,
    val observedBytesPerSecond: Long,
    val bufferedDurationMillis: Long,
    val mediaElapsedMillis: Long,
    val forced: Boolean
)

/**
 * Stateful startup-buffer controller for one Ace Live runtime.
 *
 * The old implementation divided retained bytes by total runtime age. If discovery took 10-20
 * seconds before the first media piece, a healthy multi-megabit stream looked artificially slow and
 * AUTO collapsed toward the minimum threshold. That could expose a HD stream with only a fraction
 * of a second buffered. This controller establishes its clock at the first media sample and updates
 * an EWMA only from subsequent byte growth.
 */
internal class AceLiveStartupBufferPolicy(
    rawSettings: AceLiveBufferSettings
) {
    private val settings = rawSettings.normalized()
    private val lock = Any()

    private var firstMediaElapsedMillis: Long? = null
    private var lastRateSampleElapsedMillis: Long? = null
    private var lastRateSampleBufferedBytes: Long = 0L
    private var ewmaBytesPerSecond: Long = 0L

    fun outputBufferBytes(): Int = settings.outputBufferBytes

    fun startupTimeoutMillis(): Long = settings.startupTimeoutMillis

    fun mediaStallTimeoutMillis(): Long = settings.mediaStallTimeoutMillis

    fun evaluate(bufferedBytes: Long, elapsedMillis: Long): AceLiveStartupBufferDecision =
        synchronized(lock) {
            val buffered = bufferedBytes.coerceAtLeast(0L)
            val elapsed = elapsedMillis.coerceAtLeast(1L)
            val firstMediaAt = firstMediaElapsedMillis ?: elapsed.also {
                firstMediaElapsedMillis = it
                lastRateSampleElapsedMillis = it
                lastRateSampleBufferedBytes = buffered
            }
            val mediaElapsed = (elapsed - firstMediaAt).coerceAtLeast(0L)

            updateThroughputEstimate(buffered, elapsed)
            val observedBytesPerSecond = ewmaBytesPerSecond.coerceAtLeast(0L)

            val targetBytes = when (settings.mode) {
                AceLiveBufferMode.MANUAL -> settings.manualStartupBufferBytes
                AceLiveBufferMode.AUTO -> {
                    val estimated = if (observedBytesPerSecond > 0L) {
                        safeTargetBytes(
                            observedBytesPerSecond = observedBytesPerSecond,
                            targetDurationMillis = settings.autoTargetDurationMillis
                        )
                    } else {
                        settings.autoMinStartupBufferBytes
                    }
                    estimated.coerceIn(
                        settings.autoMinStartupBufferBytes,
                        settings.autoMaxStartupBufferBytes
                    )
                }
            }

            val forced = settings.mode == AceLiveBufferMode.AUTO &&
                mediaElapsed >= settings.forcedStartAfterMillis &&
                buffered >= settings.forcedStartMinBufferBytes
            AceLiveStartupBufferDecision(
                ready = buffered >= targetBytes || forced,
                targetBytes = targetBytes,
                observedBytesPerSecond = observedBytesPerSecond,
                bufferedDurationMillis = safeBufferedDurationMillis(
                    bufferedBytes = buffered,
                    observedBytesPerSecond = observedBytesPerSecond
                ),
                mediaElapsedMillis = mediaElapsed,
                forced = forced
            )
        }

    private fun updateThroughputEstimate(bufferedBytes: Long, elapsedMillis: Long) {
        val previousElapsed = lastRateSampleElapsedMillis ?: run {
            lastRateSampleElapsedMillis = elapsedMillis
            lastRateSampleBufferedBytes = bufferedBytes
            return
        }
        val deltaMillis = elapsedMillis - previousElapsed
        if (deltaMillis < MIN_RATE_SAMPLE_MILLIS) return

        val deltaBytes = (bufferedBytes - lastRateSampleBufferedBytes).coerceAtLeast(0L)
        lastRateSampleElapsedMillis = elapsedMillis
        lastRateSampleBufferedBytes = bufferedBytes
        if (deltaBytes <= 0L) return

        val instantaneous = safeRate(deltaBytes, deltaMillis)
        ewmaBytesPerSecond = if (ewmaBytesPerSecond <= 0L) {
            instantaneous
        } else {
            safeWeightedAverage(
                previous = ewmaBytesPerSecond,
                current = instantaneous,
                currentWeightPercent = EWMA_CURRENT_WEIGHT_PERCENT
            )
        }
    }

    private fun safeRate(bufferedBytes: Long, elapsedMillis: Long): Long {
        if (bufferedBytes <= 0L || elapsedMillis <= 0L) return 0L
        return runCatching {
            Math.multiplyExact(bufferedBytes, 1_000L) / elapsedMillis
        }.getOrElse { Long.MAX_VALUE }
    }

    private fun safeTargetBytes(observedBytesPerSecond: Long, targetDurationMillis: Long): Long {
        if (observedBytesPerSecond <= 0L) return settings.autoMinStartupBufferBytes
        return runCatching {
            Math.multiplyExact(observedBytesPerSecond, targetDurationMillis) / 1_000L
        }.getOrElse { Long.MAX_VALUE }
    }

    private fun safeBufferedDurationMillis(
        bufferedBytes: Long,
        observedBytesPerSecond: Long
    ): Long {
        if (bufferedBytes <= 0L || observedBytesPerSecond <= 0L) return 0L
        return runCatching {
            Math.multiplyExact(bufferedBytes, 1_000L) / observedBytesPerSecond
        }.getOrElse { Long.MAX_VALUE }
    }

    private fun safeWeightedAverage(
        previous: Long,
        current: Long,
        currentWeightPercent: Long
    ): Long {
        val previousWeight = 100L - currentWeightPercent
        return runCatching {
            val oldPart = Math.multiplyExact(previous, previousWeight)
            val newPart = Math.multiplyExact(current, currentWeightPercent)
            Math.addExact(oldPart, newPart) / 100L
        }.getOrElse {
            // Saturation is preferable to wrapping a rate estimate negative.
            maxOf(previous, current)
        }
    }
}

private fun AceLiveBufferSettings.normalized(): AceLiveBufferSettings {
    val boundedOutput = outputBufferBytes.coerceIn(MIN_OUTPUT_BUFFER_BYTES, MAX_OUTPUT_BUFFER_BYTES)
    val maxStartup = (boundedOutput.toLong() - OUTPUT_HEADROOM_BYTES)
        .coerceAtLeast(MIN_STARTUP_BUFFER_BYTES)
    val boundedAutoMin = autoMinStartupBufferBytes
        .coerceIn(MIN_STARTUP_BUFFER_BYTES, maxStartup)
    val boundedAutoMax = autoMaxStartupBufferBytes
        .coerceIn(boundedAutoMin, maxStartup)
    val boundedManual = manualStartupBufferBytes
        .coerceIn(MIN_STARTUP_BUFFER_BYTES, maxStartup)
    // Forced-start is an escape hatch below the adaptive target, so it must not be normalized up
    // to autoMinStartupBufferBytes. It remains independently bounded by the global safety floor and
    // by the output-buffer capacity.
    val boundedForcedMin = forcedStartMinBufferBytes
        .coerceIn(MIN_STARTUP_BUFFER_BYTES, maxStartup)

    return copy(
        manualStartupBufferBytes = boundedManual,
        autoTargetDurationMillis = autoTargetDurationMillis.coerceIn(1_000L, 15_000L),
        autoMinStartupBufferBytes = boundedAutoMin,
        autoMaxStartupBufferBytes = boundedAutoMax,
        forcedStartAfterMillis = forcedStartAfterMillis.coerceIn(3_000L, 120_000L),
        forcedStartMinBufferBytes = boundedForcedMin,
        outputBufferBytes = boundedOutput,
        startupTimeoutMillis = startupTimeoutMillis.coerceIn(10_000L, 180_000L),
        mediaStallTimeoutMillis = mediaStallTimeoutMillis.coerceIn(5_000L, 120_000L)
    )
}

private const val MIN_STARTUP_BUFFER_BYTES = 256L * 1024L
private const val MIN_OUTPUT_BUFFER_BYTES = 4 * 1024 * 1024
private const val MAX_OUTPUT_BUFFER_BYTES = 64 * 1024 * 1024
private const val OUTPUT_HEADROOM_BYTES = 512L * 1024L
private const val MIN_RATE_SAMPLE_MILLIS = 250L
private const val EWMA_CURRENT_WEIGHT_PERCENT = 35L
