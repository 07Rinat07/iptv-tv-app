package com.iptv.tv.core.p2p

/** Buffer-pressure state used as a future input to adaptive Ace Live scheduling. */
internal enum class AceLiveBufferPressure {
    CRITICAL,
    LOW,
    TARGET,
    HIGH
}

/** Which measurement currently drives buffer-pressure classification. */
internal enum class AceLiveBufferPressureSignal {
    BYTES,
    DURATION
}

/**
 * Three boundaries split playable headroom into four pressure zones.
 *
 * When a trustworthy consumer rate is available, duration boundaries are authoritative. Until then,
 * the controller falls back to conservative byte boundaries. Hysteresis is symmetric around every
 * boundary so small oscillations cannot repeatedly flip scheduler-facing state.
 */
internal data class AceLiveBufferPressureSettings(
    val criticalBoundaryDurationMillis: Long = 1_500L,
    val targetBoundaryDurationMillis: Long = 4_000L,
    val highBoundaryDurationMillis: Long = 8_000L,
    val durationHysteresisMillis: Long = 500L,
    val criticalBoundaryBytes: Long = 512L * 1024L,
    val targetBoundaryBytes: Long = 2L * 1024L * 1024L,
    val highBoundaryBytes: Long = 4L * 1024L * 1024L,
    val bytesHysteresis: Long = 256L * 1024L
) {
    init {
        require(criticalBoundaryDurationMillis > 0L) {
            "criticalBoundaryDurationMillis must be positive"
        }
        require(targetBoundaryDurationMillis > criticalBoundaryDurationMillis) {
            "targetBoundaryDurationMillis must exceed the critical boundary"
        }
        require(highBoundaryDurationMillis > targetBoundaryDurationMillis) {
            "highBoundaryDurationMillis must exceed the target boundary"
        }
        require(durationHysteresisMillis >= 0L) {
            "durationHysteresisMillis must be non-negative"
        }
        require(durationHysteresisMillis <=
            (targetBoundaryDurationMillis - criticalBoundaryDurationMillis) / 2L) {
            "duration hysteresis overlaps critical/target boundaries"
        }
        require(durationHysteresisMillis <=
            (highBoundaryDurationMillis - targetBoundaryDurationMillis) / 2L) {
            "duration hysteresis overlaps target/high boundaries"
        }

        require(criticalBoundaryBytes > 0L) { "criticalBoundaryBytes must be positive" }
        require(targetBoundaryBytes > criticalBoundaryBytes) {
            "targetBoundaryBytes must exceed the critical boundary"
        }
        require(highBoundaryBytes > targetBoundaryBytes) {
            "highBoundaryBytes must exceed the target boundary"
        }
        require(bytesHysteresis >= 0L) { "bytesHysteresis must be non-negative" }
        require(bytesHysteresis <= (targetBoundaryBytes - criticalBoundaryBytes) / 2L) {
            "byte hysteresis overlaps critical/target boundaries"
        }
        require(bytesHysteresis <= (highBoundaryBytes - targetBoundaryBytes) / 2L) {
            "byte hysteresis overlaps target/high boundaries"
        }
    }
}

internal data class AceLiveBufferPressureSnapshot(
    val pressure: AceLiveBufferPressure,
    val signal: AceLiveBufferPressureSignal,
    val playableBytes: Long,
    val playableDurationMillis: Long?,
    val consumerBytesPerSecond: Long?,
    val criticalBoundaryBytes: Long,
    val targetBoundaryBytes: Long,
    val highBoundaryBytes: Long,
    val criticalBoundaryDurationMillis: Long,
    val targetBoundaryDurationMillis: Long,
    val highBoundaryDurationMillis: Long
)

/**
 * Stateful pressure classifier for one live consumer.
 *
 * This class deliberately does not read [AceLiveMediaBuffer.retainedBytes]: that value describes the
 * retained sliding storage window, not the unread headroom of a particular player connection. The
 * caller must provide explicit [playableBytes] derived from consumer-cursor telemetry. V3a keeps
 * this controller pure; later wiring can feed its stable state into request-depth/refill policy.
 */
