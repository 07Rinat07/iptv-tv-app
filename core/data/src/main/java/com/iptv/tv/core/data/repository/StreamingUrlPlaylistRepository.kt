package com.iptv.tv.core.data.repository

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
import java.io.IOException
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
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Outermost playlist decorator for the generic URL import path.
 *
 * Provider protocols continue through the existing repository chain. Only a direct URL M3U import
 * is handled here so the OkHttp response can stay open while [M3uParser] consumes its Reader instead
 * of first materializing the complete response as a String.
 */
@Singleton
class StreamingUrlPlaylistRepository @Inject constructor(
    private val delegate: VirtualRecentChannelsPlaylistRepository,
    private val importer: StreamingUrlPlaylistImporter
) : PlaylistRepository by delegate {
    override suspend fun importFromUrl(
        url: String,
        name: String,
        catalogOrigin: CatalogOriginKind
    ): AppResult<PlaylistImportReport> = importer.importFromUrl(url, name, catalogOrigin)
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
            persistParsedUrlPlaylist(
                playlistName = name,
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

    private suspend fun persistParsedUrlPlaylist(
        playlistName: String,
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
                val deduplicated = deduplicateUrlImportChannels(parsed.channels)
                val enrichedLogos = deduplicated.map { channel ->
                    if (!channel.logo.isNullOrBlank()) {
                        channel
                    } else {
                        channel.copy(
                            logo = logoCatalogResolver.resolve(
                                name = channel.name,
                                tvgId = channel.tvgId,
                                playlistSource = source
                            )?.url
                        )
                    }
                }
                val playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = playlistName,
                        sourceType = PlaylistSourceType.URL.name,
                        source = source,
                        epgSourceUrl = parsed.epgUrls.firstOrNull(),
                        scheduleHours = 12,
                        lastSyncedAt = null,
                        isCustom = false,
                        createdAt = System.currentTimeMillis(),
                        catalogOrigin = catalogOrigin.name
                    )
                )
                val prepared = enrichedLogos.mapIndexed { index, channel ->
                    channel.copy(playlistId = playlistId, orderIndex = index)
                }
                prepared.chunked(DB_INSERT_CHUNK)
                    .forEach { chunk -> channelDao.insertAll(chunk.map { it.toEntity() }) }

                val storedChannels = channelDao.getChannels(playlistId)
                inheritGlobalFavorites(storedChannels)
                val quickStats = probeAndPersistHealth(storedChannels.take(AUTO_HEALTH_CHECK_LIMIT))

                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = playlistId,
                        status = "imported",
                        message =
                            "Imported ${prepared.size}/${parsed.channels.size}, " +
                                "duplicates removed=${parsed.channels.size - prepared.size}, " +
                                "warnings=${parsed.warnings.size}",
                        createdAt = System.currentTimeMillis()
                    )
                )
                AppResult.Success(
                    PlaylistImportReport(
                        playlistId = playlistId,
                        totalParsed = parsed.channels.size,
                        totalImported = prepared.size,
                        removedDuplicates = parsed.channels.size - prepared.size,
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

    private suspend fun inheritGlobalFavorites(channels: List<ChannelEntity>) {
        if (channels.isEmpty()) return
        val favoriteChannelIds = favoriteDao.getFavorites().map { it.channelId }
        if (favoriteChannelIds.isEmpty()) return

        val favoriteIdentities = channelDao.findByIds(favoriteChannelIds)
            .mapTo(mutableSetOf()) { channel ->
                GlobalFavoriteIdentity.key(channel.tvgId, channel.name, channel.streamUrl)
            }
        if (favoriteIdentities.isEmpty()) return

        val inherited = channels
            .filter { channel ->
                GlobalFavoriteIdentity.key(channel.tvgId, channel.name, channel.streamUrl) in
                    favoriteIdentities
            }
            .map { channel ->
                FavoriteEntity(channelId = channel.id, addedAt = System.currentTimeMillis())
            }
        if (inherited.isNotEmpty()) favoriteDao.upsertAll(inherited)
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

    private companion object {
        const val DB_INSERT_CHUNK = 500
        const val AUTO_HEALTH_CHECK_LIMIT = 200
        const val HEALTH_CHECK_CONCURRENCY = 20
        const val HEALTH_CHECK_RETRIES = 2
        const val HEALTH_RETRY_DELAY_MS = 450L
        val RETRIABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}

internal fun deduplicateUrlImportChannels(channels: List<Channel>): List<Channel> {
    val byUrl = linkedMapOf<String, Channel>()
    channels.forEach { channel ->
        val key = channel.streamUrl.trim().lowercase(Locale.ROOT)
        if (!byUrl.containsKey(key)) byUrl[key] = channel
    }

    val byIdAndName = linkedMapOf<String, Channel>()
    byUrl.values.forEach { channel ->
        val normalizedTvgId = channel.tvgId.orEmpty().trim().lowercase(Locale.ROOT)
        val key = if (normalizedTvgId.isNotEmpty()) {
            "$normalizedTvgId::${channel.name.trim().lowercase(Locale.ROOT)}"
        } else {
            "__url__::${channel.streamUrl.trim().lowercase(Locale.ROOT)}"
        }
        if (!byIdAndName.containsKey(key)) byIdAndName[key] = channel
    }
    return byIdAndName.values.toList()
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
