package com.iptv.tv

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.view.ViewGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.EngineRepository
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic-only sweep for the exact production code under test.
 *
 * This class is intentionally not part of the normal merge gate. It is used from a temporary
 * diagnostic branch to exercise several real Torrent TV samples against the embedded Ace Live engine,
 * render them through Media3, save screenshots, and leave explicit per-sample boundaries in logcat.
 */
@RunWith(AndroidJUnit4::class)
class TorrentTvSweepDiagnosticTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context
        get() = instrumentation.targetContext

    private val engineRepository: EngineRepository by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ApplicationEngineEntryPoint::class.java
        ).engineRepository()
    }

    @After
    fun cleanup() {
        runBlocking { runCatching { engineRepository.stopTorrentStream() } }
    }

    @Test
    fun sweepRealTorrentTvChannelsAndCaptureEvidence() = runBlocking {
        assertNoExternalAceEngine()
        val samples = loadSweepSamples()
        assertTrue("Torrent TV sweep did not resolve any samples", samples.isNotEmpty())

        val results = mutableListOf<SweepResult>()
        samples.forEachIndexed { index, sample ->
            val startedAt = System.currentTimeMillis()
            val prefix = "sample=${index + 1}/${samples.size} label=${sample.label}"
            println("TORRENT_TV_SWEEP start $prefix source=${sample.source}")
            Log.i(TAG, "start $prefix source=${sample.source}")

            val failure = runCatching {
                verifyPlayback(sample, index + 1)
            }.exceptionOrNull()
            val elapsed = System.currentTimeMillis() - startedAt
            val success = failure == null
            val detail = failure?.let { "${it::class.java.simpleName}: ${it.message}" } ?: "ready"
            results += SweepResult(sample.label, sample.source, success, elapsed, detail)

            println(
                "TORRENT_TV_SWEEP result $prefix success=$success elapsed_ms=$elapsed detail=$detail"
            )
            if (success) {
                Log.i(TAG, "result $prefix success=true elapsed_ms=$elapsed")
            } else {
                Log.w(TAG, "result $prefix success=false elapsed_ms=$elapsed detail=$detail", failure)
            }

            runCatching { engineRepository.stopTorrentStream() }
            delay(SWITCH_SETTLE_MILLIS)
        }

        writeSummary(results)
        val successful = results.count(SweepResult::success)
        println("TORRENT_TV_SWEEP summary success=$successful total=${results.size}")
        Log.i(TAG, "summary success=$successful total=${results.size}")
        assertTrue(
            "No real Torrent TV sample reached visible Media3 playback. See sweep artifacts/logcat.",
            successful > 0
        )
    }

    private fun assertNoExternalAceEngine() {
        val installedPackages = context.packageManager
            .getInstalledApplications(0)
            .map { application -> application.packageName.lowercase() }
        assertTrue(
            "Diagnostic emulator must not contain an external Ace Stream engine",
            installedPackages.none { packageName ->
                packageName.contains("acestream") || packageName.contains("torrentstream")
            }
        )
    }

    private suspend fun loadSweepSamples(): List<SweepSample> {
        val provider = parsePlaylist(downloadPlaylistOrEmpty(PROVIDER_PLAYLIST_URL), "Provider")
        val dimonovich = parsePlaylist(downloadPlaylistOrEmpty(DIMONOVICH_PLAYLIST_URL), "Dimonovich")
        val discovered = (provider + dimonovich).distinctBy(SweepSample::source)

        val known = listOf(
            SweepSample(
                "Animal Planet HD known infohash",
                "$ACE_INFOHASH_DESCRIPTOR_PREFIX$ANIMAL_PLANET_INFO_HASH"
            ),
            SweepSample(
                "Viju+ Planet known content id",
                "$ACE_CONTENT_ID_DESCRIPTOR_PREFIX$VIJU_PLANET_CONTENT_ID"
            )
        )

        val preferred = PREFERRED_CHANNEL_HINTS.mapNotNull { hint ->
            discovered.firstOrNull { sample -> sample.label.contains(hint, ignoreCase = true) }
        }

        val spread = if (discovered.isEmpty()) {
            emptyList()
        } else {
            (0 until MAX_SWEEP_SAMPLES).map { slot ->
                discovered[(discovered.size * slot / MAX_SWEEP_SAMPLES).coerceAtMost(discovered.lastIndex)]
            }
        }

        return (known + preferred + spread)
            .distinctBy(SweepSample::source)
            .take(MAX_SWEEP_SAMPLES)
    }

    private fun parsePlaylist(text: String, sourceName: String): List<SweepSample> {
        var pendingLabel = "Torrent TV"
        return buildList {
            text.lineSequence().map(String::trim).forEach { line ->
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    pendingLabel = line.substringAfterLast(',').trim().ifEmpty { "Torrent TV" }
                    return@forEach
                }
                val contentId = CONTENT_ID_URL_PATTERN.find(line)
                    ?.groupValues?.getOrNull(1)?.lowercase()
                if (contentId != null) {
                    add(
                        SweepSample(
                            "$sourceName $pendingLabel content_id",
                            "$ACE_CONTENT_ID_DESCRIPTOR_PREFIX$contentId"
                        )
                    )
                    return@forEach
                }
                val infoHash = INFOHASH_URL_PATTERN.find(line)
                    ?.groupValues?.getOrNull(1)?.lowercase()
                if (infoHash != null) {
                    add(
                        SweepSample(
                            "$sourceName $pendingLabel infohash",
                            "$ACE_INFOHASH_DESCRIPTOR_PREFIX$infoHash"
                        )
                    )
                }
            }
        }
    }

    private suspend fun downloadPlaylistOrEmpty(url: String): String = withContext(Dispatchers.IO) {
        val connection = openHttp(url)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            println("TORRENT_TV_SWEEP playlist http=$code chars=${text.length} url=$url")
            if (code in 200..299) text else ""
        } catch (error: Throwable) {
            println("TORRENT_TV_SWEEP playlist_unavailable url=$url reason=${error.message}")
            ""
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verifyPlayback(sample: SweepSample, index: Int) {
        val resolved = withTimeout(RESOLVE_TIMEOUT_MILLIS) {
            engineRepository.resolveTorrentStream(sample.source)
        }
        println("TORRENT_TV_SWEEP resolve label=${sample.label} result=$resolved")
        check(resolved is AppResult.Success) {
            "engine did not resolve a playable local stream: $resolved"
        }

        val localUrl = resolved.data
        val (httpCode, firstBytes) = readRange(localUrl, MAX_PROBE_BYTES)
        println(
            "TORRENT_TV_SWEEP bytes label=${sample.label} http=$httpCode count=${firstBytes.size}"
        )
        check(httpCode == HttpURLConnection.HTTP_OK || httpCode == HttpURLConnection.HTTP_PARTIAL) {
            "embedded stream HTTP $httpCode"
        }
        check(firstBytes.isNotEmpty()) { "embedded stream returned no media bytes" }

        val mediaFailure = awaitVisibleMedia3(
            url = localUrl,
            sampleLabel = sample.label,
            screenshotPrefix = "%02d".format(index)
        )
        check(mediaFailure == null) { "Media3 playback failed: $mediaFailure" }
    }

    private suspend fun readRange(url: String, maxBytes: Int): Pair<Int, ByteArray> =
        withContext(Dispatchers.IO) {
            val connection = openHttp(url).apply {
                setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { input ->
                    val output = ByteArrayOutputStream(maxBytes)
                    val buffer = ByteArray(16 * 1024)
                    var remaining = maxBytes
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                    output.toByteArray()
                } ?: ByteArray(0)
                code to bytes
            } finally {
                connection.disconnect()
            }
        }

    private fun openHttp(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 35_000
            requestMethod = "GET"
            useCaches = false
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 IPTV-TV-App-CI-Sweep")
        }

    private suspend fun awaitVisibleMedia3(
        url: String,
        sampleLabel: String,
        screenshotPrefix: String
    ): String? {
        val ready = CompletableDeferred<Unit>()
        val firstFrame = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<String>()
        lateinit var player: ExoPlayer
        lateinit var playerView: PlayerView
        var rebufferCount = 0
        var lastPlaybackState = Player.STATE_IDLE

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            player = ExoPlayer.Builder(activity).build()
            playerView = PlayerView(activity).apply {
                useController = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player
            }
            (activity.window.decorView as ViewGroup).addView(playerView)
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (
                        playbackState == Player.STATE_BUFFERING &&
                        lastPlaybackState != Player.STATE_BUFFERING &&
                        ready.isCompleted
                    ) {
                        rebufferCount += 1
                    }
                    lastPlaybackState = playbackState
                    if (playbackState == Player.STATE_READY && !ready.isCompleted) {
                        ready.complete(Unit)
                    }
                }

                override fun onRenderedFirstFrame() {
                    if (!firstFrame.isCompleted) firstFrame.complete(Unit)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!failure.isCompleted) {
                        failure.complete("${error.errorCodeName}: ${error.message}")
                    }
                }
            })
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.playWhenReady = true
        }

        return try {
            val startupResult = withTimeoutOrNull(PLAYER_READY_TIMEOUT_MILLIS) {
                select<String> {
                    ready.onAwait { "" }
                    failure.onAwait { message -> message }
                }
            } ?: return captureAndReturn(
                screenshotPrefix,
                sampleLabel,
                "ready-timeout",
                "Media3 readiness timeout"
            )
            if (startupResult.isNotEmpty()) {
                return captureAndReturn(
                    screenshotPrefix,
                    sampleLabel,
                    "player-error",
                    startupResult
                )
            }

            val rendered = withTimeoutOrNull(FIRST_FRAME_TIMEOUT_MILLIS) {
                firstFrame.await()
                true
            } ?: false
            delay(VISIBLE_STABILITY_MILLIS)

            var state = Player.STATE_IDLE
            var position = 0L
            scenario.onActivity {
                state = player.playbackState
                position = player.currentPosition
            }
            captureScreenshot(
                screenshotPrefix,
                sampleLabel,
                if (rendered) "ready-frame" else "ready-no-frame"
            )
            println(
                "TORRENT_TV_SWEEP media3 label=$sampleLabel state=$state position_ms=$position " +
                    "first_frame=$rendered rebuffers=$rebufferCount"
            )
            when {
                state != Player.STATE_READY -> "Media3 did not remain READY: state=$state"
                !rendered -> "Media3 reached READY but rendered no first video frame"
                position < MIN_VISIBLE_POSITION_MILLIS ->
                    "Media3 position did not advance enough: $position ms"
                else -> null
            }
        } catch (error: Throwable) {
            captureScreenshot(screenshotPrefix, sampleLabel, "exception")
            "${error::class.java.simpleName}: ${error.message}"
        } finally {
            runCatching {
                scenario.onActivity { activity ->
                    playerView.player = null
                    (playerView.parent as? ViewGroup)?.removeView(playerView)
                    player.release()
                    activity.finish()
                }
            }
            scenario.close()
        }
    }

    private fun captureAndReturn(
        prefix: String,
        label: String,
        suffix: String,
        message: String
    ): String {
        captureScreenshot(prefix, label, suffix)
        return message
    }

    private fun captureScreenshot(prefix: String, label: String, suffix: String) {
        val bitmap = runCatching { instrumentation.uiAutomation.takeScreenshot() }.getOrNull() ?: return
        val directory = File(context.getExternalFilesDir(null), ARTIFACT_DIRECTORY).apply { mkdirs() }
        val safeLabel = label.lowercase()
            .replace(Regex("[^a-z0-9а-яё]+", RegexOption.IGNORE_CASE), "-")
            .trim('-')
            .take(80)
            .ifEmpty { "sample" }
        val file = File(directory, "$prefix-$safeLabel-$suffix.png")
        runCatching {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            println("TORRENT_TV_SWEEP screenshot path=${file.absolutePath}")
        }
        bitmap.recycle()
    }

    private fun writeSummary(results: List<SweepResult>) {
        val directory = File(context.getExternalFilesDir(null), ARTIFACT_DIRECTORY).apply { mkdirs() }
        File(directory, "summary.tsv").writeText(
            buildString {
                appendLine("success\telapsed_ms\tlabel\tsource\tdetail")
                results.forEach { result ->
                    append(result.success)
                    append('\t')
                    append(result.elapsedMillis)
                    append('\t')
                    append(result.label.replace('\t', ' '))
                    append('\t')
                    append(result.source)
                    append('\t')
                    appendLine(result.detail.replace('\t', ' ').replace('\n', ' '))
                }
            }
        )
    }

    private data class SweepSample(val label: String, val source: String)

    private data class SweepResult(
        val label: String,
        val source: String,
        val success: Boolean,
        val elapsedMillis: Long,
        val detail: String
    )

    private companion object {
        const val TAG = "TorrentTvSweep"
        const val ARTIFACT_DIRECTORY = "torrent-tv-sweep"
        const val PROVIDER_PLAYLIST_URL = "https://iptv.org.ua/iptv/provayder.m3u"
        const val DIMONOVICH_PLAYLIST_URL =
            "https://raw.githubusercontent.com/Dimonovich/TV/Dimonovich/FREE/TV"
        const val ACE_INFOHASH_DESCRIPTOR_PREFIX = "http://127.0.0.1:6878/ace/getstream?infohash="
        const val ACE_CONTENT_ID_DESCRIPTOR_PREFIX = "http://127.0.0.1:6878/ace/getstream?id="
        const val ANIMAL_PLANET_INFO_HASH = "568159b1059c7bbe3eaf40f123541fef86ef83cb"
        const val VIJU_PLANET_CONTENT_ID = "0d59f0292f9e5569f4dff50ac4c3c89913b32a7a"
        const val MAX_SWEEP_SAMPLES = 8
        const val MAX_PROBE_BYTES = 64 * 1024
        const val RESOLVE_TIMEOUT_MILLIS = 70_000L
        const val PLAYER_READY_TIMEOUT_MILLIS = 45_000L
        const val FIRST_FRAME_TIMEOUT_MILLIS = 12_000L
        const val VISIBLE_STABILITY_MILLIS = 5_000L
        const val MIN_VISIBLE_POSITION_MILLIS = 2_000L
        const val SWITCH_SETTLE_MILLIS = 1_000L

        val PREFERRED_CHANNEL_HINTS = listOf(
            "Discovery",
            "Nat Geo",
            "National Geographic",
            "Охота",
            "Animal Planet",
            "Viju+ Planet"
        )
        val CONTENT_ID_URL_PATTERN = Regex(
            "(?:[?&](?:id|content_id)=)([0-9a-fA-F]{40})(?:[&#]|$)",
            RegexOption.IGNORE_CASE
        )
        val INFOHASH_URL_PATTERN = Regex(
            "(?:[?&]infohash=)([0-9a-fA-F]{40})(?:[&#]|$)",
            RegexOption.IGNORE_CASE
        )
    }
}
