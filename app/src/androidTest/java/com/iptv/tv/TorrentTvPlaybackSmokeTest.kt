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
 * Network-backed diagnostic smoke using known real Torrent TV swarms.
 *
 * The emulator has no Ace Stream Engine installed or running. When the public playlists are
 * reachable, the test refreshes the descriptor text from them. If a playlist host rejects the CI
 * runner (for example HTTP 403), the same known infohash/content-id descriptors are constructed
 * locally so an unrelated playlist outage cannot hide the real P2P playback signal. Every sample
 * still goes through the production EngineRepository, real peer discovery, the embedded loopback
 * stream and Media3. The infohash stream is then started again after a full stop to cover switching
 * and restart cleanup. Content-id availability is inherently volatile, so the default smoke tries
 * a bounded set of current playlist candidates and requires at least one of them to play.
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
    fun realTorrentTvSamplesActuallyReachMedia3ReadyWithoutAceEngine() = runBlocking {
        val installedPackages = context.packageManager
            .getInstalledApplications(0)
            .map { application -> application.packageName.lowercase() }
        assertTrue(
            "A clean smoke device must not contain an external Ace Stream engine",
            installedPackages.none { packageName ->
                packageName.contains("acestream") || packageName.contains("torrentstream")
            }
        )

        val arguments = InstrumentationRegistry.getArguments()
        val requestedSample = arguments
            .getString("torrentSample")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val stabilityMillis = arguments
            .getString("torrentStabilityMs")
            ?.toLongOrNull()
            ?.coerceIn(MIN_CONFIGURABLE_STABILITY_MS, MAX_CONFIGURABLE_STABILITY_MS)
            ?: PLAYBACK_STABILITY_MS
        val customContentIdText = arguments.getString("torrentContentId")?.trim().orEmpty()
        require(customContentIdText.isEmpty() || CONTENT_ID_PATTERN.matches(customContentIdText)) {
            "torrentContentId must be a 40-character hexadecimal value"
        }
        val samples = if (customContentIdText.isNotEmpty()) {
            listOf(
                SmokeSample(
                    label = "custom Ace content id",
                    source = "$ACE_CONTENT_ID_DESCRIPTOR_PREFIX${customContentIdText.lowercase()}",
                    requirement = SampleRequirement.REQUIRED
                )
            )
        } else {
            defaultTorrentTvSamples(requestedSample)
        }
        assertTrue("No Torrent TV smoke sample matched '$requestedSample'", samples.isNotEmpty())

        val failures = mutableListOf<String>()
        val contentIdCandidateFailures = mutableListOf<String>()
        var contentIdCandidateSucceeded = false
        samples.forEach { sample ->
            if (
                sample.requirement == SampleRequirement.CONTENT_ID_QUORUM &&
                contentIdCandidateSucceeded
            ) {
                return@forEach
            }
            val (label, source) = sample
            val failure = runCatching {
                verifyPlayback(label, source, stabilityMillis)
            }.exceptionOrNull()
            if (failure != null) {
                val message = "$label failed: ${failure::class.java.simpleName}: ${failure.message}"
                if (sample.requirement == SampleRequirement.CONTENT_ID_QUORUM) {
                    contentIdCandidateFailures += message
                    println("TORRENT_TV_SMOKE candidate_unavailable $message")
                    Log.w(TAG, message, failure)
                } else {
                    failures += message
                    println("TORRENT_TV_SMOKE failure $message")
                    Log.e(TAG, message, failure)
                }
            } else if (sample.requirement == SampleRequirement.CONTENT_ID_QUORUM) {
                contentIdCandidateSucceeded = true
            }
            val stopped = engineRepository.stopTorrentStream()
            if (stopped !is AppResult.Success) {
                failures += "$label stop failed: $stopped"
            }
        }

        if (
            samples.any { it.requirement == SampleRequirement.CONTENT_ID_QUORUM } &&
            !contentIdCandidateSucceeded
        ) {
            failures += buildString {
                append("No current Ace content-id candidate reached Media3 READY")
                if (contentIdCandidateFailures.isNotEmpty()) {
                    append(":\n")
                    append(contentIdCandidateFailures.joinToString("\n"))
                }
            }
        }

        assertTrue(
            "Real Torrent TV playback failures:\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
    }

    private suspend fun defaultTorrentTvSamples(requestedSample: String?): List<SmokeSample> {
        val providerLines = downloadPlaylistOrEmpty(PROVIDER_PLAYLIST_URL)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()
        val dimonovichLines = downloadPlaylistOrEmpty(DIMONOVICH_PLAYLIST_URL)
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

        val infoHashSource = providerLines.firstOrNull {
            it.contains("infohash=$ANIMAL_PLANET_INFO_HASH", ignoreCase = true)
        } ?: "$ACE_INFOHASH_DESCRIPTOR_PREFIX$ANIMAL_PLANET_INFO_HASH"
        val contentIdSamples = selectContentIdCandidates(dimonovichLines)

        return buildList {
            add(
                SmokeSample(
                    label = "provider Animal Planet HD infohash",
                    source = infoHashSource,
                    requirement = SampleRequirement.REQUIRED
                )
            )
            addAll(contentIdSamples)
            add(
                SmokeSample(
                    label = "provider Animal Planet HD restart",
                    source = infoHashSource,
                    requirement = SampleRequirement.REQUIRED
                )
            )
        }.filter { sample ->
            requestedSample == null || sample.label.contains(requestedSample, ignoreCase = true)
        }
    }

    private fun selectContentIdCandidates(lines: List<String>): List<SmokeSample> {
        var pendingLabel = "Ace content id"
        val playlistCandidates = buildList {
            lines.forEach { line ->
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    pendingLabel = line.substringAfterLast(',').trim().ifEmpty { "Ace content id" }
                    return@forEach
                }
                val contentId = CONTENT_ID_URL_PATTERN.find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.lowercase()
                    ?: return@forEach
                add(
                    SmokeSample(
                        label = "Dimonovich $pendingLabel Ace content id",
                        source = "$ACE_CONTENT_ID_DESCRIPTOR_PREFIX$contentId",
                        requirement = SampleRequirement.CONTENT_ID_QUORUM
                    )
                )
            }
        }.distinctBy(SmokeSample::source)

        val knownSource = "$ACE_CONTENT_ID_DESCRIPTOR_PREFIX$VIJU_PLANET_CONTENT_ID"
        val knownSample = playlistCandidates.firstOrNull { it.source == knownSource }
            ?: SmokeSample(
                label = "Dimonovich Viju+ Planet Ace content id",
                source = knownSource,
                requirement = SampleRequirement.CONTENT_ID_QUORUM
            )
        if (playlistCandidates.size <= 1) return listOf(knownSample)

        val knownIndex = playlistCandidates.indexOfFirst { it.source == knownSource }
            .takeIf { it >= 0 }
            ?: 0
        val fallbackOffsets = buildList {
            add(1)
            (1 until MAX_CONTENT_ID_CANDIDATES).forEach { slot ->
                add(maxOf(1, playlistCandidates.size * slot / MAX_CONTENT_ID_CANDIDATES))
            }
        }.distinct()
        return buildList {
            add(knownSample)
            fallbackOffsets.forEach { offset ->
                add(playlistCandidates[(knownIndex + offset) % playlistCandidates.size])
            }
        }.distinctBy(SmokeSample::source).take(MAX_CONTENT_ID_CANDIDATES)
    }

    private suspend fun downloadPlaylistOrEmpty(url: String): String = withContext(Dispatchers.IO) {
        val connection = openHttp(url)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            println("TORRENT_TV_SMOKE playlist http=$code chars=${text.length} url=$url")
            Log.i(TAG, "playlist http=$code chars=${text.length} url=$url")
            if (code in 200..299 && text.isNotBlank()) text else ""
        } catch (error: Throwable) {
            println("TORRENT_TV_SMOKE playlist unavailable url=$url reason=${error.message}")
            Log.w(TAG, "playlist unavailable url=$url", error)
            ""
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun verifyPlayback(label: String, source: String, stabilityMillis: Long) {
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

        val playerFailure = awaitMedia3Stable(localUrl, stabilityMillis)
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
            setRequestProperty("User-Agent", "Mozilla/5.0 IPTV-TV-App-CI")
        }

    private suspend fun awaitMedia3Stable(url: String, stabilityMillis: Long): String? {
        val ready = CompletableDeferred<Unit>()
        val failure = CompletableDeferred<String>()
        lateinit var player: ExoPlayer
        var rebufferCount = 0
        var lastPlaybackState = Player.STATE_IDLE

        withContext(Dispatchers.Main) {
            player = ExoPlayer.Builder(context).build()
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
            val runtimeFailure = withTimeoutOrNull(stabilityMillis) { failure.await() }
            if (runtimeFailure != null) return runtimeFailure

            val (state, endPosition) = withContext(Dispatchers.Main) {
                player.playbackState to player.currentPosition
            }
            Log.i(
                TAG,
                "stable state=$state startPosition=$startPosition endPosition=$endPosition " +
                    "advance=${endPosition - startPosition} rebuffers=$rebufferCount"
            )
            if (state != Player.STATE_READY) {
                "Media3 did not remain ready: state=$state"
            } else if (endPosition - startPosition < stabilityMillis * 2L / 3L) {
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
        data class SmokeSample(
            val label: String,
            val source: String,
            val requirement: SampleRequirement
        )

        enum class SampleRequirement {
            REQUIRED,
            CONTENT_ID_QUORUM
        }

        const val TAG = "TorrentTvSmoke"
        const val PROVIDER_PLAYLIST_URL = "https://iptv.org.ua/iptv/provayder.m3u"
        const val DIMONOVICH_PLAYLIST_URL =
            "https://raw.githubusercontent.com/Dimonovich/TV/Dimonovich/FREE/TV"
        const val ACE_INFOHASH_DESCRIPTOR_PREFIX = "http://127.0.0.1:6878/ace/getstream?infohash="
        const val ACE_CONTENT_ID_DESCRIPTOR_PREFIX = "http://127.0.0.1:6878/ace/getstream?id="
        const val ANIMAL_PLANET_INFO_HASH = "568159b1059c7bbe3eaf40f123541fef86ef83cb"
        const val VIJU_PLANET_CONTENT_ID = "0d59f0292f9e5569f4dff50ac4c3c89913b32a7a"
        val CONTENT_ID_PATTERN = Regex("^[0-9a-fA-F]{40}$")
        val CONTENT_ID_URL_PATTERN = Regex(
            "(?:[?&](?:id|content_id)=)([0-9a-fA-F]{40})(?:[&#]|$)",
            RegexOption.IGNORE_CASE
        )
        const val MAX_CONTENT_ID_CANDIDATES = 8
        const val MAX_PROBE_BYTES = 128 * 1024
        const val PLAYER_READY_TIMEOUT_MS = 45_000L
        const val PLAYBACK_STABILITY_MS = 45_000L
        const val MIN_CONFIGURABLE_STABILITY_MS = 10_000L
        const val MAX_CONFIGURABLE_STABILITY_MS = 120_000L
    }
}
