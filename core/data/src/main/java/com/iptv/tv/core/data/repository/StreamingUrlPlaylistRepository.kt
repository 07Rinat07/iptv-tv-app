package com.iptv.tv.core.data.repository

import android.content.Context
import android.net.Uri
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.data.mapper.toEntity
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import com.iptv.tv.core.parser.ParseResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Outermost playlist decorator for streaming M3U import paths.
 *
 * Direct URL, Tvheadend and local file/content URI imports are handled here so their source stream
 * can stay open while [M3uParser] consumes its Reader instead of first materializing the complete
 * playlist as a String. JSON-based provider protocols continue through the existing repository chain.
 */
@Singleton
class StreamingUrlPlaylistRepository @Inject constructor(
    private val delegate: VirtualRecentChannelsPlaylistRepository,
    private val importer: StreamingUrlPlaylistImporter,
    private val fileImporter: StreamingFilePlaylistImporter
) : PlaylistRepository by delegate {
    override suspend fun importFromUrl(
        url: String,
        name: String,
        catalogOrigin: CatalogOriginKind
    ): AppResult<PlaylistImportReport> = importer.importFromUrl(url, name, catalogOrigin)

    override suspend fun importFromFile(
        pathOrUri: String,
        name: String
    ): AppResult<PlaylistImportReport> = fileImporter.importFromFile(pathOrUri, name)

    override suspend fun importFromTvheadend(
        baseUrl: String,
        username: String?,
        password: String?,
        name: String
    ): AppResult<PlaylistImportReport> = importer.importFromTvheadend(baseUrl, username, password, name)
}

