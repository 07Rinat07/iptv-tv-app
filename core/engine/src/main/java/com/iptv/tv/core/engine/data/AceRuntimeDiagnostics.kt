package com.iptv.tv.core.engine.data

import java.net.URI

enum class AceRuntimeStage {
    IDLE,
    RESOLVING_ENDPOINT,
    REQUESTING_METADATA,
    METADATA_READY,
    REQUESTING_PLAYBACK,
    PLAYBACK_READY,
    COMPATIBILITY_FALLBACK,
    ERROR
}

/**
 * Safe, value-free runtime diagnostics for Ace playback/metadata resolution.
 *
 * Content ids, magnets, transport descriptor query strings and access tokens are deliberately not
 * stored here. Playback URLs are sanitized before being exposed so query parameters/fragments are
 * never shown on the diagnostics screen.
 */
data class AceRuntimeDiagnostics(
    val stage: AceRuntimeStage = AceRuntimeStage.IDLE,
    val descriptorKind: String? = null,
    val provider: String? = null,
    val route: String? = null,
    val enginePackage: String? = null,
    val endpoint: String? = null,
    val transportType: String? = null,
    val isLive: Boolean? = null,
    val playbackTarget: String? = null,
    val failureCode: String? = null
) {
    fun toSummary(): String = buildString {
        append("Ace runtime: stage=")
        append(stage.name.lowercase())
        descriptorKind?.let { append(" descriptor=").append(it) }
        provider?.let { append(" provider=").append(it) }
        route?.let { append(" route=").append(it) }
        enginePackage?.let { append(" package=").append(it) }
        endpoint?.let { append(" endpoint=").append(it) }
        transportType?.let { append(" transport=").append(it) }
        isLive?.let { append(" live=").append(it) }
        playbackTarget?.let { append(" target=").append(it) }
        failureCode?.let { append(" failure=").append(it) }
    }
}

internal fun sanitizeAceHttpUrl(raw: String?): String? {
    val value = raw?.trim().orEmpty()
    if (value.isBlank()) return null

    return runCatching {
        val uri = URI(value)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme !in setOf("http", "https") || host.isNullOrBlank()) return@runCatching null

        URI(
            scheme,
            null,
            host,
            uri.port,
            uri.rawPath?.takeIf { it.isNotBlank() } ?: "/",
            null,
            null
        ).toString()
    }.getOrNull()
}
