package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.engine.api.EngineStreamApi
import com.iptv.tv.core.model.EngineStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EngineStreamClient @Inject constructor(
    private val api: EngineStreamApi
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

    suspend fun resolveStream(magnetOrAce: String): AppResult<String> {
        val descriptor = normalizeTorrentDescriptor(magnetOrAce)
        if (descriptor.isBlank()) return AppResult.Error("Empty torrent descriptor")
        if (!isTorrentDescriptor(descriptor)) return AppResult.Success(descriptor)

        val endpoint = connectedEndpoint ?: return AppResult.Error("Engine not connected")
        val serviceUrl = buildServiceUrl(endpoint)
        return runCatching {
            val response = api.resolve(
                url = serviceUrl,
                options = mapOf(
                    "method" to "open_torrent",
                    "url" to descriptor
                )
            )
            val streamUrl = extractPlayableUrl(response)
                ?: buildFallbackStreamUrl(endpoint, descriptor)

            status.value = status.value.copy(
                connected = true,
                message = "Torrent stream resolved"
            )
            AppResult.Success(streamUrl)
        }.getOrElse { throwable ->
            status.value = status.value.copy(
                connected = false,
                message = "Torrent resolve failed: ${throwable.message}"
            )
            AppResult.Error("Engine resolve failed", throwable)
        }
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
            AppResult.Error("Engine status request failed", throwable)
        }
    }

    private fun buildServiceUrl(endpoint: String): String {
        return "${endpoint.removeSuffix("/")}/webui/api/service"
    }

    private fun buildFallbackStreamUrl(endpoint: String, descriptor: String): String {
        val encoded = URLEncoder.encode(descriptor, StandardCharsets.UTF_8.toString())
        return "${endpoint.removeSuffix("/")}/ace/getstream?url=$encoded"
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

    private fun isTorrentDescriptor(input: String): Boolean {
        val normalized = input.trim().lowercase()
        return normalized.startsWith("magnet:") ||
            normalized.startsWith("acestream://") ||
            normalized.startsWith("ace://") ||
            normalized.startsWith("infohash:") ||
            normalized.endsWith(".torrent") ||
            HASH40_REGEX.matches(normalized)
    }

    private fun normalizeTorrentDescriptor(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return trimmed

        val acePrefix = "ace://"
        if (trimmed.startsWith(acePrefix, ignoreCase = true)) {
            val tail = trimmed.substring(acePrefix.length).trimStart('/')
            return if (tail.isNotBlank()) "acestream://$tail" else trimmed
        }

        val infoHashPrefix = "infohash:"
        if (trimmed.startsWith(infoHashPrefix, ignoreCase = true)) {
            val hash = trimmed.substring(infoHashPrefix.length).trim()
            return if (HASH40_REGEX.matches(hash)) "magnet:?xt=urn:btih:$hash" else trimmed
        }

        return if (HASH40_REGEX.matches(trimmed)) {
            "magnet:?xt=urn:btih:$trimmed"
        } else {
            trimmed
        }
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
                    val v = data[key]
                    if (v is String && v.isNotBlank()) return v
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

    private companion object {
        val HASH40_REGEX = Regex("^[a-fA-F0-9]{40}$")
    }
}
