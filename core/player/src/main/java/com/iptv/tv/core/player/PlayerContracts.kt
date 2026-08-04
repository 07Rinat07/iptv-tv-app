package com.iptv.tv.core.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.PlayerType

/**
 * Настройки буфера Media3.
 *
 * targetBufferBytes ограничивает расход памяти на старых TV Box. Значение <= 0 означает,
 * что Media3 самостоятельно выберет размер по типам активных дорожек.
 */
data class BufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferBytes: Int = -1
)

enum class PlaybackDeviceTier {
    LOW,
    BALANCED,
    HIGH
}

data class PlaybackDeviceProfile(
    val cpuCount: Int,
    val maxMemoryBytes: Long,
    val activePaneCount: Int = 1
) {
    val tier: PlaybackDeviceTier
        get() = when {
            cpuCount <= 2 || maxMemoryBytes < 256L * 1024L * 1024L -> PlaybackDeviceTier.LOW
            cpuCount <= 4 || maxMemoryBytes < 512L * 1024L * 1024L -> PlaybackDeviceTier.BALANCED
            else -> PlaybackDeviceTier.HIGH
        }

    val memoryMb: Long
        get() = (maxMemoryBytes / (1024L * 1024L)).coerceAtLeast(0L)
}

data class BufferingRecoveryPolicy(
    val checkIntervalMs: Long,
    val retryAfterMs: Long,
    val failAfterMs: Long,
    val maxRecoveryAttempts: Int
)

data class AdaptiveBufferPlan(
    val config: BufferConfig,
    val deviceTier: PlaybackDeviceTier,
    val recoveryPolicy: BufferingRecoveryPolicy,
    val summary: String
)

fun bufferConfigForProfile(profile: BufferProfile, manual: ManualBufferSettings? = null): BufferConfig =
    when (profile) {
        BufferProfile.MINIMAL -> BufferConfig(3_000, 15_000, 700, 1_200)
        // Баланс для TV Box: быстрый старт без чрезмерного расхода памяти.
        BufferProfile.STANDARD -> BufferConfig(6_000, 45_000, 1_500, 2_500)
        BufferProfile.HIGH -> BufferConfig(30_000, 120_000, 3_000, 5_000)
        BufferProfile.MANUAL -> {
            val boundedStart = (manual?.startMs ?: 12_000).coerceIn(250, 120_000)
            val boundedMax = (manual?.maxMs ?: 50_000).coerceIn(1_000, 240_000).coerceAtLeast(boundedStart)
            val boundedRebuffer = (manual?.rebufferMs ?: 2_000).coerceIn(250, boundedMax)
            BufferConfig(
                minBufferMs = boundedStart,
                maxBufferMs = boundedMax,
                bufferForPlaybackMs = boundedStart,
                bufferForPlaybackAfterRebufferMs = boundedRebuffer
            )
        }
    }

/**
 * Подбирает безопасную конфигурацию для конкретного устройства.
 *
 * Пользовательский профиль остаётся исходным пожеланием, но верхняя граница буфера и лимит
 * памяти уменьшаются на слабых приставках и при multiview. Это предотвращает частые GC-паузы
 * и OutOfMemoryError, не меняя URL или логику поиска/сканирования каналов.
 */
