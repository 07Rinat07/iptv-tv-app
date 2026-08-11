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
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class AceLiveMediaAuthenticator(
    publicKeyDer: ByteArray?,
    fallbackSignatureBytes: Int = DEFAULT_SIGNATURE_BYTES
) {
    private val publicKey = publicKeyDer?.let { encoded ->
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(encoded)) as? RSAPublicKey
            ?: error("Ace live public key is not RSA")
    }
    private val signatureBytes = publicKey
        ?.let { key -> (key.modulus.bitLength() + 7) / 8 }
        ?: fallbackSignatureBytes

    init {
        require(signatureBytes in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) {
            "Ace live RSA signature size is unsupported"
        }
    }

    fun verifyAndStrip(piece: ByteArray): P2pResult<ByteArray> = runCatching {
        require(piece.size > signatureBytes) { "Ace live piece is shorter than its signature" }
        val payloadSize = piece.size - signatureBytes
        publicKey?.let { key ->
            val verifier = Signature.getInstance("SHA1withRSA")
            verifier.initVerify(key)
            verifier.update(piece, 0, payloadSize)
            require(verifier.verify(piece, payloadSize, signatureBytes)) {
                "Ace live piece signature verification failed"
            }
        }
        piece.copyOf(payloadSize)
    }.fold(
        onSuccess = { P2pResult.Success(it) },
        onFailure = { error -> P2pResult.Error(error.message ?: "Ace live piece is invalid", error) }
    )

    private companion object {
        const val MIN_SIGNATURE_BYTES = 64
        const val MAX_SIGNATURE_BYTES = 512
        const val DEFAULT_SIGNATURE_BYTES = 96
    }
}

/** Keeps a bounded recent live window and gives each loopback client an independent cursor. */
internal class AceLiveMediaBuffer(
    private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES
) : Closeable {
    private val lock = Object()
    private val segments = ArrayDeque<Segment>()
    private var firstOffset = 0L
    private var nextOffset = 0L
    private var closed = false
    private var failure: IOException? = null

    init {
        require(maxBufferedBytes >= MIN_BUFFERED_BYTES) { "Ace live media buffer is too small" }
    }

    fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            val retained = if (bytes.size > maxBufferedBytes) {
                bytes.copyOfRange(bytes.size - maxBufferedBytes, bytes.size)
            } else {
                bytes.copyOf()
            }
            segments += Segment(nextOffset, retained)
            nextOffset += retained.size.toLong()
            trimLocked()
            lock.notifyAll()
        }
    }

    fun fail(error: Throwable) {
        synchronized(lock) {
            if (closed) return
            failure = IOException(error.message ?: "Ace live media stream failed", error)
            closed = true
            lock.notifyAll()
        }
    }

    fun openReader(): Reader = synchronized(lock) {
        Reader(cursor = firstOffset)
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    private fun trimLocked() {
        while (nextOffset - firstOffset > maxBufferedBytes && segments.isNotEmpty()) {
            val first = segments.removeFirst()
            firstOffset = first.endOffset
        }
        if (segments.isEmpty()) firstOffset = nextOffset
    }

    inner class Reader internal constructor(private var cursor: Long) {
        fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
                "read bounds are invalid"
            }
            if (length == 0) return 0

            synchronized(lock) {
                while (true) {
                    if (cursor < firstOffset) cursor = firstOffset
                    val segment = segments.firstOrNull { item -> cursor in item.startOffset until item.endOffset }
                    if (segment != null) {
                        val sourceOffset = (cursor - segment.startOffset).toInt()
                        val copied = minOf(length, segment.bytes.size - sourceOffset)
                        segment.bytes.copyInto(buffer, offset, sourceOffset, sourceOffset + copied)
                        cursor += copied.toLong()
                        return copied
                    }
                    failure?.let { throw it }
                    if (closed) return -1
                    lock.wait()
                }
            }
        }
    }

    private data class Segment(val startOffset: Long, val bytes: ByteArray) {
        val endOffset: Long = startOffset + bytes.size
    }

    companion object {
        const val DEFAULT_MAX_BUFFERED_BYTES = 4 * 1024 * 1024
        private const val MIN_BUFFERED_BYTES = 188 * 32
    }
}

internal class AceLiveMpegTsResynchronizer {
    private var synchronized = false
    private var pending = byteArrayOf()

