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
        val hlsPlan = DownloadHlsSegmentPlanner.plan(source) { url ->
            fetchText(url, MAX_PLAYLIST_BYTES)
        }

        val target = targetFile(downloadId, source, "ts")
        var totalBytes = 0L
        target.outputStream().use { output ->
            hlsPlan.segmentUrls.forEach { segmentUrl ->
                val connection = URL(segmentUrl).openConnection() as HttpURLConnection
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
        const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
        const val MAX_FILE_NAME_CHARS = 80
    }
}

internal data class DownloadHlsSegmentPlan(
    val mediaPlaylistUrl: String,
    val segmentUrls: List<String>,
    val discontinuityCount: Int
)

internal object DownloadHlsSegmentPlanner {
    fun plan(
        source: String,
        fetchText: (String) -> String
    ): DownloadHlsSegmentPlan {
        var currentUrl = source
        repeat(MAX_MASTER_REDIRECTS) {
            when (val manifest = HlsPlaylistParser.parse(currentUrl, fetchText(currentUrl))) {
                is HlsPlaylistParser.Manifest.Master -> {
                    currentUrl = HlsPlaylistParser.selectPreferredVariant(manifest)
                        ?: error("Empty HLS master playlist")
                }

                is HlsPlaylistParser.Manifest.Media -> {
                    if (manifest.encrypted) {
                        error(manifest.unsupportedEncryptionMessage())
                    }
                    val segmentUrls = manifest.segments.take(MAX_HLS_SEGMENTS)
                    if (segmentUrls.isEmpty()) {
                        error("HLS playlist has no media segments")
                    }
                    return DownloadHlsSegmentPlan(
                        mediaPlaylistUrl = currentUrl,
                        segmentUrls = segmentUrls,
                        discontinuityCount = manifest.discontinuityCount
                    )
                }
            }
        }
        error("Too many nested HLS master playlists")
    }

    private const val MAX_HLS_SEGMENTS = 256
    private const val MAX_MASTER_REDIRECTS = 3
}

private fun HlsPlaylistParser.Manifest.Media.unsupportedEncryptionMessage(): String {
    val methods = encryptionMethods.sorted().joinToString(separator = ", ").ifBlank { "UNKNOWN" }
    return "Encrypted HLS playlists are not supported yet: $methods"
}

private fun String.sanitizeDownloadFileName(): String {
    return replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), "_")
        .trim('_', '.')
}
