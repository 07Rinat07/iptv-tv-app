package com.iptv.tv

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for live MPEG-TS startup: Media3 must become READY while HTTP is still streaming. */
@RunWith(AndroidJUnit4::class)
class ContinuousMpegTsReadySmokeTest {
    @Test
    fun media3ReachesReadyBeforeContinuousTsResponseEnds() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixture = instrumentation.context.assets
            .open(FIXTURE_ASSET)
            .use { input -> input.readBytes() }
        assertTrue("MPEG-TS fixture must contain several packets", fixture.size >= 188 * 10)
        assertTrue("MPEG-TS fixture must start on a sync byte", fixture.first() == 0x47.toByte())

        ContinuousTsServer(fixture).use { server ->
            val ready = CompletableDeferred<Long>()
            val failure = CompletableDeferred<String>()
            lateinit var player: ExoPlayer

            withContext(Dispatchers.Main) {
                player = ExoPlayer.Builder(instrumentation.targetContext).build().apply {
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> if (!ready.isCompleted) {
                                    ready.complete(SystemClock.elapsedRealtime())
                                }
                                Player.STATE_ENDED -> if (!failure.isCompleted) {
                                    failure.complete("Media3 reached END before READY")
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            if (!failure.isCompleted) {
                                failure.complete("${error.errorCodeName}: ${error.message}")
                            }
                        }
                    })
                    volume = 0f
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(server.url)
                            .setMimeType(MimeTypes.VIDEO_MP2T)
                            .build()
                    )
                    prepare()
                    playWhenReady = true
                }
            }

            try {
                val startedAt = SystemClock.elapsedRealtime()
                val result = withTimeout(READY_TIMEOUT_MS) {
                    select<String> {
                        ready.onAwait { readyAt -> "ready:${readyAt - startedAt}" }
                        failure.onAwait { message -> "failure:$message" }
                    }
                }
                assertTrue("Continuous MPEG-TS did not reach READY: $result", result.startsWith("ready:"))
                assertTrue("Media3 never connected to the live HTTP fixture", server.clientConnected)
                assertTrue("Media3 reached READY without receiving TS bytes", server.bytesSent > 0L)
                assertFalse(
                    "Continuous HTTP response ended before Media3 READY; regression would allow EOF-only startup",
                    server.responseEnded
                )
                assertTrue(
                    "Fixture server failed while Media3 was starting: ${server.failure?.message}",
                    server.failure == null
                )
                assertTrue(
                    "Media3 was not READY at the assertion boundary",
                    withContext(Dispatchers.Main) { player.playbackState == Player.STATE_READY }
                )
            } finally {
                withContext(Dispatchers.Main) { player.release() }
            }
        }
    }

    private class ContinuousTsServer(
        private val fixture: ByteArray
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val connected = AtomicBoolean(false)
        private val ended = AtomicBoolean(false)
        private val sent = AtomicLong(0L)
        private val workerFailure = AtomicReference<Throwable?>(null)
        private val server = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK_HOST))
        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "continuous-ts-test-server"
        ) {
            try {
                server.accept().use { socket ->
                    connected.set(true)
                    socket.tcpNoDelay = true
                    val reader = socket.getInputStream()
                        .bufferedReader(StandardCharsets.US_ASCII)
                    while (true) {
                        val line = reader.readLine() ?: return@use
                        if (line.isEmpty()) break
                    }

                    val output = socket.getOutputStream()
                    output.write(
                        ("HTTP/1.1 200 OK\r\n" +
                            "Content-Type: video/mp2t\r\n" +
                            "Cache-Control: no-store\r\n" +
                            "Connection: close\r\n\r\n")
                            .toByteArray(StandardCharsets.US_ASCII)
                    )
                    output.flush()

                    while (!closed.get()) {
                        var offset = 0
                        while (offset < fixture.size && !closed.get()) {
                            val count = minOf(STREAM_CHUNK_BYTES, fixture.size - offset)
                            output.write(fixture, offset, count)
                            output.flush()
                            sent.addAndGet(count.toLong())
                            offset += count
                            Thread.sleep(STREAM_CHUNK_DELAY_MS)
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) workerFailure.set(error)
            } finally {
                ended.set(true)
            }
        }

        val url: String = "http://$LOOPBACK_HOST:${server.localPort}/continuous.ts"
        val clientConnected: Boolean get() = connected.get()
        val responseEnded: Boolean get() = ended.get()
        val bytesSent: Long get() = sent.get()
        val failure: Throwable? get() = workerFailure.get()

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { server.close() }
            worker.interrupt()
            runCatching { worker.join(1_000L) }
        }
    }

    private companion object {
        const val FIXTURE_ASSET = "continuous_h264_128x72.ts"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val READY_TIMEOUT_MS = 10_000L
        const val STREAM_CHUNK_BYTES = 188 * 7
        const val STREAM_CHUNK_DELAY_MS = 20L
    }
}