    fun consume(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return byteArrayOf()
        if (synchronized) return bytes.copyOf()

        val candidate = pending + bytes
        val offset = findSyncOffset(candidate)
        if (offset >= 0) {
            synchronized = true
            pending = byteArrayOf()
            return candidate.copyOfRange(offset, candidate.size)
        }

        pending = if (candidate.size > MAX_PENDING_BYTES) {
            candidate.copyOfRange(candidate.size - MAX_PENDING_BYTES, candidate.size)
        } else {
            candidate
        }
        return byteArrayOf()
    }

    fun reset() {
        synchronized = false
        pending = byteArrayOf()
    }

    private fun findSyncOffset(bytes: ByteArray): Int {
        val lastStart = bytes.size - TS_PACKET_BYTES * REQUIRED_SYNC_PACKETS
        for (start in 0..lastStart.coerceAtLeast(-1)) {
            var matches = true
            for (packet in 0 until REQUIRED_SYNC_PACKETS) {
                if (bytes[start + packet * TS_PACKET_BYTES] != TS_SYNC_BYTE) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private companion object {
        const val TS_PACKET_BYTES = 188
        const val REQUIRED_SYNC_PACKETS = 5
        const val MAX_PENDING_BYTES = TS_PACKET_BYTES * REQUIRED_SYNC_PACKETS * 2
        val TS_SYNC_BYTE: Byte = 0x47
    }
}

internal class LoopbackHttpLiveServer(
    private val mediaBuffer: AceLiveMediaBuffer,
    private val requestedPort: Int = 0
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(LOOPBACK_HOST), requestedPort), ACCEPT_BACKLOG)
    }
    private val clients: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ace-live-http-client").apply { isDaemon = true }
    }
    private val acceptThread = Thread(::acceptLoop, "ace-live-http-accept").apply {
        isDaemon = true
        start()
    }

    val url: String = "http://$LOOPBACK_HOST:${serverSocket.localPort}$STREAM_PATH"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { serverSocket.close() }
        clients.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                val socket = serverSocket.accept()
                clients.execute {
                    socket.use { client ->
                        try {
                            handleClient(client)
                        } catch (_: IOException) {
                            // Media clients routinely close a live response without draining it.
                        }
                    }
                }
            } catch (_: IOException) {
                if (closed.get()) return
            } catch (_: RuntimeException) {
                if (closed.get()) return
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = REQUEST_TIMEOUT_MILLIS
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val requestLine = input.readHttpLine() ?: return
        val parts = requestLine.split(' ')
        if (parts.size != 3) return writeStatus(output, 400, "Bad Request")
        val method = parts[0].uppercase(Locale.US)
        val path = parts[1].substringBefore('?')
        if (!consumeHeaders(input)) return writeStatus(output, 400, "Bad Request")
        if (path != STREAM_PATH) return writeStatus(output, 404, "Not Found")
        if (method != "GET" && method != "HEAD") {
            return writeStatus(output, 405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD"))
        }

        writeHeaders(
            output,
            200,
            "OK",
            linkedMapOf(
                "Content-Type" to "video/mp2t",
                "Cache-Control" to "no-store",
                "Connection" to "close"
            )
        )
        if (method == "HEAD") return

        socket.soTimeout = 0
        val reader = mediaBuffer.openReader()
        val bytes = ByteArray(64 * 1024)
        while (!closed.get()) {
            val count = reader.read(bytes, 0, bytes.size)
            if (count < 0) return
            output.write(bytes, 0, count)
            output.flush()
        }
    }

    private fun consumeHeaders(input: InputStream): Boolean {
        repeat(MAX_HEADER_LINES) {
            val line = input.readHttpLine() ?: return false
            if (line.isEmpty()) return true
            if (line.indexOf(':') <= 0) return false
        }
        return false
    }

    private fun writeStatus(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String> = emptyMap()
    ) = writeHeaders(
        output,
        status,
        reason,
        headers + mapOf("Content-Length" to "0", "Connection" to "close")
    )

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        reason: String,
        headers: Map<String, String>
    ) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun InputStream.readHttpLine(): String? {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < MAX_LINE_LENGTH) {
            val value = read()
            if (value < 0) {
                return if (bytes.isEmpty()) null else bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
            if (value == '\n'.code) {
                if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.removeAt(bytes.lastIndex)
                return bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
            }
            bytes += value.toByte()
        }
        throw IOException("HTTP header line is too long")
    }

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { index -> this[index] }

    private companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val STREAM_PATH = "/live.ts"
        const val ACCEPT_BACKLOG = 4
        const val REQUEST_TIMEOUT_MILLIS = 10_000
        const val MAX_HEADER_LINES = 64
        const val MAX_LINE_LENGTH = 8 * 1024
    }
}
