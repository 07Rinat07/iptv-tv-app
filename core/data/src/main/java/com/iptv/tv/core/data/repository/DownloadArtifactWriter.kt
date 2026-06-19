package com.iptv.tv.core.data.repository

import android.content.Context
import android.os.Environment
import com.iptv.tv.core.model.DownloadSourceType
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

internal sealed interface DownloadArtifactResult {
    data class Completed(val filePath: String, val bytesWritten: Long) : DownloadArtifactResult
    data class Failed(val reason: String) : DownloadArtifactResult
    data object Unsupported : DownloadArtifactResult
}

internal interface DownloadArtifactWriter {
    fun write(downloadId: Long, source: String, sourceType: DownloadSourceType): DownloadArtifactResult
}

internal class AppDownloadArtifactWriter(
    private val context: Context
) : DownloadArtifactWriter {
    override fun write(downloadId: Long, source: String, sourceType: DownloadSourceType): DownloadArtifactResult {
        return when (sourceType) {
            DownloadSourceType.HTTP_STREAM -> writeHttp(downloadId, source)
            DownloadSourceType.HLS_PLAYLIST -> writeHls(downloadId, source)
            DownloadSourceType.LOCAL_FILE -> copyLocal(downloadId, source)
            DownloadSourceType.MAGNET,
            DownloadSourceType.ACESTREAM,
            DownloadSourceType.TORRENT_FILE,
            DownloadSourceType.CUSTOM -> DownloadArtifactResult.Unsupported
        }
    }

    private fun writeHttp(downloadId: Long, source: String): DownloadArtifactResult = runCatching {
        val url = URL(source)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"

        connection.inputStream.use { input ->
            val extension = extensionFromUrl(source)
            val target = targetFile(downloadId, source, extension)
            val bytes = target.outputStream().use { output -> input.copyTo(output) }
            DownloadArtifactResult.Completed(target.absolutePath, bytes)
        }
    }.getOrElse { throwable ->
        DownloadArtifactResult.Failed(throwable.message ?: throwable.javaClass.simpleName)
    }

    private fun writeHls(downloadId: Long, source: String): DownloadArtifactResult = runCatching {
        val playlistText = fetchText(source, MAX_PLAYLIST_BYTES)
        if (playlistText.lineSequence().any { it.trim().startsWith("#EXT-X-KEY") && !it.contains("METHOD=NONE") }) {
            return@runCatching DownloadArtifactResult.Failed("Encrypted HLS playlists are not supported yet")
        }

        val segmentUrls = playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .take(MAX_HLS_SEGMENTS)
            .map { segment -> URL(URL(source), segment) }
            .toList()

        if (segmentUrls.isEmpty()) {
            return@runCatching DownloadArtifactResult.Failed("HLS playlist has no media segments")
        }

        val target = targetFile(downloadId, source, "ts")
        var totalBytes = 0L
        target.outputStream().use { output ->
            segmentUrls.forEach { segmentUrl ->
                val connection = segmentUrl.openConnection() as HttpURLConnection
                connection.connectTimeout = NETWORK_TIMEOUT_MS
                connection.readTimeout = NETWORK_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.inputStream.use { input ->
                    totalBytes += input.copyTo(output)
                }
            }
        }
        DownloadArtifactResult.Completed(target.absolutePath, totalBytes)
    }.getOrElse { throwable ->
        DownloadArtifactResult.Failed(throwable.message ?: throwable.javaClass.simpleName)
    }

    private fun copyLocal(downloadId: Long, source: String): DownloadArtifactResult = runCatching {
        val input = if (source.startsWith("file://", ignoreCase = true)) {
            File(URI(source))
        } else {
            File(source)
        }
        if (!input.exists() || !input.isFile) {
            return@runCatching DownloadArtifactResult.Failed("Local source file is unavailable")
        }
        val target = targetFile(downloadId, input.name, input.extension.ifBlank { "bin" })
        input.copyTo(target, overwrite = true)
        DownloadArtifactResult.Completed(target.absolutePath, target.length())
    }.getOrElse { throwable ->
        DownloadArtifactResult.Failed(throwable.message ?: throwable.javaClass.simpleName)
    }

    private fun fetchText(source: String, maxBytes: Int): String {
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        return connection.inputStream.use { input ->
            val bytes = input.readBytes()
            if (bytes.size > maxBytes) {
                error("HLS playlist is too large: ${bytes.size} bytes")
            }
            bytes.toString(Charsets.UTF_8)
        }
    }

    private fun targetFile(downloadId: Long, source: String, extension: String): File {
        val directory = downloadDirectory()
        if (!directory.exists() && !directory.mkdirs()) {
            error("Cannot create download directory: ${directory.absolutePath}")
        }
        val safeName = source.substringAfterLast('/').substringBefore('?')
            .substringBefore('#')
            .ifBlank { "download-$downloadId" }
            .sanitizeDownloadFileName()
            .take(MAX_FILE_NAME_CHARS)
            .ifBlank { "download-$downloadId" }
        val normalizedExtension = extension.trim('.').ifBlank { "bin" }
        val base = safeName.substringBeforeLast('.', safeName)
        return File(directory, "${downloadId}_${base}.${normalizedExtension}")
    }

    private fun downloadDirectory(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "downloads")
    }

    private fun extensionFromUrl(source: String): String {
        val path = runCatching { URI(source).path.orEmpty() }.getOrDefault("")
        return path.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.US)
            .takeIf { it.length in 1..6 && it.all { ch -> ch.isLetterOrDigit() } }
            ?: "bin"
    }

    private companion object {
        const val NETWORK_TIMEOUT_MS = 30_000
        const val MAX_HLS_SEGMENTS = 256
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        const val MAX_FILE_NAME_CHARS = 80
    }
}

private fun String.sanitizeDownloadFileName(): String {
    return replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .trim('_', '.')
}