@Singleton
class StreamingUrlPlaylistImporter @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val favoriteDao: FavoriteDao,
    private val syncLogDao: SyncLogDao,
    private val parser: M3uParser,
    private val okHttpClient: OkHttpClient,
    private val logoCatalogResolver: LogoCatalogResolver
) {
    private val streamCheckClient = okHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()

    suspend fun importFromUrl(
        url: String,
        name: String,
        catalogOrigin: CatalogOriginKind
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext AppResult.Error("URL is empty")
        runCatching {
            val parsed = okHttpClient.newCall(Request.Builder().url(url).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    parseM3uResponseBody(
                        playlistId = 0L,
                        parser = parser,
                        body = response.body
                    )
                }
            persistParsedPlaylist(
                playlistName = name,
                sourceType = PlaylistSourceType.URL,
                source = url,
                catalogOrigin = catalogOrigin,
                parsed = parsed
            )
        }.getOrElse { throwable ->
            AppResult.Error(
                "Unable to import by URL: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }
    }

    suspend fun importFromTvheadend(
        baseUrl: String,
        username: String?,
        password: String?,
        name: String
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        val playlistUrl = normalizeTvheadendPlaylistUrl(baseUrl)
        if (playlistUrl.isBlank()) return@withContext AppResult.Error("Tvheadend URL is empty")

        runCatching {
            val request = Request.Builder()
                .url(playlistUrl)
                .applyTvheadendBasicAuth(username, password)
                .build()
            val parsed = okHttpClient.newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    parseM3uResponseBody(
                        playlistId = 0L,
                        parser = parser,
                        body = response.body
                    )
                }
            persistParsedPlaylist(
                playlistName = name,
                sourceType = PlaylistSourceType.TVHEADEND,
                source = playlistUrl,
                catalogOrigin = CatalogOriginKind.PROVIDER,
                parsed = parsed
            )
        }.getOrElse { throwable ->
            AppResult.Error(
                "Unable to import Tvheadend: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }
    }

    internal suspend fun persistParsedPlaylist(
        playlistName: String,
        sourceType: PlaylistSourceType,
        source: String,
        catalogOrigin: CatalogOriginKind,
        parsed: ParseResult
    ): AppResult<PlaylistImportReport> {
        if (playlistName.isBlank()) return AppResult.Error("Playlist name is empty")

        return when (parsed) {
            is ParseResult.Invalid -> {
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = "import_failed",
                        message = parsed.reason,
                        createdAt = System.currentTimeMillis()
                    )
                )
                AppResult.Error(parsed.reason)
            }

            is ParseResult.Valid -> {
                val playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = playlistName,
                        sourceType = sourceType.name,
                        source = source,
                        epgSourceUrl = parsed.epgUrls.firstOrNull(),
                        scheduleHours = 12,
                        lastSyncedAt = null,
                        isCustom = false,
                        createdAt = System.currentTimeMillis(),
                        catalogOrigin = catalogOrigin.name
                    )
                )
                var importedCount = 0
                prepareUrlImportEntityChunks(
                    channels = parsed.channels,
                    playlistId = playlistId,
                    chunkSize = DB_INSERT_CHUNK,
                    resolveLogo = { channel ->
                        logoCatalogResolver.resolve(
                            name = channel.name,
                            tvgId = channel.tvgId,
                            playlistSource = source
                        )?.url
                    }
                ).forEach { chunk ->
                    importedCount += chunk.size
                    channelDao.insertAll(chunk)
                }

                inheritGlobalFavorites(playlistId)
                val quickStats = probeAndPersistHealth(
                    channelDao.getChannelsLimited(
                        playlistId = playlistId,
                        limit = AUTO_HEALTH_CHECK_LIMIT
                    )
                )

                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = playlistId,
                        status = "imported",
                        message =
                            "Imported $importedCount/${parsed.channels.size}, " +
                                "duplicates removed=${parsed.channels.size - importedCount}, " +
                                "warnings=${parsed.warnings.size}",
                        createdAt = System.currentTimeMillis()
                    )
                )
                AppResult.Success(
                    PlaylistImportReport(
                        playlistId = playlistId,
                        totalParsed = parsed.channels.size,
                        totalImported = importedCount,
                        removedDuplicates = parsed.channels.size - importedCount,
                        warnings = parsed.warnings,
                        autoChecked = quickStats.totalChecked,
                        available = quickStats.available,
                        unstable = quickStats.unstable,
                        unavailable = quickStats.unavailable
                    )
                )
            }
        }
    }

    private suspend fun inheritGlobalFavorites(playlistId: Long) {
        val favoriteChannelIds = favoriteDao.getFavorites()
            .map { it.channelId }
            .distinct()
        if (favoriteChannelIds.isEmpty()) return

        val favoriteIdentities = mutableSetOf<String>()
        favoriteChannelIds
            .chunked(CHANNEL_LOOKUP_BATCH_SIZE)
            .forEach { batch ->
                channelDao.findByIds(batch).forEach { channel ->
                    favoriteIdentities += GlobalFavoriteIdentity.key(
                        channel.tvgId,
                        channel.name,
                        channel.streamUrl
                    )
                }
            }
        if (favoriteIdentities.isEmpty()) return

        val maxOrderIndex = channelDao.maxOrderIndex(playlistId)
        if (maxOrderIndex < 0) return

        var batchStart = 0
        while (batchStart <= maxOrderIndex) {
            val batchEnd = minOf(
                batchStart + CHANNEL_LOOKUP_BATCH_SIZE - 1,
                maxOrderIndex
            )
            val inherited = channelDao.findByPlaylistIdAndOrderIndexes(
                playlistId = playlistId,
                orderIndexes = (batchStart..batchEnd).toList()
            ).filter { channel ->
                GlobalFavoriteIdentity.key(channel.tvgId, channel.name, channel.streamUrl) in
                    favoriteIdentities
            }.map { channel ->
                FavoriteEntity(channelId = channel.id, addedAt = System.currentTimeMillis())
            }
            if (inherited.isNotEmpty()) {
                favoriteDao.upsertAll(inherited)
            }
            batchStart = batchEnd + 1
        }
    }

    private suspend fun probeAndPersistHealth(channels: List<ChannelEntity>): UrlImportHealthStats {
        if (channels.isEmpty()) return UrlImportHealthStats()
        val semaphore = Semaphore(HEALTH_CHECK_CONCURRENCY)
        val statuses = coroutineScope {
            channels.map { channel ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        channel.id to probeChannelHealth(channel.streamUrl)
                    }
                }
            }.map { it.await() }
        }
        statuses.forEach { (channelId, health) ->
            channelDao.updateHealth(channelId = channelId, health = health.name)
        }
        return UrlImportHealthStats(
            totalChecked = statuses.size,
            available = statuses.count { it.second == ChannelHealth.AVAILABLE },
            unstable = statuses.count { it.second == ChannelHealth.UNSTABLE },
            unavailable = statuses.count { it.second == ChannelHealth.UNAVAILABLE }
        )
    }

    private suspend fun probeChannelHealth(streamUrl: String): ChannelHealth {
        val normalized = streamUrl.trim()
        if (!normalized.startsWith("http://", ignoreCase = true) &&
            !normalized.startsWith("https://", ignoreCase = true)
        ) {
            return ChannelHealth.UNSTABLE
        }

        repeat(HEALTH_CHECK_RETRIES) { attempt ->
            when (val outcome = probeHttpStream(normalized)) {
                is UrlImportProbeOutcome.Success -> {
                    return if (outcome.strongContentType && attempt == 0) {
                        ChannelHealth.AVAILABLE
                    } else {
                        ChannelHealth.UNSTABLE
                    }
                }
                UrlImportProbeOutcome.RetryableFailure -> {
                    if (attempt < HEALTH_CHECK_RETRIES - 1) {
                        delay(HEALTH_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
                UrlImportProbeOutcome.NonRetryableFailure -> return ChannelHealth.UNAVAILABLE
            }
        }
        return ChannelHealth.UNAVAILABLE
    }

    private fun probeHttpStream(url: String): UrlImportProbeOutcome {
        return try {
            val request = Request.Builder().url(url).head().build()
            streamCheckClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> UrlImportProbeOutcome.Success(
                        isStreamLikeContentType(response.header("Content-Type").orEmpty())
                    )
                    response.code == 405 || response.code == 501 -> probeHttpFallbackGet(url)
                    response.code in RETRIABLE_HTTP_CODES -> UrlImportProbeOutcome.RetryableFailure
                    else -> UrlImportProbeOutcome.NonRetryableFailure
                }
            }
        } catch (_: IOException) {
            UrlImportProbeOutcome.RetryableFailure
        }
    }

    private fun probeHttpFallbackGet(url: String): UrlImportProbeOutcome {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-1024")
                .build()
            streamCheckClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 206 -> UrlImportProbeOutcome.Success(
                        isStreamLikeContentType(response.header("Content-Type").orEmpty())
                    )
                    response.code in RETRIABLE_HTTP_CODES -> UrlImportProbeOutcome.RetryableFailure
                    else -> UrlImportProbeOutcome.NonRetryableFailure
                }
            }
        } catch (_: IOException) {
            UrlImportProbeOutcome.RetryableFailure
        }
    }

    private fun isStreamLikeContentType(contentType: String): Boolean {
        val normalized = contentType.lowercase(Locale.ROOT)
        return normalized.contains("video/") ||
            normalized.contains("audio/") ||
            normalized.contains("mpegurl") ||
            normalized.contains("dash+xml") ||
            normalized.contains("octet-stream")
    }

    private fun normalizeTvheadendPlaylistUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return ""
        val url = trimmed.toHttpUrl()
        val path = url.encodedPath.trimEnd('/')
        if (
            path.endsWith(".m3u", ignoreCase = true) ||
            path.endsWith(".m3u8", ignoreCase = true) ||
            path.contains("/playlist", ignoreCase = true)
        ) {
            return url.toString()
        }
        return url.newBuilder()
            .encodedPath(path + "/playlist/channels.m3u")
            .build()
            .toString()
    }

    private fun Request.Builder.applyTvheadendBasicAuth(
        username: String?,
        password: String?
    ): Request.Builder {
        val normalizedUsername = username?.trim().orEmpty()
        val normalizedPassword = password?.trim().orEmpty()
        if (normalizedUsername.isNotBlank() || normalizedPassword.isNotBlank()) {
            header("Authorization", Credentials.basic(normalizedUsername, normalizedPassword))
        }
        return this
    }

    private companion object {
        const val DB_INSERT_CHUNK = 500
        const val CHANNEL_LOOKUP_BATCH_SIZE = 900
        const val AUTO_HEALTH_CHECK_LIMIT = 200
        const val HEALTH_CHECK_CONCURRENCY = 20
        const val HEALTH_CHECK_RETRIES = 2
        const val HEALTH_RETRY_DELAY_MS = 450L
        val RETRIABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}

