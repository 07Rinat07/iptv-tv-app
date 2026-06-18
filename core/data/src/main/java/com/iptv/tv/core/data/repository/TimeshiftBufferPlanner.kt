package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.DownloadSourceType
import com.iptv.tv.core.model.RecordingStorageInfo
import com.iptv.tv.core.model.TimeshiftBufferPlan
import kotlin.math.min

internal object TimeshiftBufferPlanner {
    const val MIN_DURATION_MINUTES = 5
    const val MAX_DURATION_MINUTES = 6 * 60
    private const val HLS_BYTES_PER_SECOND = 768_000L
    private const val HTTP_BYTES_PER_SECOND = 1_024_000L

    fun plan(
        channelId: Long,
        channelName: String,
        rawStreamUrl: String,
        storageInfo: RecordingStorageInfo,
        requestedMinutes: Int
    ): TimeshiftBufferPlan {
        val streamUrl = rawStreamUrl.substringBefore('|').trim()
        val sourceType = classify(streamUrl)
        val durationMinutes = requestedMinutes.coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
        val bytesPerSecond = bytesPerSecondFor(sourceType)
        val estimatedBytes = bytesPerSecond * durationMinutes * 60L
        val maxWritableBytes = storageInfo.freeBytes.toWritableBytesOrUnknown()
        val availableBytes = if (storageInfo.freeBytes < 0L) -1L else maxWritableBytes
        val maxDurationByStorage = if (storageInfo.freeBytes < 0L) {
            durationMinutes
        } else if (bytesPerSecond <= 0L || maxWritableBytes <= 0L) {
            0
        } else {
            ((maxWritableBytes / bytesPerSecond) / 60L)
                .toInt()
                .coerceIn(0, MAX_DURATION_MINUTES)
        }
        val maxDurationMinutes = min(durationMinutes, maxDurationByStorage)
        val streamSupported = sourceType == DownloadSourceType.HTTP_STREAM ||
            sourceType == DownloadSourceType.HLS_PLAYLIST
        val reason = when {
            !streamSupported -> "Timeshift пока поддерживает только прямые HTTP/HTTPS и HLS .m3u8 потоки"
            !storageInfo.configured -> "Папка записей не выбрана"
            !storageInfo.exists -> "Папка записей недоступна"
            !storageInfo.writable -> "Папка записей недоступна для записи"
            storageInfo.freeBytes >= 0L && maxWritableBytes < estimatedBytes -> {
                "Недостаточно места для ${durationMinutes} мин timeshift"
            }
            else -> null
        }

        return TimeshiftBufferPlan(
            channelId = channelId,
            channelName = channelName,
            requestedDurationMinutes = durationMinutes,
            maxDurationMinutes = maxDurationMinutes,
            estimatedBytes = estimatedBytes,
            availableBytes = availableBytes,
            storagePath = storageInfo.path,
            storageLocation = storageInfo.location,
            sourceType = sourceType,
            supported = reason == null,
            reason = reason
        )
    }

    fun classify(streamUrl: String): DownloadSourceType {
        return DownloadSourceClassifier.classify(streamUrl)
    }

    private fun bytesPerSecondFor(sourceType: DownloadSourceType): Long {
        return when (sourceType) {
            DownloadSourceType.HLS_PLAYLIST -> HLS_BYTES_PER_SECOND
            DownloadSourceType.HTTP_STREAM -> HTTP_BYTES_PER_SECOND
            else -> 0L
        }
    }

    private fun Long.toWritableBytesOrUnknown(): Long {
        return if (this < 0L) {
            RecordingLimits.ABSOLUTE_MAX_RECORDING_BYTES
        } else {
            RecordingLimits.maxRecordingBytes(this)
        }
    }
}
