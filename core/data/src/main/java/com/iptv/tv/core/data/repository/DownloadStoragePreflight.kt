package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.DownloadSourceType
import java.util.Locale

internal data class DownloadStoragePreflightResult(
    val allowed: Boolean,
    val sourceType: DownloadSourceType,
    val estimatedBytes: Long,
    val availableBytes: Long?,
    val reserveBytes: Long,
    val reason: String?
)

internal class DownloadStoragePreflight(
    private val availableBytesProvider: () -> Long?
) {
    fun evaluate(source: String, sourceType: DownloadSourceType): DownloadStoragePreflightResult {
        val estimatedBytes = estimateBytes(source, sourceType)
        val availableBytes = availableBytesProvider()?.takeIf { it >= 0L }
        if (estimatedBytes <= 0L || availableBytes == null) {
            return DownloadStoragePreflightResult(
                allowed = true,
                sourceType = sourceType,
                estimatedBytes = estimatedBytes,
                availableBytes = availableBytes,
                reserveBytes = RESERVE_BYTES,
                reason = null
            )
        }

        val requiredBytes = estimatedBytes + RESERVE_BYTES
        val allowed = availableBytes >= requiredBytes
        return DownloadStoragePreflightResult(
            allowed = allowed,
            sourceType = sourceType,
            estimatedBytes = estimatedBytes,
            availableBytes = availableBytes,
            reserveBytes = RESERVE_BYTES,
            reason = if (allowed) {
                null
            } else {
                "not_enough_space required=$requiredBytes available=$availableBytes"
            }
        )
    }

    private fun estimateBytes(source: String, sourceType: DownloadSourceType): Long {
        explicitSizeHintBytes(source)?.let { return it }
        return when (sourceType) {
            DownloadSourceType.MAGNET,
            DownloadSourceType.TORRENT_FILE -> 2L * GIB
            DownloadSourceType.ACESTREAM -> 1536L * MIB
            DownloadSourceType.HLS_PLAYLIST,
            DownloadSourceType.HTTP_STREAM -> 512L * MIB
            DownloadSourceType.CUSTOM -> 256L * MIB
            DownloadSourceType.LOCAL_FILE -> 0L
        }
    }

    private fun explicitSizeHintBytes(source: String): Long? {
        val query = source.substringAfter('?', missingDelimiterValue = "")
            .substringBefore('#')
            .takeIf { it.isNotBlank() }
            ?: return null
        return query.split('&')
            .asSequence()
            .mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().lowercase(Locale.US)
                val value = part.substringAfter('=', "").trim()
                if (key in SIZE_HINT_KEYS) parseSizeBytes(value) else null
            }
            .firstOrNull()
    }

    private fun parseSizeBytes(raw: String): Long? {
        val normalized = raw.trim().lowercase(Locale.US)
        if (normalized.isBlank()) return null
        val number = normalized
            .takeWhile { it.isDigit() || it == '.' }
            .toDoubleOrNull()
            ?: return null
        val multiplier = when {
            normalized.endsWith("gib") || normalized.endsWith("gb") -> GIB
            normalized.endsWith("mib") || normalized.endsWith("mb") -> MIB
            normalized.endsWith("kib") || normalized.endsWith("kb") -> KIB
            else -> 1L
        }
        return (number * multiplier).toLong().takeIf { it > 0L }
    }

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * KIB
        const val GIB = 1024L * MIB
        const val RESERVE_BYTES = 256L * MIB
        val SIZE_HINT_KEYS = setOf("size", "bytes", "length", "content-length", "filesize")
    }
}
