package com.iptv.tv.core.p2p

import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveEmbeddedRuntimeReadinessQualificationTest {
    @Test
    fun `prepare infohash reaches startup ready and serves media through production runtime`() = runBlocking {
        LocalTcpLivePeer().use { peer ->
            val diagnostics = CopyOnWriteArrayList<Pair<String, String>>()
            val engine = AceLiveEmbeddedEngine(
                okHttpClient = OkHttpClient(),
                bufferSettings = AceLiveBufferSettings(
                    mode = AceLiveBufferMode.MANUAL,
                    manualStartupBufferBytes = STARTUP_BYTES.toLong(),
                    outputBufferBytes = 4 * 1024 * 1024,
                    startupTimeoutMillis = 10_000,
                    mediaStallTimeoutMillis = 5_000
                ),
                diagnosticsObserver = { status, message -> diagnostics += status to message }
            )
            engine.peerDiscoveryRunner = AceLiveEmbeddedPeerDiscoveryRunner { _, _, request ->
                assertEquals(SWARM_KEY, request.dhtRequest?.swarmKey)
                AceLivePeerDiscoveryOrchestrationResult(
                    peers = List(DHT_BATCH_SIZE) {
                        AceLiveDiscoveredPeer(
                            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", peer.port),
                            sources = setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                        )
                    },
                    dht = AceLivePeerDiscoverySourceSummary(
                        status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
                        returnedPeerCount = DHT_BATCH_SIZE
                    ),
                    tracker = AceLivePeerDiscoverySourceSummary(
                        status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
                        returnedPeerCount = 0
                    )
                )
            }

            try {
                val result = try {
                    withTimeout(8_000) {
                        engine.prepareInfoHash(INFO_HASH)
                    }
                } catch (error: Throwable) {
                    peer.throwIfFailed()
                    throw error
                }
                assertTrue(result is P2pResult.Success)
                val prepared = (result as P2pResult.Success).data
                assertEquals("Ace Live", prepared.name)
                assertTrue(prepared.url.startsWith("http://127.0.0.1:"))
                assertTrue(prepared.url.endsWith("/live.ts"))

                assertTrue(peer.awaitAllChunkResponses())
                peer.throwIfFailed()

                val delivered = readLoopbackPrefix(prepared.url, STARTUP_BYTES)
                assertArrayEquals(PIECE_DATA.copyOf(STARTUP_BYTES), delivered)

                assertTrue(
                    diagnostics.any { (status, message) ->
                        status == "embedded_ace_live_peer_discovery" &&
                            message.contains("phase=initial") &&
                            message.contains("dht=SUCCEEDED/$DHT_BATCH_SIZE")
                    }
                )
            } finally {
                engine.stopStream()
            }
        }
    }

    private fun readLoopbackPrefix(url: String, byteCount: Int): ByteArray {
        val uri = URI(url)
        Socket().use { socket ->
            socket.soTimeout = 5_000
            socket.connect(InetSocketAddress(uri.host, uri.port), 2_000)
            val output = socket.getOutputStream()
            output.write(
                (
                    "GET ${uri.rawPath} HTTP/1.1\r\n" +
                        "Host: ${uri.host}:${uri.port}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(Charsets.US_ASCII)
            )
            output.flush()

            val input = socket.getInputStream()
            val headers = readHttpHeaders(input)
            assertTrue(headers.startsWith("HTTP/1.1 200"))
            assertTrue(headers.contains("Content-Type: video/mp2t", ignoreCase = true))
            return readExactly(input, byteCount)
        }
    }

    private fun readHttpHeaders(input: InputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val next = input.read()
            check(next >= 0) { "Loopback HTTP server closed before headers completed" }
            bytes += next.toByte()
            val size = bytes.size
            if (
                size >= 4 &&
                bytes[size - 4] == '\r'.code.toByte() &&
                bytes[size - 3] == '\n'.code.toByte() &&
                bytes[size - 2] == '\r'.code.toByte() &&
                bytes[size - 1] == '\n'.code.toByte()
            ) {
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            check(size <= 16 * 1024) { "Loopback HTTP response headers are too large" }
        }
    }

    private class LocalTcpLivePeer : Closeable {
        private val loopback = InetAddress.getByName("127.0.0.1")
        private val handshakeCodec = AceLivePeerHandshakeCodec()
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(loopback, 0))
            soTimeout = 8_000
        }
        private val failure = AtomicReference<Throwable?>(null)
        private val allChunkResponses = CountDownLatch(1)
        private val release = CountDownLatch(1)
        val port: Int = server.localPort

        private val worker = thread(
            start = true,
            isDaemon = true,
            name = "ace-embedded-runtime-readiness-peer"
        ) {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 8_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()

                    val handshakeBytes = readExactly(input, AceLivePeerHandshakeCodec.HANDSHAKE_BYTES)
                    val handshake = handshakeCodec.decode(
                        buffer = handshakeBytes,
                        expectedSwarmKey = SWARM_KEY_BYTES
                    )
                    check(handshake is AceLivePeerHandshakeDecodeResult.Decoded)
                    output.write(
                        handshakeCodec.encode(
                            swarmKey = SWARM_KEY_BYTES,
                            peerId = SERVER_PEER_ID
                        )
                    )
                    output.flush()

                    var interestedSeen = false
                    while (!interestedSeen) {
                        val frame = readFrame(input)
                        interestedSeen = frame.size == 1 && (frame[0].toInt() and 0xff) == 2
                    }

                    output.write(frame(11, compactLiveStatus(PIECE_NUMBER, PIECE_NUMBER, PIECE_NUMBER)))
                    output.write(frame(1))
                    output.flush()

                    val requestedChunks = linkedSetOf<Int>()
                    var requestFrames = 0
                    while (requestedChunks.size < CHUNKS_PER_PIECE) {
                        val request = readFrame(input)
                        if (request.isEmpty() || (request[0].toInt() and 0xff) != 6) continue
                        requestFrames += 1
                        check(requestFrames <= MAX_CHUNK_REQUEST_FRAMES) {
                            "Too many chunk request frames before completing the piece: $requestFrames"
                        }
                        check(request.size == 11) { "Unexpected chunk request body size ${request.size}" }
                        val payload = ByteBuffer.wrap(request, 1, 10).order(ByteOrder.BIG_ENDIAN)
                        val streamIndex = payload.int.toLong() and 0xffff_ffffL
                        val piece = payload.int.toLong() and 0xffff_ffffL
                        val chunkIndex = payload.short.toInt() and 0xffff
                        check(streamIndex == 0L)
                        check(piece == PIECE_NUMBER)
                        check(chunkIndex in 0 until CHUNKS_PER_PIECE)

                        // The production scheduler is allowed to retry a still-missing chunk after
                        // its bounded retry interval. A deterministic peer fixture must answer that
                        // retry instead of treating it as a protocol violation. The unique-set
                        // condition below still requires the runtime to request every chunk.
                        requestedChunks.add(chunkIndex)
                        output.write(liveChunkFrame(chunkIndex))
                        output.flush()
                    }
                    allChunkResponses.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            } catch (error: Throwable) {
                failure.compareAndSet(null, error)
                allChunkResponses.countDown()
            }
        }

        fun awaitAllChunkResponses(): Boolean = allChunkResponses.await(5, TimeUnit.SECONDS)

        fun throwIfFailed() {
            failure.get()?.let { throw AssertionError("embedded runtime peer failed", it) }
        }

        override fun close() {
            release.countDown()
            runCatching { server.close() }
            worker.join(1_000)
        }
    }

    private companion object {
        const val PIECE_NUMBER = 10L
        const val PIECE_BYTES = 512 * 1024
        const val CHUNK_BYTES = 16 * 1024
        const val CHUNKS_PER_PIECE = PIECE_BYTES / CHUNK_BYTES
        const val MAX_CHUNK_REQUEST_FRAMES = CHUNKS_PER_PIECE * 3
        const val SIGNATURE_BYTES = 96
        const val TS_PACKET_BYTES = 188
        const val STARTUP_BYTES = 256 * 1024
        const val DHT_BATCH_SIZE = 4
        const val INFO_HASH = "1112131415161718191a1b1c1d1e1f2021222324"

        val SWARM_KEY_BYTES = byteArrayOf(
            0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a,
            0x1b, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x21, 0x22, 0x23, 0x24
        )
        val SWARM_KEY = AceLiveSwarmKey.fromBytes(SWARM_KEY_BYTES)
        val SERVER_PEER_ID = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
            (0x50 + index).toByte()
        }
        val PIECE_HEADER = AceLivePieceHeaderCodec.encodeUnixSeconds(1_700_000_000.25)
        val PIECE_DATA = ByteArray(PIECE_BYTES).also { bytes ->
            val authenticatedBytes = PIECE_BYTES - SIGNATURE_BYTES
            val fullPackets = authenticatedBytes / TS_PACKET_BYTES
            repeat(fullPackets) { packet ->
                val offset = packet * TS_PACKET_BYTES
                for (index in 0 until TS_PACKET_BYTES) {
                    bytes[offset + index] = ((packet * 17 + index * 3 + 7) and 0xff).toByte()
                }
                bytes[offset] = 0x47
                bytes[offset + 1] = 0x1f
                bytes[offset + 2] = (packet and 0xff).toByte()
                bytes[offset + 3] = 0x10
            }
            for (index in PIECE_BYTES - SIGNATURE_BYTES until PIECE_BYTES) {
                bytes[index] = (0x60 + (index and 0x1f)).toByte()
            }
        }

        fun frame(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
            val bodyLength = 1 + payload.size
            return ByteBuffer.allocate(4 + bodyLength)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(bodyLength)
                .put(id.toByte())
                .put(payload)
                .array()
        }

        fun liveChunkFrame(chunkIndex: Int): ByteArray {
            val start = chunkIndex * CHUNK_BYTES
            val chunkData = PIECE_DATA.copyOfRange(start, start + CHUNK_BYTES)
            val payload = ByteBuffer.allocate(4 + 4 + AceLivePieceHeaderCodec.HEADER_SIZE_BYTES + 2 + chunkData.size)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(0)
                .putInt(PIECE_NUMBER.toInt())
                .put(PIECE_HEADER)
                .putShort(chunkIndex.toShort())
                .put(chunkData)
                .array()
            return frame(7, payload)
        }

        fun compactLiveStatus(minPiece: Long, maxPiece: Long, position: Long): ByteArray =
            (
                "d1:ai1e1:bi0e1:ci1e1:di0e1:ei1e1:fi0e" +
                    "1:gi${position}e1:hi1e1:ii${minPiece}e1:ji${maxPiece}e" +
                    "1:ki0e1:li1e1:mi-1e1:ni2e1:oi0e1:pi0e1:qi1e" +
                    "1:ri${position}e1:si${position}e1:ti-1e1:ui1ee"
                ).toByteArray(Charsets.US_ASCII)

        fun readFrame(input: InputStream): ByteArray {
            val length = ByteBuffer.wrap(readExactly(input, 4))
                .order(ByteOrder.BIG_ENDIAN)
                .int
            check(length in 0..2 * 1024 * 1024) { "Invalid peer frame length $length" }
            return if (length == 0) byteArrayOf() else readExactly(input, length)
        }

        fun readExactly(input: InputStream, byteCount: Int): ByteArray {
            val output = ByteArray(byteCount)
            var offset = 0
            while (offset < byteCount) {
                val read = input.read(output, offset, byteCount - offset)
                check(read >= 0) { "Stream closed after $offset of $byteCount bytes" }
                offset += read
            }
            return output
        }
    }
}
