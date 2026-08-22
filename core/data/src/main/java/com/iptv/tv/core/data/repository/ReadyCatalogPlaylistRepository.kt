package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.ReadyPlaylistRefreshDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import com.iptv.tv.core.parser.ParseResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adds true network refresh semantics for the small built-in Ready catalog.
 *
 * User/provider sources keep their existing refresh behavior. Ready URL playlists are downloaded,
 * parsed and reconciled in-place so publishers can change channels and M3U EPG metadata without
 * creating duplicate playlist rows or exposing a partially written snapshot.
 */
@Singleton
class ReadyCatalogPlaylistRepository @Inject constructor(
    private val delegate: PlaylistRepositoryImpl,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val readyPlaylistRefreshDao: ReadyPlaylistRefreshDao,
    private val syncLogDao: SyncLogDao,
    private val parser: M3uParser,
    private val okHttpClient: OkHttpClient,
    private val logoCatalogResolver: LogoCatalogResolver
) : PlaylistRepository by delegate {
    /**
     * One lock per physical Ready playlist prevents manual-open refresh and scheduled sync from
     * downloading/planning/writing the same snapshot concurrently. The built-in catalog is tiny,
     * so retaining these locks for the singleton repository lifetime is bounded.
     */
    private val refreshLocks = ConcurrentHashMap<Long, Mutex>()

    override suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val initialPlaylist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")
        if (
            initialPlaylist.catalogOrigin != CatalogOriginKind.READY_CATALOG.name ||
            initialPlaylist.sourceType != PlaylistSourceType.URL.name
        ) {
            return@withContext delegate.refreshPlaylist(playlistId)
        }

        val sourceUrl = initialPlaylist.source.trim()
        if (sourceUrl.isBlank()) return@withContext AppResult.Error("Ready playlist URL is empty")

        val refreshLock = refreshLocks.computeIfAbsent(playlistId) { Mutex() }
        refreshLock.withLock {
            try {
                // Re-read after acquiring the lock so a concurrent edit/delete cannot make us write
                // a response downloaded for an obsolete source into a different playlist.
                val playlist = playlistDao.findById(playlistId)
                    ?: return@withLock AppResult.Error("Playlist not found: id=$playlistId")
                if (
                    playlist.catalogOrigin != CatalogOriginKind.READY_CATALOG.name ||
                    playlist.sourceType != PlaylistSourceType.URL.name ||
                    playlist.source.trim() != sourceUrl
                ) {
                    return@withLock AppResult.Error("Ready playlist changed while refresh was pending")
                }

                val body = okHttpClient.newCall(Request.Builder().url(sourceUrl).build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        response.body?.string().orEmpty()
                    }

                when (val parsed = parser.parse(playlistId = playlistId, raw = body)) {
                    is ParseResult.Invalid -> {
                        logRefreshFailureBestEffort(playlistId, parsed.reason)
                        AppResult.Error(parsed.reason)
                    }

                    is ParseResult.Valid -> {
                        val incoming = deduplicateReadyChannels(parsed.channels).map { channel ->
                            if (!channel.logo.isNullOrBlank()) {
                                channel
                            } else {
                                channel.copy(
                                    logo = logoCatalogResolver.resolve(
                                        name = channel.name,
                                        tvgId = channel.tvgId,
                                        playlistSource = sourceUrl
                                    )?.url
                                )
                            }
                        }
                        val plan = ReadyPlaylistRefreshPlanner.plan(
                            playlistId = playlistId,
                            existing = channelDao.getChannels(playlistId),
                            incoming = incoming
                        )
                        // Ready-catalog EPG metadata is publisher-owned just like its channel list.
                        // If a successful fresh M3U removes url-tvg/x-tvg-url, clear the retired
                        // discovered endpoint instead of requesting it indefinitely.
                        val refreshedEpgSource = refreshedReadyEpgSource(parsed.epgUrls)
                        val now = System.currentTimeMillis()
                        readyPlaylistRefreshDao.applyRefresh(
                            playlistId = playlistId,
                            channels = plan.upsertChannels,
                            staleChannelIds = plan.staleChannelIds,
                            epgSourceUrl = refreshedEpgSource,
                            syncedAt = now,
                            syncLog = SyncLogEntity(
                                playlistId = playlistId,
                                status = "refresh",
                                message =
                                    "Ready playlist refreshed: channels=${plan.upsertChannels.size}, " +
                                        "removed=${plan.staleChannelIds.size}, warnings=${parsed.warnings.size}, " +
                                        "epg=${refreshedEpgSource ?: "-"}",
                                createdAt = now
                            )
                        )
                        AppResult.Success(Unit)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val summary = throwable.toLogSummary(maxDepth = 4)
                logRefreshFailureBestEffort(playlistId, "Ready playlist refresh failed: $summary")
                AppResult.Error("Unable to refresh ready playlist: $summary", throwable)
            }
        }
    }

    /**
     * Periodic sync must use the same live downloader as a manual Ready-catalog open.
     *
     * Refreshes stay sequential on purpose: the built-in catalog is tiny and sequential network/
     * parse work bounds peak memory on TV boxes instead of holding several large M3Us at once.
     */
    override suspend fun refreshAllPlaylists(): AppResult<Int> = withContext(Dispatchers.IO) {
        try {
            val playlistIds = playlistDao.getAllIds()
            if (playlistIds.isEmpty()) return@withContext delegate.refreshAllPlaylists()

            var refreshed = 0
            val failures = mutableListOf<String>()
            playlistIds.forEach { playlistId ->
                when (val result = refreshPlaylist(playlistId)) {
                    is AppResult.Success -> refreshed += 1
                    is AppResult.Error -> failures += "id=$playlistId: ${result.message}"
                    AppResult.Loading -> failures += "id=$playlistId: unexpected loading state"
                }
            }

            val now = System.currentTimeMillis()
            logBestEffort(
                SyncLogEntity(
                    playlistId = null,
                    status = if (failures.isEmpty()) "refresh_all" else "refresh_all_partial",
                    message = if (failures.isEmpty()) {
                        "Refreshed $refreshed playlists"
                    } else {
                        "Refreshed $refreshed/${playlistIds.size} playlists; failed=${failures.size}"
                    },
                    createdAt = now
                )
            )

            if (failures.isEmpty()) {
                AppResult.Success(refreshed)
            } else {
                AppResult.Error(
                    "Playlist refresh incomplete: refreshed=$refreshed/${playlistIds.size}; " +
                        failures.joinToString(separator = " | ", limit = 3, truncated = "…")
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            AppResult.Error(
                "Unable to refresh playlists: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }
    }

    private suspend fun logRefreshFailureBestEffort(playlistId: Long, message: String) {
        logBestEffort(
            SyncLogEntity(
                playlistId = playlistId,
                status = "refresh_failed",
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun logBestEffort(entry: SyncLogEntity) {
        try {
            syncLogDao.insert(entry)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // Diagnostics must never replace the original refresh result when persistence is broken.
        }
    }
}

internal fun refreshedReadyEpgSource(epgUrls: List<String>): String? =
    epgUrls.firstOrNull()?.trim()?.ifBlank { null }

/**
 * A stream URL is the concrete playback identity for Ready refresh deduplication.
 *
 * Multiple primary/backup/quality streams may legitimately share tvg-id and display name. Keep
 * those variants and remove only byte-for-byte URL duplicates after trimming surrounding spaces.
 * URL path/query casing is preserved because HTTP servers may treat it as significant.
 */
internal fun deduplicateReadyChannels(channels: List<Channel>): List<Channel> {
    val byUrl = linkedMapOf<String, Channel>()
    channels.forEach { channel ->
        val key = channel.streamUrl.trim()
        if (key !in byUrl) byUrl[key] = channel
    }
    return byUrl.values.toList()
}
