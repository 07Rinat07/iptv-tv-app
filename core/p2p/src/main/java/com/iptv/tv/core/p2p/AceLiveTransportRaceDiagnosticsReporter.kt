package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class AceLiveTransportRaceDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit,
    private val context: AceLiveRuntimeDiagnosticsContext,
    private val delegate: P2pRuntimeMetricsReporter = P2pRuntimeMetricsReporter.LOGCAT,
    private val endpointFingerprinter: AceLiveEndpointFingerprinter = AceLiveEndpointFingerprinter.PROCESS_SCOPED
) : P2pRuntimeMetricsReporter {
    override fun report(metric: P2pRuntimeMetric) {
        delegate.reportSafely(metric)
        val race = metric as? AceLiveTransportRaceMetric ?: return
        runCatching {
            observer(STATUS, race.toDiagnosticsMessage(context, endpointFingerprinter))
        }
    }

    private companion object {
        const val STATUS = "embedded_ace_live_transport_race"
    }
}

/**
 * Correlates repeated peer endpoints inside one app process without persisting the endpoint itself.
 * The production key is generated in memory, never exported and changes after process restart.
 */
internal fun interface AceLiveEndpointFingerprinter {
    fun fingerprint(host: String, port: Int): String

    companion object {
        val PROCESS_SCOPED: AceLiveEndpointFingerprinter = ProcessScopedAceLiveEndpointFingerprinter()
    }
}

private class ProcessScopedAceLiveEndpointFingerprinter(
    private val key: ByteArray = ByteArray(HMAC_KEY_BYTES).also { SecureRandom().nextBytes(it) }
) : AceLiveEndpointFingerprinter {
    override fun fingerprint(host: String, port: Int): String =
        hmacEndpointFingerprint(key = key, host = host, port = port)
}

internal fun hmacEndpointFingerprint(
    key: ByteArray,
    host: String,
    port: Int
): String {
    require(key.isNotEmpty()) { "endpoint fingerprint key must not be empty" }

    val normalizedEndpoint = buildString {
        append(host.trim().lowercase(Locale.ROOT))
        append(':')
        append(port)
    }
    val mac = Mac.getInstance(HMAC_ALGORITHM)
    mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
    val digest = mac.doFinal(normalizedEndpoint.toByteArray(StandardCharsets.UTF_8))
    return buildString(FINGERPRINT_BYTES * 2) {
        repeat(FINGERPRINT_BYTES) { index ->
            val value = digest[index].toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }
}

internal fun AceLiveTransportRaceMetric.toDiagnosticsMessage(
    context: AceLiveRuntimeDiagnosticsContext,
    endpointFingerprinter: AceLiveEndpointFingerprinter = AceLiveEndpointFingerprinter.PROCESS_SCOPED
): String = buildString {
    append("winner=")
    append(winner?.wireName ?: "none")
    append(" elapsed_ms=")
    append(elapsedMillis)
    append(" endpoint_fp=")
    append(endpointFingerprinter.fingerprint(endpointHost, endpointPort))
    candidates
        .sortedBy { candidate -> candidate.transport.ordinal }
        .forEach { candidate ->
            append(' ')
            append(candidate.transport.wireName)
            append("_connected_ms=")
            append(candidate.physicalConnectedMillis ?: "none")
            append(' ')
            append(candidate.transport.wireName)
            append("_outcome=")
            append(candidate.outcome.wireName)
            append(' ')
            append(candidate.transport.wireName)
            append("_terminal_ms=")
            append(candidate.terminalElapsedMillis)
        }
    append(" startup_id=")
    append(context.startupId)
    append(" runtime_id=")
    append(context.runtimeId)
    append(" generation=")
    append(context.generation)
    append(" path=")
    append(context.path)
}

private const val HMAC_ALGORITHM = "HmacSHA256"
private const val HMAC_KEY_BYTES = 32
private const val FINGERPRINT_BYTES = 10
private const val HEX = "0123456789abcdef"
