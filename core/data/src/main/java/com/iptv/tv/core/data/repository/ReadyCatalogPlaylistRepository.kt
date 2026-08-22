package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.parser.M3uParser
import com.iptv.tv.core.parser.ParseResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Adds true network refresh semantics for the small built-in Ready catalog.
 *
 * The legacy repository refresh path is intentionally left unchanged for user/provider sources.
 * Ready URL playlists are re-downloaded, parsed and reconciled in-place so the publisher can update
 * a playlist without creating duplicate playlist rows or invalidating stable channel IDs.
 */
@Singleton
class ReadyCatalogPlaylistRepository @Inject constructor(
    private val delegate: PlaylistRepositoryImpl,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val syncLogDao: SyncLogDao,
    private val parser: M3uParser,
    private val okHttpClient: OkHttpClient,
    private val logoCatalogResolver: LogoCatalogResolver
) : PlaylistRepository by delegate {

    override suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")
        if (
            playlist.catalogOrigin != CatalogOriginKind.READY_CATALOG.name ||
            playlist.sourceType != PlaylistSourceType.URL.name
        ) {
            return@withContext delegate.refreshPlaylist(playlistId)
        }

        val sourceUrl = playlist.source.trim()
        if (sourceUrl.isBlank()) return@withContext AppResult.Error("Ready playlist URL is empty")

        runCatching {
            val body = okHttpClient.newCall(Request.Builder().url(sourceUrl).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            when (val parsed = parser.parse(playlistId = playlistId, raw = body)) {
                is ParseResult.Invalid -> {
                    logRefreshFailure(playlistId, parsed.reason)
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

                    plan.upsertChannels
                        .chunked(READY_REFRESH_DB_CHUNK)
                        .forEach { chunk -> channelDao.insertAll(chunk) }
                    plan.staleChannelIds
                        .chunked(READY_REFRESH_DB_CHUNK)
                        .forEach { chunk -> channelDao.deleteByIds(chunk) }

                    val now = System.currentTimeMillis()
                    playlistDao.updateLastSynced(playlistId, now)
                    syncLogDao.insert(
                        SyncLogEntity(
                            playlistId = playlistId,
                            status = "refresh",
                            message =
                                "Ready playlist refreshed: channels=${plan.upsertChannels.size}, " +
                                    "removed=${plan.staleChannelIds.size}, warnings=${parsed.warnings.size}",
                            createdAt = now
                        )
                    )
                    AppResult.Success(Unit)
                }
            }
        }.getOrElse { throwable ->
            val summary = throwable.toLogSummary(maxDepth = 4)
            logRefreshFailure(playlistId, "Ready playlist refresh failed: $summary")
            AppResult.Error("Unable to refresh ready playlist: $summary", throwable)
        }
    }

    private suspend fun logRefreshFailure(playlistId: Long, message: String) {
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = playlistId,
                status = "refresh_failed",
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}

internal fun deduplicateReadyChannels(channels: List<Channel>): List<Channel> {
    val byUrl = linkedMapOf<String, Channel>()
    channels.forEach { channel ->
        val key = channel.streamUrl.trim().lowercase()
        if (key !in byUrl) byUrl[key] = channel
    }

    val byIdAndName = linkedMapOf<String, Channel>()
    byUrl.values.forEach { channel ->
        val normalizedTvgId = channel.tvgId.orEmpty().trim().lowercase()
        val key = if (normalizedTvgId.isNotEmpty()) {
            "$normalizedTvgId::${channel.name.trim().lowercase()}"
        } else {
            "__url__::${channel.streamUrl.trim().lowercase()}"
        }
        if (key !in byIdAndName) byIdAndName[key] = channel
    }
    return byIdAndName.values.toList()
}

private const val READY_REFRESH_DB_CHUNK = 500