@Singleton
class StreamingFilePlaylistImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser: M3uParser,
    private val persistence: StreamingUrlPlaylistImporter
) {
    suspend fun importFromFile(
        pathOrUri: String,
        name: String
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        if (pathOrUri.isBlank()) return@withContext AppResult.Error("File path/uri is empty")

        val parsed = runCatching {
            openPlaylistFileInputStream(
                pathOrUri = pathOrUri,
                contentUriOpener = { source ->
                    context.contentResolver.openInputStream(Uri.parse(source))
                }
            ).use { input ->
                input.bufferedReader().use { reader ->
                    reader.mark(1)
                    if (reader.read() == -1) {
                        ParseResult.Invalid("Playlist content is empty")
                    } else {
                        reader.reset()
                        parser.parse(playlistId = 0L, reader = reader)
                    }
                }
            }
        }.getOrElse { throwable ->
            return@withContext AppResult.Error(
                "Unable to read file: ${throwable.toLogSummary(maxDepth = 3)}",
                throwable
            )
        }

        persistence.persistParsedPlaylist(
            playlistName = name,
            sourceType = PlaylistSourceType.FILE,
            source = pathOrUri,
            catalogOrigin = CatalogOriginKind.LOCAL,
            parsed = parsed
        )
    }
}