fun adaptiveBufferPlan(
    profile: BufferProfile,
    manual: ManualBufferSettings? = null,
    device: PlaybackDeviceProfile,
    isAdditionalPane: Boolean = false
): AdaptiveBufferPlan {
    val requested = bufferConfigForProfile(profile, manual)
    val paneCount = device.activePaneCount.coerceAtLeast(1)
    val effectiveTier = device.tier

    val maxBufferCap = when {
        isAdditionalPane -> 15_000
        paneCount >= 4 -> 18_000
        paneCount >= 2 -> 25_000
        effectiveTier == PlaybackDeviceTier.LOW -> 25_000
        effectiveTier == PlaybackDeviceTier.BALANCED -> 60_000
        else -> 120_000
    }
    val minBufferCap = when {
        isAdditionalPane -> 4_000
        paneCount >= 4 -> 6_000
        paneCount >= 2 -> 10_000
        effectiveTier == PlaybackDeviceTier.LOW -> 8_000
        effectiveTier == PlaybackDeviceTier.BALANCED -> 20_000
        else -> 60_000
    }
    val startCap = when {
        isAdditionalPane -> 1_200
        effectiveTier == PlaybackDeviceTier.LOW -> 1_500
        effectiveTier == PlaybackDeviceTier.BALANCED -> 3_000
        else -> 8_000
    }
    val rebufferCap = when {
        isAdditionalPane -> 2_000
        effectiveTier == PlaybackDeviceTier.LOW -> 3_000
        effectiveTier == PlaybackDeviceTier.BALANCED -> 5_000
        else -> 10_000
    }
    val targetBufferBytes = when {
        isAdditionalPane -> 10 * 1024 * 1024
        paneCount >= 4 -> 12 * 1024 * 1024
        paneCount >= 2 -> 18 * 1024 * 1024
        effectiveTier == PlaybackDeviceTier.LOW -> 24 * 1024 * 1024
        effectiveTier == PlaybackDeviceTier.BALANCED -> 48 * 1024 * 1024
        else -> 96 * 1024 * 1024
    }

    val boundedMax = requested.maxBufferMs.coerceAtMost(maxBufferCap).coerceAtLeast(1_000)
    val boundedMin = requested.minBufferMs.coerceAtMost(minBufferCap).coerceAtMost(boundedMax)
    val boundedStart = requested.bufferForPlaybackMs
        .coerceAtMost(startCap)
        .coerceAtMost(boundedMax)
        .coerceAtLeast(250)
    val boundedRebuffer = requested.bufferForPlaybackAfterRebufferMs
        .coerceAtMost(rebufferCap)
        .coerceAtMost(boundedMax)
        .coerceAtLeast(250)

    val recovery = when {
        isAdditionalPane || paneCount >= 4 -> BufferingRecoveryPolicy(
            checkIntervalMs = 2_500,
            retryAfterMs = 10_000,
            failAfterMs = 20_000,
            maxRecoveryAttempts = 1
        )
        effectiveTier == PlaybackDeviceTier.LOW -> BufferingRecoveryPolicy(
            checkIntervalMs = 3_000,
            retryAfterMs = 12_000,
            failAfterMs = 24_000,
            maxRecoveryAttempts = 1
        )
        else -> BufferingRecoveryPolicy(
            checkIntervalMs = 3_000,
            retryAfterMs = 15_000,
            failAfterMs = 30_000,
            maxRecoveryAttempts = 1
        )
    }

    val config = BufferConfig(
        minBufferMs = boundedMin,
        maxBufferMs = boundedMax,
        bufferForPlaybackMs = boundedStart,
        bufferForPlaybackAfterRebufferMs = boundedRebuffer,
        targetBufferBytes = targetBufferBytes
    )
    val mode = if (isAdditionalPane) "дополнительное окно" else "основное окно"
    val summary = buildString {
        append("${effectiveTier.name.lowercase()} | CPU=${device.cpuCount} | heap=${device.memoryMb}MB")
        append(" | окон=$paneCount | $mode")
        append(" | буфер=${config.minBufferMs}-${config.maxBufferMs}мс")
        append(" | лимит=${config.targetBufferBytes / (1024 * 1024)}MB")
    }

    return AdaptiveBufferPlan(
        config = config,
        deviceTier = effectiveTier,
        recoveryPolicy = recovery,
        summary = summary
    )
}

@UnstableApi
fun BufferConfig.toLoadControl(): DefaultLoadControl {
    val builder = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            minBufferMs,
            maxBufferMs,
            bufferForPlaybackMs,
            bufferForPlaybackAfterRebufferMs
        )
        .setPrioritizeTimeOverSizeThresholds(true)
    if (targetBufferBytes > 0) {
        builder.setTargetBufferBytes(targetBufferBytes)
    }
    return builder.build()
}

class ExternalVlcLauncher {
    fun isVlcInstalled(context: Context): Boolean {
        return context.packageManager.getLaunchIntentForPackage(VLC_PACKAGE_NAME) != null
    }

    fun createDirectPlayerIntent(streamUrl: String, title: String? = null): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName(VLC_PACKAGE_NAME, VLC_PLAYER_ACTIVITY_NAME)
            setDataAndType(Uri.parse(streamUrl), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(VLC_EXTRA_FROM_START, true)
            if (!title.isNullOrBlank()) {
                putExtra(VLC_EXTRA_TITLE, title)
            }
        }
    }

    fun createIntent(streamUrl: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            setPackage(VLC_PACKAGE_NAME)
            setDataAndType(Uri.parse(streamUrl), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createInstallIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$VLC_PACKAGE_NAME"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun createInstallWebIntent(): Intent {
        return Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$VLC_PACKAGE_NAME"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    companion object {
        const val VLC_PACKAGE_NAME = "org.videolan.vlc"
        const val VLC_PLAYER_ACTIVITY_NAME = "org.videolan.vlc.gui.video.VideoPlayerActivity"
        const val VLC_EXTRA_FROM_START = "from_start"
        const val VLC_EXTRA_TITLE = "title"
    }
}

fun isExternalPlayer(playerType: PlayerType): Boolean = playerType == PlayerType.VLC
