package com.iptv.tv.core.p2p

import java.net.Socket
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopbackHttpRangeServerTest {
    @Test
    fun servesFullGetOnlyOnLoopback() {
        val source = RecordingByteSource("0123456789".toByteArray())
        LoopbackHttpRangeServer(source).use { server ->
            server.start()

            val response = request(server.port, "GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")

            assertTrue(response.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(response.contains("Accept-Ranges: bytes\r\n"))
            assertTrue(response.contains("Content-Length: 10\r\n"))
            assertTrue(response.endsWith("\r\n\r\n0123456789"))
            assertEquals(listOf(0L..9L), source.requestedRanges)
            assertTrue(server.url.startsWith("http://127.0.0.1:"))
        }
    }

    @Test
    fun servesClosedRangeWith206() {
        val source = RecordingByteSource("0123456789".toByteArray())
        LoopbackHttpRangeServer(source).use { server ->
            server.start()

            val response = request(
                server.port,
                "GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=3-6\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 206 Partial Content\r\n"))
            assertTrue(response.contains("Content-Range: bytes 3-6/10\r\n"))
            assertTrue(response.contains("Content-Length: 4\r\n"))
            assertTrue(response.endsWith("\r\n\r\n3456"))
            assertEquals(listOf(3L..6L), source.requestedRanges)
        }
    }

    @Test
    fun headReturnsHeadersWithoutReadingBody() {
        val source = RecordingByteSource("0123456789".toByteArray())
        LoopbackHttpRangeServer(source).use { server ->
            server.start()

            val response = request(
                server.port,
                "HEAD /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=5-\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 206 Partial Content\r\n"))
            assertTrue(response.contains("Content-Range: bytes 5-9/10\r\n"))
            assertTrue(response.endsWith("\r\n\r\n"))
            assertTrue(source.requestedRanges.isEmpty())
            assertEquals(0, source.readCalls)
        }
    }

    @Test
    fun rejectsUnsatisfiableRangeWith416() {
        val source = RecordingByteSource("0123456789".toByteArray())
        LoopbackHttpRangeServer(source).use { server ->
            server.start()

            val response = request(
                server.port,
                "GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\nRange: bytes=10-\r\n\r\n"
            )

            assertTrue(response.startsWith("HTTP/1.1 416 Range Not Satisfiable\r\n"))
            assertTrue(response.contains("Content-Range: bytes */10\r\n"))
            assertFalse(response.contains("0123456789"))
            assertTrue(source.requestedRanges.isEmpty())
        }
    }

    @Test
    fun rejectsWrongPathAndUnsupportedMethod() {
        val source = RecordingByteSource("abc".toByteArray())
        LoopbackHttpRangeServer(source).use { server ->
            server.start()

            assertTrue(
                request(server.port, "GET /other HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                    .startsWith("HTTP/1.1 404 Not Found\r\n")
            )
            assertTrue(
                request(server.port, "POST /stream HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                    .startsWith("HTTP/1.1 405 Method Not Allowed\r\n")
            )
        }
    }

    private fun request(port: Int, request: String): String {
        return Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request.toByteArray(StandardCharsets.ISO_8859_1))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(StandardCharsets.ISO_8859_1)
        }
    }

    private class RecordingByteSource(private val bytes: ByteArray) : HttpRangeByteSource {
        override val length: Long = bytes.size.toLong()
        val requestedRanges = mutableListOf<LongRange>()
        var readCalls: Int = 0

        override fun onRangeRequested(start: Long, endInclusive: Long) {
            requestedRanges += start..endInclusive
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            readCalls += 1
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position.toInt())
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }
    }
}
