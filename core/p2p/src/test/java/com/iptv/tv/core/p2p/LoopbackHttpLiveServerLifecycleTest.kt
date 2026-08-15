package com.iptv.tv.core.p2p

import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackHttpLiveServerLifecycleTest {
    @Test
    fun getReaderEmitsOpenedDeliveredAndClosedForSameReader() {
        val mediaBuffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        mediaBuffer.append(ByteArray(188 * 8) { index -> index.toByte() })
        val events = Collections.synchronizedList(
            mutableListOf<AceLiveConsumerLifecycleEvent>()
        )
        val firstReads = Collections.synchronizedList(
            mutableListOf<Pair<Long, Int>>()
        )
        val server = LoopbackHttpLiveServer(
            mediaBuffer = mediaBuffer,
            consumerLifecycleObserver = { event -> events += event },
            firstReadObserver = { readerId, byteCount -> firstReads += readerId to byteCount }
        )
        val uri = URI(server.url)
        val socket = Socket(uri.host, uri.port)

        try {
            socket.soTimeout = 2_000
            val request = buildString {
                append("GET ").append(uri.path).append(" HTTP/1.1\r\n")
                append("Host: ").append(uri.host).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }

            readHeaders(socket)
            val payload = ByteArray(188)
            assertTrue(socket.getInputStream().read(payload) > 0)
            assertTrue(awaitEvent(events) { it is AceLiveConsumerLifecycleEvent.Delivered })
        } finally {
            socket.close()
            server.close()
            mediaBuffer.close()
        }

        assertTrue(awaitEvent(events) { it is AceLiveConsumerLifecycleEvent.Closed })
        val snapshot = synchronized(events) { events.toList() }
        val opened = snapshot.filterIsInstance<AceLiveConsumerLifecycleEvent.Opened>().single()
        val delivered = snapshot.filterIsInstance<AceLiveConsumerLifecycleEvent.Delivered>().first()
        val closed = snapshot.filterIsInstance<AceLiveConsumerLifecycleEvent.Closed>().single()

        val firstReadSnapshot = synchronized(firstReads) { firstReads.toList() }
        assertEquals(1, firstReadSnapshot.size)
        assertEquals(opened.readerId, firstReadSnapshot.single().first)
        assertTrue(firstReadSnapshot.single().second > 0)
        assertEquals(opened.readerId, delivered.readerId)
        assertEquals(opened.readerId, closed.readerId)
        assertTrue(snapshot.indexOf(opened) < snapshot.indexOf(delivered))
        assertTrue(snapshot.indexOf(delivered) < snapshot.indexOf(closed))
        assertTrue(delivered.consumer.totalDeliveredBytes > 0L)
    }

    @Test
    fun headRequestDoesNotCreateMediaConsumerLifecycle() {
        val mediaBuffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        val events = Collections.synchronizedList(
            mutableListOf<AceLiveConsumerLifecycleEvent>()
        )
        val firstReads = Collections.synchronizedList(
            mutableListOf<Pair<Long, Int>>()
        )
        val server = LoopbackHttpLiveServer(
            mediaBuffer = mediaBuffer,
            consumerLifecycleObserver = { event -> events += event },
            firstReadObserver = { readerId, byteCount -> firstReads += readerId to byteCount }
        )
        val uri = URI(server.url)

        try {
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 2_000
                val request = buildString {
                    append("HEAD ").append(uri.path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(uri.host).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                socket.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.ISO_8859_1))
                    flush()
                }
                readHeaders(socket)
            }
        } finally {
            server.close()
            mediaBuffer.close()
        }

        assertTrue(events.isEmpty())
        assertTrue(firstReads.isEmpty())
    }

    private fun readHeaders(socket: Socket) {
        val input = socket.getInputStream()
        var matched = 0
        repeat(MAX_HEADER_BYTES) {
            val value = input.read()
            check(value >= 0) { "loopback response ended before headers completed" }
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return
        }
        error("loopback response headers exceeded test bound")
    }

    private fun awaitEvent(
        events: List<AceLiveConsumerLifecycleEvent>,
        predicate: (AceLiveConsumerLifecycleEvent) -> Boolean
    ): Boolean {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < deadline) {
            if (synchronized(events) { events.any(predicate) }) return true
            Thread.sleep(10L)
        }
        return synchronized(events) { events.any(predicate) }
    }

    private companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}
