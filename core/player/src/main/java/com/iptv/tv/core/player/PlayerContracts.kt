package com.iptv.tv.core.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.PlayerType

data class BufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
)

fun bufferConfigForProfile(profile: BufferProfile, manual: ManualBufferSettings? = null): BufferConfig =
    when (profile) {
        BufferProfile.MINIMAL -> BufferConfig(5_000, 20_000, 800, 1_500)
        BufferProfile.STANDARD -> BufferConfig(10_000, 40_000, 1_000, 2_000)
        BufferProfile.HIGH -> BufferConfig(20_000, 70_000, 1_200, 2_500)
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

@UnstableApi
fun BufferConfig.toLoadControl(): DefaultLoadControl {
    return DefaultLoadControl.Builder()
        .setBufferDurationsMs(minBufferMs, maxBufferMs, bufferForPlaybackMs, bufferForPlaybackAfterRebufferMs)
        .build()
}

class ExternalVlcLauncher {
    fun isVlcInstalled(context: Context): Boolean {
        return context.packageManager.getLaunchIntentForPackage(VLC_PACKAGE_NAME) != null
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
    }
}

fun isExternalPlayer(playerType: PlayerType): Boolean = playerType == PlayerType.VLC
