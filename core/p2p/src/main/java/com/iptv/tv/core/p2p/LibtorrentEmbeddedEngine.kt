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
import org.libtorrent4j.SessionManager
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
    private val metadataDir = File(appContext.cacheDir, "p2p/metadata")

    suspend fun start(): P2pResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            lifecycleMutex.withLock {
                if (!session.isRunning) {
                    metadataDir.mkdirs()
                    session.start()
                }
            }
        }.fold(
            onSuccess = { P2pResult.Success(Unit) },
            onFailure = { P2pResult.Error("Unable to start embedded BitTorrent engine", it) }
        )
    }

    suspend fun stop(): P2pResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            lifecycleMutex.withLock {
                if (session.isRunning) session.stop()
            }
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

            buildMetadata(TorrentInfo(torrentBytes))
        }.fold(
            onSuccess = { P2pResult.Success(it) },
            onFailure = { P2pResult.Error(it.message ?: "Unable to inspect torrent metadata", it) }
        )
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

    private fun isMediaFile(name: String): Boolean {
        val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return extension in MEDIA_EXTENSIONS
    }

    private companion object {
        const val DEFAULT_MAGNET_TIMEOUT_SECONDS = 30

        val MEDIA_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mts",
            "mpg", "mpeg", "vob", "flv", "wmv", "mp3", "aac", "m4a", "flac", "ogg"
        )
    }
}
