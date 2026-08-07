package com.iptv.tv.core.p2p

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

/**
 * Embedded BitTorrent engine foundation.
 *
 * This class owns libtorrent's native session inside the IPTV process. It intentionally
 * does not depend on Ace Stream. Ace content ids are reported to the router as unsupported
 * until the compatibility resolver can translate them to transport metadata/infohash.
 */
class LibtorrentEmbeddedEngine(
    context: Context,
    private val httpClient: OkHttpClient = OkHttpClient()
) {
    private val appContext = context.applicationContext
    private val session = SessionManager(false)
    private val lifecycleMutex = Mutex()
    private val streamMutex = Mutex()
    private val metadataDir = File(appContext.cacheDir, "p2p/metadata")
    private val contentDir = File(appContext.cacheDir, "p2p/content")
    private var activeStream: P2pActiveStream<TorrentHandle>? = null

    suspend fun start(): P2pResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            lifecycleMutex.withLock {
                if (!session.isRunning) {
                    metadataDir.mkdirs()
                    contentDir.mkdirs()
                    session.start()
                }
            }
        }.fold(
            onSuccess = { P2pResult.Success(Unit) },
            onFailure = { P2pResult.Error("Unable to start embedded BitTorrent engine", it) }
        )
    }

    suspend fun stopStream(): P2pResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            streamMutex.withLock {
                closeActiveStreamLocked()
            }
        }.fold(
            onSuccess = { P2pResult.Success(Unit) },
            onFailure = { P2pResult.Error("Unable to stop embedded BitTorrent stream", it) }
        )
    }

    suspend fun stop(): P2pResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            var failure: Throwable? = null

            streamMutex.withLock {
                try {
                    closeActiveStreamLocked()
                } catch (error: Throwable) {
                    failure = error
                }
            }

            lifecycleMutex.withLock {
                try {
                    if (session.isRunning) session.stop()
                } catch (error: Throwable) {
                    if (failure == null) {
                        failure = error
                    } else {
                        failure?.addSuppressed(error)
                    }
                }
            }

            failure?.let { throw it }
        }.fold(
            onSuccess = { P2pResult.Success(Unit) },
            onFailure = { P2pResult.Error("Unable to stop embedded BitTorrent engine", it) }
        )
    }

    fun snapshot(): P2pEngineSnapshot = P2pEngineSnapshot(
        running = session.isRunning,
        downloadRateBytesPerSecond = if (session.isRunning) session.downloadRate() else 0L,
        uploadRateBytesPerSecond = if (session.isRunning) session.uploadRate() else 0L,
        dhtNodes = if (session.isRunning) session.dhtNodes() else 0L
    )

    suspend fun inspect(
        source: P2pSource,
        magnetTimeoutSeconds: Int = DEFAULT_MAGNET_TIMEOUT_SECONDS
    ): P2pResult<P2pTorrentMetadata> = withContext(Dispatchers.IO) {
        if (source is P2pSource.AceContentId) {
            return@withContext P2pResult.Error(
                "Ace content id requires compatibility metadata; embedded BitTorrent backend cannot resolve it yet"
            )
        }

        when (val started = start()) {
            is P2pResult.Error -> return@withContext started
            is P2pResult.Success -> Unit
        }

        runCatching {
            buildMetadata(resolveTorrentInfo(source, magnetTimeoutSeconds))
        }.fold(
            onSuccess = { P2pResult.Success(it) },
            onFailure = { P2pResult.Error(it.message ?: "Unable to inspect torrent metadata", it) }
        )
    }

    suspend fun prepareStream(
        source: P2pSource,
        fileIndex: Int? = null,
        magnetTimeoutSeconds: Int = DEFAULT_MAGNET_TIMEOUT_SECONDS
    ): P2pResult<P2pStreamDescriptor> = withContext(Dispatchers.IO) {
        if (source is P2pSource.AceContentId) {
            return@withContext P2pResult.Error(
                "Ace content id requires compatibility metadata; embedded BitTorrent backend cannot resolve it yet"
            )
        }

        when (val started = start()) {
            is P2pResult.Error -> return@withContext started
            is P2pResult.Success -> Unit
        }

        streamMutex.withLock {
            var pendingServer: LoopbackHttpRangeServer? = null
            var pendingHandle: TorrentHandle? = null

            runCatching {
                // A player switch owns exactly one embedded stream. Stop the previous torrent before
                // resolving/adding the next one so the old handle cannot keep using bandwidth.
                closeActiveStreamLocked()

                val torrentInfo = resolveTorrentInfo(source, magnetTimeoutSeconds)
                val metadata = buildMetadata(torrentInfo)
                val selectedFileIndex = fileIndex ?: metadata.preferredFileIndex
                    ?: error("Torrent does not contain a playable non-empty file")
                val selectedFile = metadata.files.firstOrNull { it.index == selectedFileIndex }
                    ?: error("Torrent file index $selectedFileIndex does not exist")
                if (selectedFile.sizeBytes <= 0L) error("Selected torrent file is empty")

                val storage = torrentInfo.files()
                val torrentDirectory = File(contentDir, torrentDirectoryName(torrentInfo)).apply { mkdirs() }

                session.download(torrentInfo, torrentDirectory)
                val handle = awaitTorrentHandle(torrentInfo)
                pendingHandle = handle
                configureFilePriorities(handle, storage.numFiles(), selectedFileIndex)
                handle.resume()

                val targetFile = File(storage.filePath(selectedFileIndex, torrentDirectory.absolutePath))
                val byteSource = LibtorrentStreamByteSource(
                    handle = handle,
                    torrentInfo = torrentInfo,
                    fileIndex = selectedFileIndex,
                    file = targetFile,
                    contentType = contentTypeFor(selectedFile.name)
                )
                byteSource.onRangeRequested(
                    start = 0L,
                    endInclusive = minOf(selectedFile.sizeBytes - 1L, STARTUP_PRIORITY_BYTES - 1L)
                )

                val server = LoopbackHttpRangeServer(byteSource)
                pendingServer = server
                val url = server.start()

                activeStream = P2pActiveStream(
                    server = server,
                    handle = handle,
                    removeHandle = ::removeTorrentHandle
                )
                pendingServer = null
                pendingHandle = null

                P2pStreamDescriptor(
                    url = url,
                    file = selectedFile,
                    torrent = metadata
                )
            }.fold(
                onSuccess = { P2pResult.Success(it) },
                onFailure = { error ->
                    var cleanupFailure: Throwable? = null
                    try {
                        pendingServer?.close()
                    } catch (cleanupError: Throwable) {
                        cleanupFailure = cleanupError
                    }
                    try {
                        pendingHandle?.let(::removeTorrentHandle)
                    } catch (cleanupError: Throwable) {
                        if (cleanupFailure == null) {
                            cleanupFailure = cleanupError
                        } else {
                            cleanupFailure?.addSuppressed(cleanupError)
                        }
                    }
                    cleanupFailure?.let(error::addSuppressed)
                    P2pResult.Error(error.message ?: "Unable to prepare embedded BitTorrent stream", error)
                }
            )
        }
    }

    private fun closeActiveStreamLocked() {
        val stream = activeStream ?: return
        activeStream = null
        stream.close()
    }

    private fun removeTorrentHandle(handle: TorrentHandle) {
        if (session.isRunning && handle.isValid) {
            session.remove(handle)
        }
    }

    private fun resolveTorrentInfo(
        source: P2pSource,
        magnetTimeoutSeconds: Int
    ): TorrentInfo {
        val torrentBytes = when (source) {
            is P2pSource.Magnet,
            is P2pSource.InfoHash -> {
                val magnet = P2pSourceParser.toMagnetUri(source)
                    ?: error("Unable to convert source to magnet URI")
                session.fetchMagnet(
                    magnet,
                    magnetTimeoutSeconds.coerceIn(5, 120),
                    metadataDir
                ) ?: error("Torrent metadata was not received before timeout")
            }

            is P2pSource.TorrentUrl -> downloadTorrentMetadata(source.url)
            is P2pSource.LocalTorrentUri -> readTorrentMetadata(source.uri)
            is P2pSource.AceContentId -> error("Ace content id is not BitTorrent metadata")
        }

        return TorrentInfo(torrentBytes).also {
            if (!it.isValid) error("Invalid torrent metadata")
        }
    }

    private fun awaitTorrentHandle(
        info: TorrentInfo,
        timeoutMillis: Long = TORRENT_HANDLE_TIMEOUT_MILLIS
    ): TorrentHandle {
        val deadlineNanos = System.nanoTime() + timeoutMillis * NANOS_PER_MILLI
        while (System.nanoTime() < deadlineNanos) {
            session.find(info.infoHash())?.takeIf { it.isValid }?.let { return it }
            try {
                Thread.sleep(TORRENT_HANDLE_POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                error("Interrupted while waiting for torrent handle")
            }
        }
        error("Timed out waiting for libtorrent to add the torrent")
    }

    private fun configureFilePriorities(
        handle: TorrentHandle,
        fileCount: Int,
        selectedFileIndex: Int
    ) {
        for (index in 0 until fileCount) {
            handle.filePriority(
                index,
                if (index == selectedFileIndex) Priority.DEFAULT else Priority.IGNORE
            )
        }
    }

    private fun torrentDirectoryName(info: TorrentInfo): String {
        val hash = info.infoHash().toHex().lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        if (hash.isNotBlank()) return hash
        return info.name()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(80)
            .ifBlank { "torrent" }
    }

    private fun downloadTorrentMetadata(url: String): ByteArray {
        val request = Request.Builder().url(url).get().build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Torrent metadata HTTP ${response.code}")
            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                ?: error("Torrent metadata response is empty")
        }
    }

    private fun readTorrentMetadata(rawUri: String): ByteArray {
        val uri = Uri.parse(rawUri)
        return when (uri.scheme?.lowercase()) {
            "file" -> {
                val path = uri.path ?: error("Local torrent file path is empty")
                File(path).readBytes()
            }

            "content" -> {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Unable to open local torrent content URI")
            }

            else -> error("Unsupported local torrent URI")
        }.also {
            if (it.isEmpty()) error("Local torrent metadata is empty")
        }
    }

    private fun buildMetadata(info: TorrentInfo): P2pTorrentMetadata {
        if (!info.isValid) error("Invalid torrent metadata")

        val storage = info.files()
        val files = (0 until storage.numFiles()).map { index ->
            val name = storage.fileName(index)
            P2pTorrentFile(
                index = index,
                name = name,
                path = storage.filePath(index),
                sizeBytes = storage.fileSize(index),
                mediaCandidate = isMediaFile(name)
            )
        }

        val preferred = files
            .filter { it.mediaCandidate && it.sizeBytes > 0L }
            .maxByOrNull { it.sizeBytes }
            ?: files.filter { it.sizeBytes > 0L }.maxByOrNull { it.sizeBytes }

        return P2pTorrentMetadata(
            name = info.name(),
            infoHashV1 = info.infoHash().toHex().takeIf { it.isNotBlank() },
            totalSizeBytes = info.totalSize(),
            pieceLengthBytes = info.pieceLength(),
            files = files,
            preferredFileIndex = preferred?.index
        )
    }

    private fun contentTypeFor(name: String): String = when (
        name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    ) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts", "m2ts", "mts" -> "video/mp2t"
        "mpeg", "mpg" -> "video/mpeg"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "flv" -> "video/x-flv"
        "wmv" -> "video/x-ms-wmv"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        "m4a" -> "audio/mp4"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun isMediaFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in MEDIA_EXTENSIONS
    }

    private companion object {
        const val DEFAULT_MAGNET_TIMEOUT_SECONDS = 30
        const val TORRENT_HANDLE_TIMEOUT_MILLIS = 5_000L
        const val TORRENT_HANDLE_POLL_MILLIS = 50L
        const val NANOS_PER_MILLI = 1_000_000L
        const val STARTUP_PRIORITY_BYTES = 2L * 1024L * 1024L

        val MEDIA_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mts",
            "mpg", "mpeg", "vob", "flv", "wmv", "mp3", "aac", "m4a", "flac", "ogg"
        )
    }
}
