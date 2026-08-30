package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveLoopbackHttpDeliveryQualificationTest {
    @Test
    fun `loopback GET delivers retained live bytes and confirms consumer progress`() {
        val payload = ByteArray(188 * 5) { index ->
            if (index % 188 == 0) 0x47 else (index and 0x7f).toByte()
        }
        val mediaBuffer = AceLiveMediaBuffer(maxBufferedBytes = 188 * 64)
        assertEquals(payload.size, mediaBuffer.append(payload))

        val deliveredSnapshot = AtomicReference<AceLiveMediaConsumerSnapshot?>()
        val firstRead = AtomicReference<Pair<Long, Int>?>()
        val delivered = CountDownLatch(1)
        val firstReadObserved = CountDownLatch(1)
        val server = LoopbackHttpLiveServer(
            mediaBuffer = mediaBuffer,
            consumerObserver = { snapshot ->
                deliveredSnapshot.set(snapshot)
                if (snapshot.totalDeliveredBytes >= payload.size.toLong()) delivered.countDown()
            },
            firstReadObserver = { readerId, byteCount ->
                firstRead.set(readerId to byteCount)
                firstReadObserved.countDown()
            }
        )

        try {
            val uri = URI(server.url)
            Socket(uri.host, uri.port).use { socket ->
                socket.soTimeout = 2_000
                val request = buildString {
                    append("GET ").append(uri.rawPath).append(" HTTP/1.1\r\n")
                    append("Host: ").append(uri.host).append(':').append(uri.port).append("\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().apply {
                    write(request.toByteArray(StandardCharsets.ISO_8859_1))
                    flush()
                }

                val input = socket.getInputStream()
                val headers = readHttpHeaders(input)
                assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"))
                assertTrue(headers.contains("Content-Type: video/mp2t\r\n"))
                assertTrue(headers.contains("Cache-Control: no-store\r\n"))
                assertTrue(headers.contains("Connection: close\r\n"))

                val received = readExactly(input, payload.size)
                assertArrayEquals(payload, received)
            }

            assertTrue(
                "loopback server must report the first socket read",
                firstReadObserved.await(2, TimeUnit.SECONDS)
            )
            assertTrue(
                "loopback server must confirm bytes only after socket write and flush",
                delivered.await(2, TimeUnit.SECONDS)
            )

            val first = requireNotNull(firstRead.get())
            val snapshot = requireNotNull(deliveredSnapshot.get())
            assertEquals(snapshot.readerId, first.first)
            assertEquals(payload.size, first.second)
            assertEquals(payload.size.toLong(), snapshot.consumerOffset)
            assertEquals(payload.size.toLong(), snapshot.liveEdgeOffset)
            assertEquals(0L, snapshot.playableBytes)
            assertEquals(payload.size.toLong(), snapshot.totalDeliveredBytes)
            assertEquals(false, snapshot.fellBehind)
        } finally {
            mediaBuffer.close()
            server.close()
        }
    }

    private fun readHttpHeaders(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (output.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            require(value >= 0) { "Loopback HTTP response ended before headers completed" }
            val byte = value.toByte()
            output.write(value)
            matched = if (byte == terminator[matched]) {
                matched + 1
            } else if (byte == terminator[0]) {
                1
            } else {
                0
            }
            if (matched == terminator.size) {
                return output.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
        }
        error("Loopback HTTP response headers exceeded $MAX_HEADER_BYTES bytes")
    }

    private fun readExactly(input: InputStream, byteCount: Int): ByteArray {
        val result = ByteArray(byteCount)
        var offset = 0
        while (offset < result.size) {
            val count = input.read(result, offset, result.size - offset)
            require(count > 0) { "Loopback HTTP response ended after $offset of $byteCount media bytes" }
            offset += count
        }
        return result
    }

    private companion object {
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}
