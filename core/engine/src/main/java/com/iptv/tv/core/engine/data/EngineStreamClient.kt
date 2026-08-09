package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.engine.api.EngineStreamApi
import com.iptv.tv.core.model.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton

@Singleton
class EngineStreamClient(
    private val api: EngineStreamApi,
    private val serviceConnector: AceStreamServiceConnector? = null
) {
    private val status = MutableStateFlow(
        EngineStatus(
            connected = false,
            peers = 0,
            speedKbps = 0,
            message = "Engine not connected"
        )
    )
    private val runtimeDiagnostics = MutableStateFlow(AceRuntimeDiagnostics())

    private val enginePlayerId = "engineProxy-${Random().nextInt()}"
    private val clientSessionIds = AtomicInteger(0)

    private var connectedEndpoint: String? = null
    private var connectedAccessToken: String? = null
    private var connectedPackageName: String? = null
    private var connectedRoute: String? = null

    fun observeStatus(): StateFlow<EngineStatus> = status.asStateFlow()

    fun observeRuntimeDiagnostics(): StateFlow<AceRuntimeDiagnostics> =
        runtimeDiagnostics.asStateFlow()

    suspend fun connect(endpoint: String): AppResult<Unit> {
        val normalized = normalizeEndpoint(endpoint)
            ?: return AppResult.Error("Engine endpoint is empty")
        val preservingCompatibilityFallback =
            runtimeDiagnostics.value.stage == AceRuntimeStage.COMPATIBILITY_FALLBACK
        if (!preservingCompatibilityFallback) {
            runtimeDiagnostics.value = AceRuntimeDiagnostics()
        }

        return when (val result = fetchStatus(normalized)) {
            is AppResult.Success -> {
                connectedEndpoint = normalized
                connectedAccessToken = null
                connectedPackageName = if (preservingCompatibilityFallback) {
                    "loopback_http"
                } else {
                    "manual_endpoint"
                }
                connectedRoute = if (preservingCompatibilityFallback) {
                    "loopback_compatibility"
                } else {
                    "manual_endpoint"
                }
                status.value = result.data.copy(
                    connected = true,
                    message = runtimeMessageOr("Connected: $normalized")
                )
                AppResult.Success(Unit)
            }

            is AppResult.Error -> {
                status.value = status.value.copy(
                    connected = false,
                    peers = 0,
                    speedKbps = 0,
                    message = runtimeMessageOr("Engine connect failed: ${result.message}")
                )
                result
            }

            AppResult.Loading -> AppResult.Loading
        }
    }

    suspend fun connectInstalledEngine(): AppResult<Unit> {
        val connector = serviceConnector
            ?: return AppResult.Error("Ace Stream service connector is not configured")
        return when (val result = connector.ensureStarted()) {
            is AppResult.Success -> {
                connectedEndpoint = result.data.endpoint
                connectedAccessToken = result.data.accessToken
                connectedPackageName = result.data.packageName
                connectedRoute = "installed_engine"
                when (val statusResult = fetchStatus(result.data.endpoint)) {
                    is AppResult.Success -> {
                        status.value = statusResult.data.copy(
                            connected = true,
                            message = runtimeMessageOr("Connected: ${result.data.packageName}")
                        )
                        AppResult.Success(Unit)
                    }
                    is AppResult.Error -> statusResult
                    AppResult.Loading -> AppResult.Loading
                }
            }
            is AppResult.Error -> {
                status.value = status.value.copy(
                    connected = false,
                    peers = 0,
                    speedKbps = 0,
                    message = runtimeMessageOr(result.message)
                )
                result
            }
            AppResult.Loading -> AppResult.Loading
        }
    }

    suspend fun refreshStatus(): AppResult<EngineStatus> {
        val endpoint = connectedEndpoint ?: return AppResult.Error("Engine not connected")
        return when (val result = fetchStatus(endpoint)) {
            is AppResult.Success -> {
                status.value = result.data.copy(
                    connected = true,
                    message = runtimeMessageOr("Connected: $endpoint")
                )
                AppResult.Success(status.value)
            }

            is AppResult.Error -> {
                status.value = status.value.copy(
                    connected = false,
                    peers = 0,
                    speedKbps = 0,
                    message = runtimeMessageOr("Engine status error: ${result.message}")
                )
                result
            }

            AppResult.Loading -> AppResult.Loading
        }
    }

    /**
     * Resolve a true Ace content id to the complete transport metadata reported by Ace Engine.
     *
     * The official SDK keeps content id, infohash, media files and dumped transport-file data as
     * separate fields. Mirroring that shape lets routing code distinguish ordinary BitTorrent VOD
     * from Ace live transport without guessing from one recursively discovered value.
     */
    suspend fun resolveContentIdMetadata(rawSource: String): AppResult<AceTransportMetadata> {
        val descriptor = AceStreamDescriptorParser.parse(rawSource)
        if (descriptor !is AceStreamDescriptor.ContentId) {
            publishRuntime(
                AceRuntimeDiagnostics(
                    stage = AceRuntimeStage.ERROR,
                    descriptorKind = descriptorKind(descriptor),
                    provider = "external_engine",
                    route = connectedRoute ?: "installed_engine",
                    failureCode = "content_id_required"
                )
            )
            return AppResult.Error("Ace Stream content id is required for metadata resolution")
        }

        val previousRuntime = runtimeDiagnostics.value
        val preserveFallback = previousRuntime.stage == AceRuntimeStage.COMPATIBILITY_FALLBACK
        publishRuntime(
            AceRuntimeDiagnostics(
                stage = AceRuntimeStage.RESOLVING_ENDPOINT,
                descriptorKind = "content_id",
                provider = "external_engine",
                route = if (preserveFallback) {
                    previousRuntime.route ?: "loopback_compatibility"
                } else {
                    connectedRoute ?: "installed_engine"
                },
                enginePackage = if (preserveFallback) previousRuntime.enginePackage else connectedPackageName,
                endpoint = if (preserveFallback) previousRuntime.endpoint else diagnosticEndpoint(connectedEndpoint),
                failureCode = if (preserveFallback) previousRuntime.failureCode else null
            )
        )

        val endpoint = when (val local = ensureEndpoint()) {
            is AppResult.Success -> local.data
            is AppResult.Error -> {
                publishRuntime(
                    runtimeDiagnostics.value.copy(
                        stage = AceRuntimeStage.ERROR,
                        enginePackage = connectedPackageName,
                        endpoint = diagnosticEndpoint(connectedEndpoint),
                        failureCode = "endpoint_unavailable"
                    )
                )
                return local
            }
            AppResult.Loading -> return AppResult.Loading
        }

        publishRuntime(
            runtimeDiagnostics.value.copy(
                stage = AceRuntimeStage.REQUESTING_METADATA,
                route = connectedRoute ?: runtimeDiagnostics.value.route ?: "installed_engine",
                enginePackage = connectedPackageName,
                endpoint = diagnosticEndpoint(endpoint),
                failureCode = null
            ),
            connected = true
        )

        val options = buildMap {
            put("api_version", "2")
            put("method", "get_media_files")
            put("content_id", descriptor.value)
            put("mode", "full")
            put("expand_wrapper", "1")
            put("dump_transport_file", "1")
        }

        return runCatching {
            val response = api.resolve(
                url = buildServerApiUrl(endpoint),
                options = options
            )
            val metadata = AceTransportMetadataParser.parse(response)
            val transport = metadata.transportType
                ?: metadata.files.firstNotNullOfOrNull { it.transportType }

            publishRuntime(
                runtimeDiagnostics.value.copy(
                    stage = AceRuntimeStage.METADATA_READY,
                    transportType = transport,
                    isLive = metadata.isLive,
                    failureCode = null
                ),
                connected = true
            )
            AppResult.Success(metadata)
        }.getOrElse { throwable ->
            publishRuntime(
                runtimeDiagnostics.value.copy(
                    stage = AceRuntimeStage.ERROR,
                    failureCode = throwableFailureCode(throwable)
                )
            )
            AppResult.Error(
                "Ace content metadata request failed: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }
    }

    /**
     * Compatibility helper for callers that only need a standard BitTorrent hash.
     * Live and non-BitTorrent transports deliberately return an error here.
     */
    suspend fun resolveContentIdInfoHash(rawSource: String): AppResult<String> {
        return when (val resolved = resolveContentIdMetadata(rawSource)) {
            is AppResult.Success -> {
                val metadata = resolved.data
                val hash = metadata.embeddedBitTorrentInfoHash
                when {
                    metadata.isLive -> AppResult.Error(
                        "Ace live transport requires the Ace live protocol and cannot use embedded BitTorrent"
                    )
                    metadata.transportType != null && metadata.transportType != "bt" -> AppResult.Error(
                        "Ace content id resolved to unsupported transport type: ${metadata.transportType}"
                    )
                    hash == null -> AppResult.Error(
                        "Ace content id metadata did not contain a valid BitTorrent infohash"
                    )
                    else -> AppResult.Success(hash)
                }
            }
            is AppResult.Error -> resolved
            AppResult.Loading -> AppResult.Loading
        }
    }

    /**
     * Resolve an Ace descriptor through the current Ace Engine playback control API.
     *
     * `/ace/getstream?format=json` is a control endpoint. It returns JSON containing the local
     * `response.playback_url`; only that URL is handed to Media3/LibVLC. The control endpoint
     * itself must never be treated as a playable media URL.
     */
    suspend fun resolveStream(rawSource: String): AppResult<String> {
        val descriptor = AceStreamDescriptorParser.parse(rawSource)
        if (descriptor is AceStreamDescriptor.Direct) {
            runtimeDiagnostics.value = AceRuntimeDiagnostics()
            return if (descriptor.value.isBlank()) {
                AppResult.Error("Empty stream source")
            } else {
                AppResult.Success(descriptor.value)
            }
        }

        publishRuntime(
            AceRuntimeDiagnostics(
                stage = AceRuntimeStage.RESOLVING_ENDPOINT,
                descriptorKind = descriptorKind(descriptor),
                provider = "external_engine",
                route = connectedRoute ?: "installed_engine",
                enginePackage = connectedPackageName,
                endpoint = diagnosticEndpoint(connectedEndpoint)
            )
        )

        val endpoint = when (val local = ensureEndpoint()) {
            is AppResult.Success -> local.data
            is AppResult.Error -> {
                publishRuntime(
                    runtimeDiagnostics.value.copy(
                        stage = AceRuntimeStage.ERROR,
                        enginePackage = connectedPackageName,
                        endpoint = diagnosticEndpoint(connectedEndpoint),
                        failureCode = "endpoint_unavailable"
                    )
                )
                return local
            }
            AppResult.Loading -> return AppResult.Loading
        }

        // A loopback media URL without a descriptor query is already a resolved engine URL.
        if (descriptor is AceStreamDescriptor.LocalEngineUrl) {
            publishRuntime(
                runtimeDiagnostics.value.copy(
                    stage = AceRuntimeStage.PLAYBACK_READY,
                    route = "local_engine_url",
                    enginePackage = connectedPackageName,
                    endpoint = diagnosticEndpoint(endpoint),
                    playbackTarget = sanitizeAceHttpUrl(descriptor.value),
                    failureCode = null
                ),
                connected = true
            )
            return AppResult.Success(descriptor.value)
        }

        publishRuntime(
            runtimeDiagnostics.value.copy(
                stage = AceRuntimeStage.REQUESTING_PLAYBACK,
                route = connectedRoute ?: "installed_engine",
                enginePackage = connectedPackageName,
                endpoint = diagnosticEndpoint(endpoint),
                failureCode = null
            ),
            connected = true
        )

        val options = buildPlaybackOptions(descriptor)

        return runCatching {
            val response = api.resolve(
                url = buildPlaybackControlUrl(endpoint),
                options = options
            )

            extractEngineError(response)?.let { engineError ->
                error("Ace Stream Engine returned error: $engineError")
            }

            val playable = extractPlaybackUrl(response)
                ?: error("Ace Stream Engine response is missing response.playback_url")

            publishRuntime(
                runtimeDiagnostics.value.copy(
                    stage = AceRuntimeStage.PLAYBACK_READY,
                    playbackTarget = sanitizeAceHttpUrl(playable),
                    failureCode = null
                ),
                connected = true
            )
            AppResult.Success(playable)
        }.getOrElse { throwable ->
            publishRuntime(
                runtimeDiagnostics.value.copy(
                    stage = AceRuntimeStage.ERROR,
                    failureCode = throwableFailureCode(throwable)
                ),
                connected = false
            )
            AppResult.Error(
                "Engine resolve failed: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }
    }

    internal fun recordCompatibilityFallback(loopbackEndpoint: String) {
        publishRuntime(
            runtimeDiagnostics.value.copy(
                stage = AceRuntimeStage.COMPATIBILITY_FALLBACK,
                route = "loopback_compatibility",
                enginePackage = null,
                endpoint = diagnosticEndpoint(loopbackEndpoint),
                failureCode = "primary_metadata_failed"
            )
        )
    }

    fun closeInstalledEngineConnection() {
        serviceConnector?.close()
        connectedEndpoint = null
        connectedAccessToken = null
        connectedPackageName = null
        connectedRoute = null
        runtimeDiagnostics.value = AceRuntimeDiagnostics()
        status.value = status.value.copy(
            connected = false,
            peers = 0,
            speedKbps = 0,
            message = "Engine disconnected"
        )
    }

    private suspend fun ensureEndpoint(): AppResult<String> {
        connectedEndpoint?.let { return AppResult.Success(it) }
        val connector = serviceConnector
            ?: return AppResult.Error("Ace Stream Engine is not connected")
        return when (val result = connector.ensureStarted()) {
            is AppResult.Success -> {
                connectedEndpoint = result.data.endpoint
                connectedAccessToken = result.data.accessToken
                connectedPackageName = result.data.packageName
                connectedRoute = "installed_engine"
                status.value = status.value.copy(
                    connected = true,
                    message = runtimeMessageOr("Ace Stream Engine ready: ${result.data.packageName}")
                )
                AppResult.Success(result.data.endpoint)
            }
            is AppResult.Error -> result
            AppResult.Loading -> AppResult.Loading
        }
    }

    private suspend fun fetchStatus(endpoint: String): AppResult<EngineStatus> {
        return runCatching {
            val serviceUrl = buildServiceUrl(endpoint)
            val statusOptions = buildMap {
                put("method", "get_status")
                connectedAccessToken?.takeIf { it.isNotBlank() }?.let { put("access_token", it) }
            }
            val response = runCatching {
                api.status(serviceUrl, statusOptions)
            }.getOrElse {
                val tokenOnly = connectedAccessToken
                    ?.takeIf { it.isNotBlank() }
                    ?.let { mapOf("access_token" to it) }
                    .orEmpty()
                api.status(serviceUrl, tokenOnly)
            }

            val root = extractRootMap(response)
            AppResult.Success(
                EngineStatus(
                    connected = true,
                    peers = extractInt(root, setOf("peers", "active_peers", "num_peers", "downloaders")),
                    speedKbps = extractInt(root, setOf("speed", "download_speed", "speed_down", "dl_speed")),
                    message = extractString(root, setOf("message", "status", "result"))
                        ?: "Engine is online"
                )
            )
        }.getOrElse { throwable ->
            AppResult.Error(
                "Engine status request failed: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }
    }

    private fun buildPlaybackOptions(descriptor: AceStreamDescriptor): Map<String, String> =
        buildMap {
            put("format", "json")
            put("sid", enginePlayerId)
            put("_idx", "0")
            put("stream_id", "0")
            putAll(AceStreamDescriptorParser.toEngineRequest(descriptor))
            put("auto_start_stream", "1")
            put("client_session_id", clientSessionIds.incrementAndGet().toString())
            // Current Ace Engine clients allow the live manifest/P2P startup path this window.
            put("manifest_p2p_wait_timeout", "10")
        }

    private fun buildPlaybackControlUrl(endpoint: String): String =
        "${endpoint.removeSuffix("/")}/ace/getstream"

    private fun buildServiceUrl(endpoint: String): String =
        "${endpoint.removeSuffix("/")}/webui/api/service"

    private fun buildServerApiUrl(endpoint: String): String =
        "${endpoint.removeSuffix("/")}/server/api"

    private fun normalizeEndpoint(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            value.removeSuffix("/")
        } else {
            "http://${value.removeSuffix("/")}"
        }
    }

    private fun publishRuntime(
        diagnostics: AceRuntimeDiagnostics,
        connected: Boolean? = null
    ) {
        runtimeDiagnostics.value = diagnostics
        status.value = status.value.copy(
            connected = connected ?: status.value.connected,
            message = diagnostics.toSummary()
        )
    }

    private fun runtimeMessageOr(fallback: String): String {
        val diagnostics = runtimeDiagnostics.value
        return if (diagnostics.stage == AceRuntimeStage.IDLE) fallback else diagnostics.toSummary()
    }

    private fun diagnosticEndpoint(endpoint: String?): String? =
        sanitizeAceHttpUrl(endpoint)?.removeSuffix("/")

    private fun throwableFailureCode(throwable: Throwable): String =
        throwable::class.java.simpleName
            .takeIf { it.isNotBlank() }
            ?.lowercase()
            ?: "request_failed"

    private fun descriptorKind(descriptor: AceStreamDescriptor): String = when (descriptor) {
        is AceStreamDescriptor.ContentId -> "content_id"
        is AceStreamDescriptor.Magnet -> "magnet"
        is AceStreamDescriptor.TransportFile -> {
            if (descriptor.value.substringBefore('?').lowercase().endsWith(".acelive")) {
                "acelive_url"
            } else {
                "transport_file_url"
            }
        }
        is AceStreamDescriptor.LocalEngineUrl -> "local_engine_url"
        is AceStreamDescriptor.Direct -> "direct"
    }

    private fun extractRootMap(map: Map<String, Any?>): Map<String, Any?> {
        val nested = map["response"]
        return if (nested is Map<*, *>) {
            nested.entries.filter { it.key is String }.associate { it.key as String to it.value }
        } else {
            map
        }
    }

    private fun extractPlaybackUrl(payload: Map<String, Any?>): String? {
        val response = payload["response"] as? Map<*, *> ?: return null
        val value = response["playback_url"] as? String ?: return null
        return value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun extractEngineError(payload: Map<String, Any?>): String? {
        val error = payload["error"] ?: return null
        return when (error) {
            is Boolean -> if (error) "unknown error" else null
            is Number -> if (error.toLong() == 0L) null else error.toString()
            is String -> error.takeIf { it.isNotBlank() }
            is Map<*, *> -> extractString(error, setOf("message", "error", "description"))
                ?: error.toString().takeIf { it != "{}" }
            else -> error.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun extractInt(data: Any?, preferredKeys: Set<String>): Int {
        when (data) {
            is Number -> return data.toInt()
            is String -> return data.toIntOrNull() ?: 0
            is Map<*, *> -> {
                preferredKeys.forEach { key ->
                    val number = extractInt(data[key], emptySet())
                    if (number > 0) return number
                }
                data.values.forEach { value ->
                    val number = extractInt(value, preferredKeys)
                    if (number > 0) return number
                }
            }
            is Iterable<*> -> data.forEach { value ->
                val number = extractInt(value, preferredKeys)
                if (number > 0) return number
            }
        }
        return 0
    }

    private fun extractString(data: Any?, preferredKeys: Set<String>): String? {
        when (data) {
            is String -> return data.takeIf { it.isNotBlank() }
            is Map<*, *> -> {
                preferredKeys.forEach { key ->
                    val value = data[key]
                    if (value is String && value.isNotBlank()) return value
                }
                data.values.forEach { value ->
                    extractString(value, preferredKeys)?.let { return it }
                }
            }
            is Iterable<*> -> data.forEach { value ->
                extractString(value, preferredKeys)?.let { return it }
            }
        }
        return null
    }
}
