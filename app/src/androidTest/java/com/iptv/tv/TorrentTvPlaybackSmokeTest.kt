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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Network-backed diagnostic smoke using the real Dimonovich playlist URL.
 *
 * The emulator has no Ace Stream Engine installed or running. The test downloads the playlist,
 * selects one Torrent TV entry with explicit `infohash` and one with Ace `id`, runs each descriptor
 * through the production EngineRepository, probes the resulting local stream for real bytes and then
 * asks Media3 to reach STATE_READY.
 */
@RunWith(AndroidJUnit4::class)
class TorrentTvPlaybackSmokeTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val engineRepository: EngineRepository by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            EngineEntryPoint::class.java
        ).engineRepository()
    }

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    @After
    fun cleanup() = runBlocking {
        runCatching { engineRepository.stopTorrentStream() }
    }

    @Test
    fun realPlaylistTorrentTvSamplesActuallyReachMedia3ReadyWithoutAceEngine() = runBlocking {
        val playlist = downloadPlaylist()
        val lines = playlist.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val infoHashSource = lines.firstOrNull {
            it.startsWith("http://127.0.0.1:6878/ace/getstream?", ignoreCase = true) &&
                it.contains("infohash=", ignoreCase = true)
        }
        val contentIdSource = lines.firstOrNull {
            it.startsWith("http://127.0.0.1:6878/ace/getstream?", ignoreCase = true) &&
                (it.contains("?id=", ignoreCase = true) || it.contains("&id=", ignoreCase = true))
        }

        assertTrue("Playlist did not contain a Torrent TV infohash entry", !infoHashSource.isNullOrBlank())
        assertTrue("Playlist did not contain a Torrent TV content-id entry", !contentIdSource.isNullOrBlank())

        val failures = mutableListOf<String>()
        listOf(
            "playlist explicit infohash" to infoHashSource!!,
            "playlist Ace content id" to contentIdSource!!
        ).forEach { (label, source) ->
            val failure = runCatching { verifyPlayback(label, source) }.exceptionOrNull()
            if (failure != null) {
                val message = "$label failed: ${failure::class.java.simpleName}: ${failure.message}"
                failures += message
                println("TORRENT_TV_SMOKE failure $message")
                Log.e(TAG, message, failure)
            }
            runCatching { engineRepository.stopTorrentStream() }
        }

        assertTrue(
            "Real Torrent TV playback failures:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    private suspend fun downloadPlaylist(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(PLAYLIST_URL).get().build()
        probeClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            println("TORRENT_TV_SMOKE playlist http=${response.code} chars=${text.length}")
            Log.i(TAG, "playlist http=${response.code} chars=${text.length}")
            check(response.isSuccessful) { "Playlist HTTP ${response.code}" }
            check(text.isNotBlank()) { "Playlist response is empty" }
            text
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

        val firstBytes = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(localUrl)
                .header("Range", "bytes=0-131071")
                .get()
                .build()
            probeClient.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes().orEmpty()
                println(
                    "TORRENT_TV_SMOKE bytes label=$label http=${response.code} count=${bytes.size} url=$localUrl"
                )
                Log.i(TAG, "bytes label=$label http=${response.code} count=${bytes.size}")
                bytes
            }
        }

        check(firstBytes.isNotEmpty()) { "$label: embedded stream returned no media bytes" }

        val playerFailure = awaitMedia3Ready(localUrl)
        check(playerFailure == null) {
            "$label: Media3 did not reach STATE_READY: $playerFailure"
        }

        println("TORRENT_TV_SMOKE ready label=$label bytes=${firstBytes.size}")
        Log.i(TAG, "ready label=$label bytes=${firstBytes.size}")
    }

    private suspend fun awaitMedia3Ready(url: String): String? {
        val result = CompletableDeferred<String?>()
        lateinit var player: ExoPlayer

        withContext(Dispatchers.Main) {
            player = ExoPlayer.Builder(context).build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !result.isCompleted) {
                        result.complete(null)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (!result.isCompleted) {
                        result.complete("${error.errorCodeName}: ${error.message}")
                    }
                }
            })
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
        }

        return try {
            withTimeout(45_000) { result.await() }
        } catch (error: Throwable) {
            "${error::class.java.simpleName}: ${error.message}"
        } finally {
            withContext(Dispatchers.Main) {
                player.release()
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EngineEntryPoint {
        fun engineRepository(): EngineRepository
    }

    private companion object {
        const val TAG = "TorrentTvSmoke"
        const val PLAYLIST_URL = "https://raw.githubusercontent.com/Dimonovich/TV/Dimonovich/FREE/TV"
    }
}
