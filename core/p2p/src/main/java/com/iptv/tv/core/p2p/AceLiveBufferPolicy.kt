package com.iptv.tv.core.p2p

/**
 * Controls how much media the autonomous Ace Live runtime should retain before exposing the
 * loopback stream to the player.
 *
 * AUTO estimates the current incoming media rate and targets a few seconds of playable data.
 * MANUAL uses an explicit byte threshold. Both modes remain bounded by the live output buffer.
 */
enum class AceLiveBufferMode {
    AUTO,
    MANUAL
}

data class AceLiveBufferSettings(
    val mode: AceLiveBufferMode = AceLiveBufferMode.AUTO,
    val manualStartupBufferBytes: Long = 4L * 1024L * 1024L,
    val autoTargetDurationMillis: Long = 3_000L,
    val autoMinStartupBufferBytes: Long = 512L * 1024L,
    val autoMaxStartupBufferBytes: Long = 4L * 1024L * 1024L,
    val forcedStartAfterMillis: Long = 20_000L,
    val forcedStartMinBufferBytes: Long = 512L * 1024L,
    val outputBufferBytes: Int = 16 * 1024 * 1024,
    val startupTimeoutMillis: Long = 60_000L,
    val mediaStallTimeoutMillis: Long = 20_000L
)

internal data class AceLiveStartupBufferDecision(
    val ready: Boolean,
    val targetBytes: Long,
    val observedBytesPerSecond: Long,
    val forced: Boolean
)

/**
 * Pure startup-buffer planner so the network runtime can be tested independently from sockets.
 */
internal class AceLiveStartupBufferPolicy(
    rawSettings: AceLiveBufferSettings
) {
    private val settings = rawSettings.normalized()

    fun outputBufferBytes(): Int = settings.outputBufferBytes

    fun startupTimeoutMillis(): Long = settings.startupTimeoutMillis

    fun mediaStallTimeoutMillis(): Long = settings.mediaStallTimeoutMillis

    fun evaluate(bufferedBytes: Long, elapsedMillis: Long): AceLiveStartupBufferDecision {
        val buffered = bufferedBytes.coerceAtLeast(0L)
        val elapsed = elapsedMillis.coerceAtLeast(1L)
        val observedBytesPerSecond = safeRate(buffered, elapsed)

        val targetBytes = when (settings.mode) {
            AceLiveBufferMode.MANUAL -> settings.manualStartupBufferBytes
            AceLiveBufferMode.AUTO -> {
                val estimated = safeTargetBytes(
                    observedBytesPerSecond = observedBytesPerSecond,
                    targetDurationMillis = settings.autoTargetDurationMillis
                )
                estimated.coerceIn(
                    settings.autoMinStartupBufferBytes,
                    settings.autoMaxStartupBufferBytes
                )
            }
        }

        val forced = elapsed >= settings.forcedStartAfterMillis &&
            buffered >= settings.forcedStartMinBufferBytes
        return AceLiveStartupBufferDecision(
            ready = buffered >= targetBytes || forced,
            targetBytes = targetBytes,
            observedBytesPerSecond = observedBytesPerSecond,
            forced = forced
        )
    }

    private fun safeRate(bufferedBytes: Long, elapsedMillis: Long): Long {
        if (bufferedBytes <= 0L) return 0L
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
    val boundedForcedMin = forcedStartMinBufferBytes
        .coerceIn(MIN_STARTUP_BUFFER_BYTES, maxStartup)

    return copy(
        manualStartupBufferBytes = boundedManual,
        autoTargetDurationMillis = autoTargetDurationMillis.coerceIn(500L, 15_000L),
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
