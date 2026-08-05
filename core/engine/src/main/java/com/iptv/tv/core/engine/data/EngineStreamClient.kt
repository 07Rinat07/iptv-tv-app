package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.engine.api.EngineStreamApi
import com.iptv.tv.core.model.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

    private var connectedEndpoint: String? = null

    fun observeStatus(): StateFlow<EngineStatus> = status.asStateFlow()

    suspend fun connect(endpoint: String): AppResult<Unit> {
        val normalized = normalizeEndpoint(endpoint)
            ?: return AppResult.Error("Engine endpoint is empty")

        return when (val result = fetchStatus(normalized)) {
            is AppResult.Success -> {
                connectedEndpoint = normalized
                status.value = result.data.copy(
                    connected = true,
                    message = "Connected: $normalized"
                )
                AppResult.Success(Unit)
            }

            is AppResult.Error -> {
                status.value = status.value.copy(
                    connected = false,
                    peers = 0,
                    speedKbps = 0,
                    message = "Engine connect failed: ${result.message}"
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
            is AppResult.Success -> connect(result.data.endpoint)
            is AppResult.Error -> {
                status.value = status.value.copy(
                    connected = false,
                    peers = 0,
                    speedKbps = 0,
                    message = result.message
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

    suspend fun resolveStream(rawSource: String): AppResult<String> {
        val descriptor = AceStreamDescriptorParser.parse(rawSource)
        if (descriptor is AceStreamDescriptor.Direct) {
            return if (descriptor.value.isBlank()) {
                AppResult.Error("Empty stream source")
            } else {
                AppResult.Success(descriptor.value)
            }
        }

        val endpoint = when (val local = ensureEndpoint()) {
            is AppResult.Success -> local.data
            is AppResult.Error -> return local
            AppResult.Loading -> return AppResult.Loading
        }

        if (descriptor is AceStreamDescriptor.LocalEngineUrl) {
            return AppResult.Success(descriptor.value)
        }

        val request = AceStreamDescriptorParser.toEngineRequest(descriptor)
        val options = buildMap {
            put("method", "open_torrent")
            putAll(request)
        }

        return runCatching {
            val response = api.resolve(
                url = buildServiceUrl(endpoint),
                options = options
            )
            val playable = extractPlayableUrl(response)
                ?: buildFallbackStreamUrl(endpoint, request)

            status.value = status.value.copy(
                connected = true,
                message = "Ace Stream source resolved"
            )
            AppResult.Success(playable)
        }.getOrElse { throwable ->
            status.value = status.value.copy(
                connected = false,
                message = "Ace Stream resolve failed: ${throwable.message}"
            )
            AppResult.Error(
                "Engine resolve failed: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }
    }

    fun closeInstalledEngineConnection() {
        serviceConnector?.close()
        connectedEndpoint = null
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
                status.value = status.value.copy(
                    connected = true,
                    message = "Ace Stream Engine ready: ${result.data.packageName}"
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
            val response = runCatching {
                api.status(serviceUrl, mapOf("method" to "get_status"))
            }.getOrElse {
                api.status(serviceUrl)
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

    private fun buildServiceUrl(endpoint: String): String =
        "${endpoint.removeSuffix("/")}/webui/api/service"

    private fun buildFallbackStreamUrl(endpoint: String, request: Map<String, String>): String {
        val key = if (request.containsKey("id")) "id" else "url"
        val value = request[key].orEmpty()
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        return "${endpoint.removeSuffix("/")}/ace/getstream?$key=$encoded"
    }

    private fun normalizeEndpoint(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
            value.removeSuffix("/")
        } else {
            "http://${value.removeSuffix("/")}"
        }
    }

    private fun extractRootMap(map: Map<String, Any?>): Map<String, Any?> {
        val nested = map["response"]
        return if (nested is Map<*, *>) {
            nested.entries.filter { it.key is String }.associate { it.key as String to it.value }
        } else {
            map
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

    private fun extractPlayableUrl(payload: Any?): String? = when (payload) {
        is String -> payload.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        is Map<*, *> -> {
            val preferred = listOf(
                "url",
                "stream",
                "stream_url",
                "streamUrl",
                "play_url",
                "playback_url",
                "link"
            )
            preferred.firstNotNullOfOrNull { key -> extractPlayableUrl(payload[key]) }
                ?: payload.values.firstNotNullOfOrNull(::extractPlayableUrl)
        }
        is Iterable<*> -> payload.firstNotNullOfOrNull(::extractPlayableUrl)
        else -> null
    }
}
