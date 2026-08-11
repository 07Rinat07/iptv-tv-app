package com.iptv.tv

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.EngineRepository
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
 * Network-backed diagnostic smoke using the real provider and Dimonovich playlist URLs.
 *
 * The emulator has no Ace Stream Engine installed or running. The test downloads the playlist,
 * selects a known provider `infohash` and a current Ace `id`, runs each descriptor through the
 * production EngineRepository, probes the resulting local stream for real bytes and asks Media3 to
 * stay playable. The infohash stream is then started again after a full stop to cover switching and
 * restart cleanup.
 */
@RunWith(AndroidJUnit4::class)
class TorrentTvPlaybackSmokeTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val engineRepository: EngineRepository by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ApplicationEngineEntryPoint::class.java
        ).engineRepository()
    }

    @After
    fun cleanup() {
        runBlocking {
            runCatching { engineRepository.stopTorrentStream() }
        }
    }

    @Test
    fun realPlaylistTorrentTvSamplesActuallyReachMedia3ReadyWithoutAceEngine() = runBlocking {
        val installedPackages = context.packageManager
            .getInstalledApplications(0)
            .map { application -> application.packageName.lowercase() }
        assertTrue(
            "A clean smoke device must not contain an external Ace Stream engine",
            installedPackages.none { packageName ->
                packageName.contains("acestream") || packageName.contains("torrentstream")
            }
        )

        val providerLines = downloadPlaylist(PROVIDER_PLAYLIST_URL)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val dimonovichLines = downloadPlaylist(DIMONOVICH_PLAYLIST_URL)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        assertTrue(
            "Dimonovich playlist did not contain Torrent TV descriptors",
            dimonovichLines.any { line ->
                line.startsWith("http://127.0.0.1:6878/ace/getstream?", ignoreCase = true)
            }
        )
        val infoHashSource = providerLines.firstOrNull {
            it.contains("infohash=$ANIMAL_PLANET_INFO_HASH", ignoreCase = true)
        }
        val contentIdSource = dimonovichLines.firstOrNull {
            it.contains("id=$VIJU_PLANET_CONTENT_ID", ignoreCase = true)
        }

        assertTrue("Playlist did not contain a Torrent TV infohash entry", !infoHashSource.isNullOrBlank())
        assertTrue("Playlist did not contain a Torrent TV content-id entry", !contentIdSource.isNullOrBlank())

        val failures = mutableListOf<String>()
        listOf(
            "provider Animal Planet HD infohash" to infoHashSource!!,
            "Dimonovich Viju+ Planet Ace content id" to contentIdSource!!,
            "provider Animal Planet HD restart" to infoHashSource
        ).forEach { (label, source) ->
            val failure = runCatching { verifyPlayback(label, source) }.exceptionOrNull()
            if (failure != null) {
                val message = "$label failed: ${failure::class.java.simpleName}: ${failure.message}"
                failures += message
                println("TORRENT_TV_SMOKE failure $message")
                Log.e(TAG, message, failure)
            }
            val stopped = engineRepository.stopTorrentStream()
            if (stopped !is AppResult.Success) {
                failures += "$label stop failed: $stopped"
            }
        }

        assertTrue(
            "Real Torrent TV playback failures:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    private suspend fun downloadPlaylist(url: String): String = withContext(Dispatchers.IO) {
        val connection = openHttp(url)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            println("TORRENT_TV_SMOKE playlist http=$code chars=${text.length}")
            Log.i(TAG, "playlist http=$code chars=${text.length}")
            check(code in 200..299) { "Playlist HTTP $code" }
            check(text.isNotBlank()) { "Playlist response is empty" }
            text
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verifyPlayback(label: String, source: String) {
        println("TORRENT_TV_SMOKE start label=$label source=$source")
        Log.i(TAG, "start label=$label source=$source")

        val resolved = withTimeout(75_000) {
            engineRepository.resolveTorrentStream(source)
        }
        println("TORRENT_TV_SMOKE resolve label=$label result=$resolved")
        Log.i(TAG, "resolve label=$label result=$resolved")

        check(resolved is AppResult.Success) {
            "$label: engine did not resolve a playable local stream: $resolved"
        }
        val localUrl = resolved.data
        val (httpCode, firstBytes) = readRange(localUrl, MAX_PROBE_BYTES)

        println(
            "TORRENT_TV_SMOKE bytes label=$label http=$httpCode count=${firstBytes.size} url=$localUrl"
        )
        Log.i(TAG, "bytes label=$label http=$httpCode count=${firstBytes.size}")
        check(httpCode == HttpURLConnection.HTTP_OK || httpCode == HttpURLConnection.HTTP_PARTIAL) {
            "$label: embedded stream HTTP $httpCode"
        }
        check(firstBytes.isNotEmpty()) { "$label: embedded stream returned no media bytes" }

        val playerFailure = awaitMedia3Stable(localUrl)
        check(playerFailure == null) {
            "$label: Media3 playback was not stable: $playerFailure"
        }

        println("TORRENT_TV_SMOKE ready label=$label bytes=${firstBytes.size}")
        Log.i(TAG, "ready label=$label bytes=${firstBytes.size}")
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
        }

    private suspend fun awaitMedia3Stable(url: String): String? {
        val ready = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<String>()
        lateinit var player: ExoPlayer

        withContext(Dispatchers.Main) {
            player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !ready.isCompleted) {
                        ready.complete(Unit)
                    }
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
            val startupResult = withTimeoutOrNull(PLAYER_READY_TIMEOUT_MS) {
                select<String> {
                    ready.onAwait { "" }
                    failure.onAwait { message -> message }
                }
            } ?: return "Media3 readiness timeout"
            if (startupResult.isNotEmpty()) return startupResult

            val startPosition = withContext(Dispatchers.Main) { player.currentPosition }
            val runtimeFailure = withTimeoutOrNull(PLAYBACK_STABILITY_MS) { failure.await() }
            if (runtimeFailure != null) return runtimeFailure

            val (state, endPosition) = withContext(Dispatchers.Main) {
                player.playbackState to player.currentPosition
            }
            if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) {
                "Media3 left the playable state: state=$state"
            } else if (endPosition - startPosition < MIN_POSITION_ADVANCE_MS) {
                "Playback position stalled: start=$startPosition end=$endPosition"
            } else {
                null
            }
        } catch (error: Throwable) {
            "${error::class.java.simpleName}: ${error.message}"
        } finally {
            withContext(Dispatchers.Main) {
                player.release()
            }
        }
    }

    private companion object {
        const val TAG = "TorrentTvSmoke"
        const val PROVIDER_PLAYLIST_URL = "https://iptv.org.ua/iptv/provayder.m3u"
        const val DIMONOVICH_PLAYLIST_URL =
            "https://raw.githubusercontent.com/Dimonovich/TV/Dimonovich/FREE/TV"
        const val ANIMAL_PLANET_INFO_HASH = "568159b1059c7bbe3eaf40f123541fef86ef83cb"
        const val VIJU_PLANET_CONTENT_ID = "0d59f0292f9e5569f4dff50ac4c3c89913b32a7a"
        const val MAX_PROBE_BYTES = 128 * 1024
        const val PLAYER_READY_TIMEOUT_MS = 45_000L
        const val PLAYBACK_STABILITY_MS = 8_000L
        const val MIN_POSITION_ADVANCE_MS = 3_000L
    }
}
