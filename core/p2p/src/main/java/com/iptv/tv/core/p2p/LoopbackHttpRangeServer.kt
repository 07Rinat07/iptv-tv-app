package com.iptv.tv.core.p2p

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal interface HttpRangeByteSource {
    val length: Long
    val contentType: String
        get() = "application/octet-stream"

    fun onRangeRequested(start: Long, endInclusive: Long) = Unit

    /** Reads bytes starting at [position]. Returns the number of bytes read, or -1 at EOF. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

internal class LoopbackHttpRangeServer(
    private val source: HttpRangeByteSource,
    private val requestedPort: Int = 0,
    private val streamPath: String = DEFAULT_STREAM_PATH
) : Closeable {
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var clients: ExecutorService? = null

    init {
        require(requestedPort in 0..65_535) { "requestedPort must be between 0 and 65535" }
        require(streamPath.startsWith('/')) { "streamPath must be absolute" }
        require('?' !in streamPath && '#' !in streamPath) { "streamPath must not contain query or fragment" }
        require(source.length >= 0L) { "source length must not be negative" }
    }

    val port: Int
        get() = synchronized(lock) { serverSocket?.localPort ?: 0 }

    val url: String
        get() {
            val activePort = port
            check(activePort > 0) { "HTTP range server is not started" }
            return "http://$LOOPBACK_HOST:$activePort$streamPath"
        }

    fun start(): String = synchronized(lock) {
        serverSocket?.let { return@synchronized url }

        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), requestedPort), ACCEPT_BACKLOG)
        }
        val executor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "p2p-http-client").apply { isDaemon = true }
        }

        serverSocket = socket
        clients = executor
        acceptThread = Thread({ acceptLoop(socket, executor) }, "p2p-http-accept").apply {
            isDaemon = true
            start()
        }
        url
    }

    override fun close() {
        val socket: ServerSocket?
        val executor: ExecutorService?
        synchronized(lock) {
            socket = serverSocket
            executor = clients
            serverSocket = null
            clients = null
            acceptThread = null
        }
        runCatching { socket?.close() }
        executor?.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                executor.execute { client.use(::handleClient) }
            } catch (_: IOException) {
                if (!socket.isClosed) continue
            } catch (_: RuntimeException) {
                if (socket.isClosed) return
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = CLIENT_TIMEOUT_MILLIS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val requestLine = input.readHttpLine() ?: return
        val requestParts = requestLine.split(' ')
        if (requestParts.size != 3) {
            writeSimpleResponse(output, 400, "Bad Request")
            return
        }

        val method = requestParts[0].uppercase(Locale.US)
        val targetPath = requestParts[1].substringBefore('?')
        val headers = readHeaders(input) ?: run {
            writeSimpleResponse(output, 400, "Bad Request")
            return
        }

        if (targetPath != streamPath) {
            writeSimpleResponse(output, 404, "Not Found")
            return
        }
        if (method != "GET" && method != "HEAD") {
            writeSimpleResponse(output, 405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD"))
            return
        }

        when (val range = HttpByteRange.resolve(headers["range"], source.length)) {
            is HttpRangeResolution.Full -> respondFull(output, method == "HEAD", range.contentLength)
            is HttpRangeResolution.Partial -> respondPartial(output, method == "HEAD", range)
            is HttpRangeResolution.Unsatisfiable -> respondUnsatisfiable(output, range.contentLength)
        }
    }

    private fun respondFull(output: OutputStream, headOnly: Boolean, contentLength: Long) {
        writeHeaders(
            output = output,
            status = 200,
            reason = "OK",
            headers = commonHeaders(contentLength)
        )
        if (!headOnly && contentLength > 0L) {
            source.onRangeRequested(0L, contentLength - 1L)
            streamBody(output, 0L, contentLength)
        }
    }

    private fun respondPartial(
        output: OutputStream,
        headOnly: Boolean,
        range: HttpRangeResolution.Partial
    ) {
        writeHeaders(
            output = output,
            status = 206,
            reason = "Partial Content",
            headers = commonHeaders(range.length) +
                ("Content-Range" to "bytes ${range.start}-${range.endInclusive}/${range.contentLength}")
        )
        if (!headOnly) {
            source.onRangeRequested(range.start, range.endInclusive)
            streamBody(output, range.start, range.length)
        }
    }

    private fun respondUnsatisfiable(output: OutputStream, contentLength: Long) {
        writeHeaders(
            output = output,
            status = 416,
            reason = "Range Not Satisfiable",
            headers = mapOf(
                "Accept-Ranges" to "bytes",
                "Content-Range" to "bytes */$contentLength",
                "Content-Length" to "0",
                "Connection" to "close"
            )
        )
    }

    private fun commonHeaders(contentLength: Long): Map<String, String> = linkedMapOf(
        "Accept-Ranges" to "bytes",
        "Content-Type" to source.contentType,
        "Content-Length" to contentLength.toString(),
        "Connection" to "close"
    )

    private fun streamBody(output: OutputStream, start: Long, length: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var position = start
        var remaining = length
        while (remaining > 0L) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val read = source.readAt(position, buffer, 0, requested)
            if (read <= 0) {
                throw IOException("Byte source ended before advertised Content-Length")
            }
            output.write(buffer, 0, read)
            position += read.toLong()
            remaining -= read.toLong()
        }
        output.flush()
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val result = linkedMapOf<String, String>()
        repeat(MAX_HEADER_LINES) {
            val line = input.readHttpLine() ?: return null
            if (line.isEmpty()) return result
            val separator = line.indexOf(':')
            if (separator <= 0) return null
            val name = line.substring(0, separator).trim().lowercase(Locale.US)
            val value = line.substring(separator + 1).trim()
            result[name] = value
        }
        return null
    }

    private fun writeSimpleResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        writeHeaders(
            output,
            status,
            reason,
            extraHeaders + mapOf("Content-Length" to "0", "Connection" to "close")
        )
    }

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>
    ) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) ->
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun InputStream.readHttpLine(): String? {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < MAX_LINE_LENGTH) {
            val value = read()
            if (value == -1) return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
            bytes.add(value.toByte())
        }
        throw IOException("HTTP header line is too long")
    }

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { index -> this[index] }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_STREAM_PATH = "/stream"
        const val ACCEPT_BACKLOG = 8
        const val CLIENT_TIMEOUT_MILLIS = 15_000
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_HEADER_LINES = 64
        const val MAX_LINE_LENGTH = 8 * 1024
    }
}
