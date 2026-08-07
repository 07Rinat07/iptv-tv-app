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
    private var connectedAccessToken: String? = null

    fun observeStatus(): StateFlow<EngineStatus> = status.asStateFlow()

    suspend fun connect(endpoint: String): AppResult<Unit> {
        val normalized = normalizeEndpoint(endpoint)
            ?: return AppResult.Error("Engine endpoint is empty")

        return when (val result = fetchStatus(normalized)) {
            is AppResult.Success -> {
                connectedEndpoint = normalized
                connectedAccessToken = null
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
            is AppResult.Success -> {
                connectedEndpoint = result.data.endpoint
                connectedAccessToken = result.data.accessToken
                when (val statusResult = fetchStatus(result.data.endpoint)) {
                    is AppResult.Success -> {
                        status.value = statusResult.data.copy(
                            connected = true,
                            message = "Connected: ${result.data.packageName}"
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

    /**
     * Resolve a true Ace content id to the BitTorrent transport infohash reported by Ace Engine.
     *
     * Content IDs and BitTorrent infohashes are different identifiers. This method therefore asks
     * the engine metadata API for the transport metadata and only returns a hash when the response
     * represents non-live BitTorrent transport and contains a valid SHA-1 infohash. Ace live uses
     * its own piece/chunk streaming protocol and must not be treated as a standard libtorrent magnet.
     */
    suspend fun resolveContentIdInfoHash(rawSource: String): AppResult<String> {
        val descriptor = AceStreamDescriptorParser.parse(rawSource)
        if (descriptor !is AceStreamDescriptor.ContentId) {
            return AppResult.Error("Ace Stream content id is required for metadata resolution")
        }

        val endpoint = when (val local = ensureEndpoint()) {
            is AppResult.Success -> local.data
            is AppResult.Error -> return local
            AppResult.Loading -> return AppResult.Loading
        }

        val options = buildMap {
            put("api_version", "3")
            put("method", "get_media_files")
            put("content_id", descriptor.value)
            put("mode", "brief")
        }

        return runCatching {
            val response = api.resolve(
                url = buildServerApiUrl(endpoint),
                options = options
            )
            val transportType = extractNamedString(response, "transport_type")
                ?.trim()
                ?.lowercase()
            if (transportType != null && transportType != "bt") {
                return@runCatching AppResult.Error(
                    "Ace content id resolved to unsupported transport type: $transportType"
                )
            }

            val mediaType = extractNamedString(response, "type")
                ?.trim()
                ?.lowercase()
            if (mediaType == "live") {
                return@runCatching AppResult.Error(
                    "Ace live transport requires the Ace live protocol and cannot use embedded BitTorrent"
                )
            }

            val infoHash = extractNamedString(response, "infohash")
                ?.trim()
                ?.lowercase()
                ?.takeIf(BITTORRENT_INFO_HASH::matches)
                ?: return@runCatching AppResult.Error(
                    "Ace content id metadata did not contain a valid BitTorrent infohash"
                )

            status.value = status.value.copy(
                connected = true,
                message = "Ace content metadata resolved"
            )
            AppResult.Success(infoHash)
        }.getOrElse { throwable ->
            AppResult.Error(
                "Ace content metadata request failed: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
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

        val request = buildEngineRequest(descriptor)
        val options = buildMap {
            put("method", "open_torrent")
            connectedAccessToken?.takeIf { it.isNotBlank() }?.let { put("access_token", it) }
            putAll(request)
        }

        return runCatching {
            val response = api.resolve(
                url = buildServiceUrl(endpoint),
                options = options
            )
            val playable = extractPlayableUrl(response)
                ?: buildFallbackStreamUrl(endpoint, request, connectedAccessToken)

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
        connectedAccessToken = null
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

    private fun buildServiceUrl(endpoint: String): String =
        "${endpoint.removeSuffix("/")}/webui/api/service"

    private fun buildServerApiUrl(endpoint: String): String =
        "${endpoint.removeSuffix("/")}/server/api"

    private fun buildEngineRequest(descriptor: AceStreamDescriptor): Map<String, String> {
        if (descriptor is AceStreamDescriptor.ContentId) {
            val original = descriptor.original.trim()
            if (original.startsWith("ace://", ignoreCase = true)) {
                val legacyContentId = original
                    .substringAfter("://")
                    .trimStart('/')
                    .substringBefore('?')
                    .trim()

                if (legacyContentId.isNotBlank()) {
                    return mapOf("url" to "acestream://$legacyContentId")
                }
            }
        }

        return AceStreamDescriptorParser.toEngineRequest(descriptor)
    }

    private fun buildFallbackStreamUrl(
        endpoint: String,
        request: Map<String, String>,
        accessToken: String?
    ): String {
        val key = if (request.containsKey("id")) "id" else "url"
        val value = request[key].orEmpty()
        val encoded = URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
        val tokenQuery = accessToken
            ?.takeIf { it.isNotBlank() }
            ?.let { token ->
                "&access_token=${URLEncoder.encode(token, StandardCharsets.UTF_8.toString())}"
            }
            .orEmpty()
        return "${endpoint.removeSuffix("/")}/ace/getstream?$key=$encoded$tokenQuery"
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

    private fun extractNamedString(data: Any?, key: String): String? = when (data) {
        is Map<*, *> -> {
            data.entries.firstNotNullOfOrNull { (entryKey, value) ->
                if (entryKey is String && entryKey.equals(key, ignoreCase = true)) {
                    (value as? String)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } ?: data.values.firstNotNullOfOrNull { value ->
                when (value) {
                    is Map<*, *>, is Iterable<*> -> extractNamedString(value, key)
                    else -> null
                }
            }
        }
        is Iterable<*> -> data.firstNotNullOfOrNull { value -> extractNamedString(value, key) }
        else -> null
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

    private companion object {
        val BITTORRENT_INFO_HASH = Regex("^[a-fA-F0-9]{40}$")
    }
}