internal class AceLiveBufferController(
    private val settings: AceLiveBufferPressureSettings = AceLiveBufferPressureSettings()
) {
    private val lock = Any()
    private var pressure = AceLiveBufferPressure.CRITICAL
    private var signal: AceLiveBufferPressureSignal? = null

    fun evaluate(
        playableBytes: Long,
        consumerBytesPerSecond: Long? = null
    ): AceLiveBufferPressureSnapshot = synchronized(lock) {
        val bytes = playableBytes.coerceAtLeast(0L)
        val rate = consumerBytesPerSecond?.takeIf { it > 0L }
        val duration = rate?.let { safeDurationMillis(bytes, it) }
        val nextSignal = if (duration != null) {
            AceLiveBufferPressureSignal.DURATION
        } else {
            AceLiveBufferPressureSignal.BYTES
        }

        // A signal change invalidates the previous hysteresis band because byte and duration
        // boundaries are intentionally independent fallback models.
        if (signal != nextSignal) {
            pressure = classifyWithoutHysteresis(
                value = duration ?: bytes,
                criticalBoundary = if (duration != null) {
                    settings.criticalBoundaryDurationMillis
                } else {
                    settings.criticalBoundaryBytes
                },
                targetBoundary = if (duration != null) {
                    settings.targetBoundaryDurationMillis
                } else {
                    settings.targetBoundaryBytes
                },
                highBoundary = if (duration != null) {
                    settings.highBoundaryDurationMillis
                } else {
                    settings.highBoundaryBytes
                }
            )
            signal = nextSignal
        } else {
            pressure = classifyWithHysteresis(
                current = pressure,
                value = duration ?: bytes,
                criticalBoundary = if (duration != null) {
                    settings.criticalBoundaryDurationMillis
                } else {
                    settings.criticalBoundaryBytes
                },
                targetBoundary = if (duration != null) {
                    settings.targetBoundaryDurationMillis
                } else {
                    settings.targetBoundaryBytes
                },
                highBoundary = if (duration != null) {
                    settings.highBoundaryDurationMillis
                } else {
                    settings.highBoundaryBytes
                },
                hysteresis = if (duration != null) {
                    settings.durationHysteresisMillis
                } else {
                    settings.bytesHysteresis
                }
            )
        }

        AceLiveBufferPressureSnapshot(
            pressure = pressure,
            signal = nextSignal,
            playableBytes = bytes,
            playableDurationMillis = duration,
            consumerBytesPerSecond = rate,
            criticalBoundaryBytes = settings.criticalBoundaryBytes,
            targetBoundaryBytes = settings.targetBoundaryBytes,
            highBoundaryBytes = settings.highBoundaryBytes,
            criticalBoundaryDurationMillis = settings.criticalBoundaryDurationMillis,
            targetBoundaryDurationMillis = settings.targetBoundaryDurationMillis,
            highBoundaryDurationMillis = settings.highBoundaryDurationMillis
        )
    }

    private fun classifyWithoutHysteresis(
        value: Long,
        criticalBoundary: Long,
        targetBoundary: Long,
        highBoundary: Long
    ): AceLiveBufferPressure = when {
        value < criticalBoundary -> AceLiveBufferPressure.CRITICAL
        value < targetBoundary -> AceLiveBufferPressure.LOW
        value < highBoundary -> AceLiveBufferPressure.TARGET
        else -> AceLiveBufferPressure.HIGH
    }

    private fun classifyWithHysteresis(
        current: AceLiveBufferPressure,
        value: Long,
        criticalBoundary: Long,
        targetBoundary: Long,
        highBoundary: Long,
        hysteresis: Long
    ): AceLiveBufferPressure {
        val criticalLow = saturatingSubtract(criticalBoundary, hysteresis)
        val criticalHigh = saturatingAdd(criticalBoundary, hysteresis)
        val targetLow = saturatingSubtract(targetBoundary, hysteresis)
        val targetHigh = saturatingAdd(targetBoundary, hysteresis)
        val highLow = saturatingSubtract(highBoundary, hysteresis)
        val highHigh = saturatingAdd(highBoundary, hysteresis)

        return when (current) {
            AceLiveBufferPressure.CRITICAL -> when {
                value >= highHigh -> AceLiveBufferPressure.HIGH
                value >= targetHigh -> AceLiveBufferPressure.TARGET
                value >= criticalHigh -> AceLiveBufferPressure.LOW
                else -> AceLiveBufferPressure.CRITICAL
            }

            AceLiveBufferPressure.LOW -> when {
                value >= highHigh -> AceLiveBufferPressure.HIGH
                value >= targetHigh -> AceLiveBufferPressure.TARGET
                value < criticalLow -> AceLiveBufferPressure.CRITICAL
                else -> AceLiveBufferPressure.LOW
            }

            AceLiveBufferPressure.TARGET -> when {
                value >= highHigh -> AceLiveBufferPressure.HIGH
                value < criticalLow -> AceLiveBufferPressure.CRITICAL
                value < targetLow -> AceLiveBufferPressure.LOW
                else -> AceLiveBufferPressure.TARGET
            }

            AceLiveBufferPressure.HIGH -> when {
                value < criticalLow -> AceLiveBufferPressure.CRITICAL
                value < targetLow -> AceLiveBufferPressure.LOW
                value < highLow -> AceLiveBufferPressure.TARGET
                else -> AceLiveBufferPressure.HIGH
            }
        }
    }

    private fun safeDurationMillis(bytes: Long, bytesPerSecond: Long): Long {
        if (bytes <= 0L || bytesPerSecond <= 0L) return 0L
        return runCatching {
            Math.multiplyExact(bytes, 1_000L) / bytesPerSecond
        }.getOrElse { Long.MAX_VALUE }
    }

    private fun saturatingAdd(value: Long, delta: Long): Long =
        if (delta <= Long.MAX_VALUE - value) value + delta else Long.MAX_VALUE

    private fun saturatingSubtract(value: Long, delta: Long): Long =
        if (delta >= value) 0L else value - delta
}
