package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.engine.acestream.AceStreamDescriptorParser
import com.iptv.tv.core.engine.acestream.AceStreamServiceBridge
import com.iptv.tv.core.engine.api.EngineStreamApi
import com.iptv.tv.core.model.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineStreamClient private constructor(
    private val api: EngineStreamApi,
    private val serviceBridge: AceStreamServiceBridge?
) {
    @Inject
    constructor(
        api: EngineStreamApi,
        serviceBridge: AceStreamServiceBridge
    ) : this(api = api, serviceBridge = serviceBridge as AceStreamServiceBridge?)

    internal constructor(api: EngineStreamApi) : this(api = api, serviceBridge = null)

    private val status = MutableStateFlow(
        EngineStatus(
            connected = false,
            peers = 0,
            speedKbps = 0,
            message = "Engine not connected"
        )
    )

    private var connectedEndpoint: String? = null
    private var serviceManagedEndpoint = false

    fun observeStatus(): StateFlow<EngineStatus> = status.asStateFlow()

    suspend fun connect(endpoint: String): AppResult<Unit> {
        val normalized = normalizeEndpoint(endpoint)
            ?: return AppResult.Error("Engine endpoint is empty")

        if (isLocalEngineEndpoint(normalized) && serviceBridge != null) {
            when (val serviceResult = serviceBridge.startEngine()) {
                is AppResult.Success -> {
                    connectedEndpoint = serviceResult.data.endpointUrl
                    serviceManagedEndpoint = true
                    status.value = EngineStatus(
                        connected = true,
                        peers = 0,
                        speedKbps = 0,
                        message = buildString {
                            append("Ace Stream Android service connected: ")
                            append(serviceResult.data.endpointUrl)
                            if (serviceResult.data.httpApiPort <= 0) {
                                append(" (HTTP port fallback to Engine API)")
                            }
                        }
                    )
                    return AppResult.Success(Unit)
                }

                is AppResult.Error -> {
                    // A manually started localhost engine remains supported when the
                    // official Android service package is not installed or cannot bind.
                }

                AppResult.Loading -> Unit
            }
        }

        return when (val result = fetchStatus(normalized)) {
            is AppResult.Success -> {
                connectedEndpoint = normalized
                serviceManagedEndpoint = false
                status.value = result.data.copy(
                    connected = true,
                    message = "Connected: $normalized"
                )
                AppResult.Success(Unit)
            }

            is AppResult.Error -> {
                status.value = status.value.copy(
                    connected = false,
                    message = "Engine connect failed: ${result.message}",
                    peers = 0,
                    speedKbps = 0
                )
                result
            }

            AppResult.Loading -> AppResult.Loading
        }
    }

    suspend fun refreshStatus(): AppResult<EngineStatus> {
        val endpoint = connectedEndpoint ?: return AppResult.Error("Engine not connected")

        if (serviceManagedEndpoint && serviceBridge?.currentEndpoint() != null) {
            val current = status.value.copy(
                connected = true,
                message = "Ace Stream Android service connected: $endpoint"
            )
            status.value = current
            return AppResult.Success(current)
        }

        return when (val result = fetchStatus(endpoint)) {
            is AppResult.Success -> {
                status.value = result.data.copy(
                    connected = true,
                    message = "Connected: $endpoint"
                )
                AppResult.Success(status.value)
            }

            is AppResult.Error -> {
                status.value = status.value.copy(
                    connected = false,
                    peers = 0,
                    speedKbps = 0,
                    message = "Engine status error: ${result.message}"
                )
                result
            }

            AppResult.Loading -> AppResult.Loading
        }
    }

    suspend fun resolveStream(rawDescriptor: String): AppResult<String> {
        val input = rawDescriptor.trim()
        if (input.isBlank()) return AppResult.Error("Empty torrent descriptor")

        val descriptor = AceStreamDescriptorParser.parse(input)
            ?: return AppResult.Success(input)

        val endpointResult = ensureConnectedEndpoint()
        val endpoint = when (endpointResult) {
            is AppResult.Success -> endpointResult.data
            is AppResult.Error -> return endpointResult
            AppResult.Loading -> return AppResult.Loading
        }

        if (serviceManagedEndpoint) {
            return resolvedDirectly(endpoint, descriptor)
        }

        val serviceUrl = buildServiceUrl(endpoint)
        return runCatching {
            val response = api.resolve(
                url = serviceUrl,
                options = mapOf(
                    "method" to "open_torrent",
                    "url" to input
                )
            )
            val streamUrl = extractPlayableUrl(response)
                ?: AceStreamDescriptorParser.buildPlaybackUrl(endpoint, descriptor)

            status.value = status.value.copy(
                connected = true,
                message = "Torrent stream resolved"
            )
            AppResult.Success(streamUrl)
        }.getOrElse {
            // The official HTTP API can start playback directly through
            // /ace/getstream even when the optional WebUI resolver is absent.
            resolvedDirectly(endpoint, descriptor)
        }
    }

    private suspend fun ensureConnectedEndpoint(): AppResult<String> {
        connectedEndpoint?.let { return AppResult.Success(it) }

        val bridge = serviceBridge
        if (bridge != null) {
            when (val serviceResult = bridge.startEngine()) {
                is AppResult.Success -> {
                    connectedEndpoint = serviceResult.data.endpointUrl
                    serviceManagedEndpoint = true
                    status.value = EngineStatus(
                        connected = true,
                        peers = 0,
                        speedKbps = 0,
                        message = "Ace Stream Android service connected: ${serviceResult.data.endpointUrl}"
                    )
                    return AppResult.Success(serviceResult.data.endpointUrl)
                }

                is AppResult.Error -> return AppResult.Error(serviceResult.message, serviceResult.cause)
                AppResult.Loading -> return AppResult.Loading
            }
        }

        return AppResult.Error("Engine not connected")
    }

    private fun resolvedDirectly(
        endpoint: String,
        descriptor: com.iptv.tv.core.engine.acestream.AceStreamDescriptor
    ): AppResult<String> {
        val streamUrl = AceStreamDescriptorParser.buildPlaybackUrl(endpoint, descriptor)
        status.value = status.value.copy(
            connected = true,
            message = "Ace Stream playback URL prepared"
        )
        return AppResult.Success(streamUrl)
    }

    private suspend fun fetchStatus(endpoint: String): AppResult<EngineStatus> {
        val serviceUrl = buildServiceUrl(endpoint)
        return runCatching {
            val response = runCatching {
                api.status(
                    url = serviceUrl,
                    options = mapOf("method" to "get_status")
                )
            }.getOrElse {
                api.status(url = serviceUrl)
            }

            val root = extractRootMap(response)
            val peers = extractInt(root, setOf("peers", "active_peers", "num_peers", "downloaders"))
            val speed = extractInt(root, setOf("speed", "download_speed", "speed_down", "dl_speed"))
            val message = extractString(root, setOf("message", "status", "result")) ?: "Engine is online"

            AppResult.Success(
                EngineStatus(
                    connected = true,
                    peers = peers,
                    speedKbps = speed,
                    message = message
                )
            )
        }.getOrElse { throwable ->
            AppResult.Error("Engine status request failed: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
        }
    }

    private fun buildServiceUrl(endpoint: String): String {
        return "${endpoint.removeSuffix("/")}/webui/api/service"
    }

    private fun normalizeEndpoint(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed.removeSuffix("/")
        } else {
            "http://${trimmed.removeSuffix("/")}"
        }
    }

    private fun isLocalEngineEndpoint(endpoint: String): Boolean {
        val lowercase = endpoint.lowercase(Locale.US)
        return lowercase.startsWith("http://127.0.0.1") ||
            lowercase.startsWith("http://localhost") ||
            lowercase.startsWith("https://127.0.0.1") ||
            lowercase.startsWith("https://localhost")
    }

    private fun extractRootMap(map: Map<String, Any?>): Map<String, Any?> {
        val nested = map["response"]
        return if (nested is Map<*, *>) {
            nested.entries
                .filter { it.key is String }
                .associate { it.key as String to it.value }
        } else {
            map
        }
    }

    private fun extractInt(data: Any?, preferredKeys: Set<String>): Int {
        when (data) {
            is Number -> return data.toInt()
            is String -> return data.toIntOrNull() ?: 0
            is Map<*, *> -> {
                val preferred = preferredKeys.firstNotNullOfOrNull { key ->
                    extractInt(data[key], emptySet()).takeIf { it > 0 }
                }
                if (preferred != null) return preferred
                data.values.forEach { value ->
                    val nested = extractInt(value, preferredKeys)
                    if (nested > 0) return nested
                }
            }

            is Iterable<*> -> {
                data.forEach { value ->
                    val nested = extractInt(value, preferredKeys)
                    if (nested > 0) return nested
                }
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
                    val nested = extractString(value, preferredKeys)
                    if (!nested.isNullOrBlank()) return nested
                }
            }

            is Iterable<*> -> {
                data.forEach { value ->
                    val nested = extractString(value, preferredKeys)
                    if (!nested.isNullOrBlank()) return nested
                }
            }
        }
        return null
    }

    private fun extractPlayableUrl(payload: Any?): String? {
        return when (payload) {
            is String -> payload.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            is Map<*, *> -> {
                val keys = listOf(
                    "url",
                    "stream",
                    "stream_url",
                    "streamUrl",
                    "play_url",
                    "playback_url",
                    "link"
                )
                keys.firstNotNullOfOrNull { key ->
                    extractPlayableUrl(payload[key])
                } ?: payload.values.firstNotNullOfOrNull { value ->
                    extractPlayableUrl(value)
                }
            }

            is Iterable<*> -> payload.firstNotNullOfOrNull { value -> extractPlayableUrl(value) }
            else -> null
        }
    }
}