internal fun openPlaylistFileInputStream(
    pathOrUri: String,
    contentUriOpener: (String) -> InputStream?,
    fileOpener: (String) -> InputStream = { source -> File(source).inputStream() }
): InputStream {
    return if (pathOrUri.startsWith("content://", ignoreCase = true)) {
        contentUriOpener(pathOrUri) ?: error("Cannot open content uri")
    } else {
        fileOpener(pathOrUri)
    }
}

internal fun prepareUrlImportEntityChunks(
    channels: List<Channel>,
    playlistId: Long,
    chunkSize: Int,
    resolveLogo: (Channel) -> String?
): Sequence<List<ChannelEntity>> {
    require(chunkSize > 0) { "chunkSize must be positive" }
    return sequence {
        var orderIndex = 0
        var batch = ArrayList<ChannelEntity>(chunkSize)
        for (channel in uniqueUrlImportChannels(channels)) {
            val enriched = if (!channel.logo.isNullOrBlank()) {
                channel
            } else {
                channel.copy(logo = resolveLogo(channel))
            }
            batch += enriched
                .copy(playlistId = playlistId, orderIndex = orderIndex)
                .toEntity()
            orderIndex += 1
            if (batch.size == chunkSize) {
                yield(batch)
                batch = ArrayList(chunkSize)
            }
        }
        if (batch.isNotEmpty()) yield(batch)
    }
}

internal fun deduplicateUrlImportChannels(channels: List<Channel>): List<Channel> =
    uniqueUrlImportChannels(channels).toList()

private fun uniqueUrlImportChannels(channels: List<Channel>): Sequence<Channel> = sequence {
    val seenUrls = hashSetOf<String>()
    val seenIdentities = hashSetOf<String>()
    for (channel in channels) {
        val normalizedUrl = channel.streamUrl.trim().lowercase(Locale.ROOT)
        if (!seenUrls.add(normalizedUrl)) continue

        val normalizedTvgId = channel.tvgId.orEmpty().trim().lowercase(Locale.ROOT)
        val identity = if (normalizedTvgId.isNotEmpty()) {
            "$normalizedTvgId::${channel.name.trim().lowercase(Locale.ROOT)}"
        } else {
            "__url__::$normalizedUrl"
        }
        if (seenIdentities.add(identity)) yield(channel)
    }
}

private data class UrlImportHealthStats(
    val totalChecked: Int = 0,
    val available: Int = 0,
    val unstable: Int = 0,
    val unavailable: Int = 0
)

private sealed interface UrlImportProbeOutcome {
    data class Success(val strongContentType: Boolean) : UrlImportProbeOutcome
    data object RetryableFailure : UrlImportProbeOutcome
    data object NonRetryableFailure : UrlImportProbeOutcome
}
