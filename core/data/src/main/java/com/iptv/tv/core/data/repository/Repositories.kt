package com.iptv.tv.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.common.toLogSummary
import com.iptv.tv.core.data.mapper.toEntity
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.data.security.ProviderSecretCipher
import com.iptv.tv.core.data.settings.SettingsKeys
import com.iptv.tv.core.data.settings.settingsDataStore
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.PlaylistProviderDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.HistoryEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.ProviderAccountRepository
import com.iptv.tv.core.domain.repository.ScannerRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.engine.data.EngineStreamClient
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.EngineStatus
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistProvider
import com.iptv.tv.core.model.ProviderAccountStatus
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.ProviderType
import com.iptv.tv.core.model.RecordingStorageLocation
import com.iptv.tv.core.model.ScannerLearnedQuery
import com.iptv.tv.core.model.ScannerProxySettings
import com.iptv.tv.core.model.ScannerSearchRequest
import com.iptv.tv.core.network.datasource.PublicRepositoryScannerDataSource
import com.iptv.tv.core.parser.M3uParser
import com.iptv.tv.core.parser.ParseResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScannerRepositoryImpl @Inject constructor(
    private val dataSource: PublicRepositoryScannerDataSource
) : ScannerRepository {
    override suspend fun search(request: ScannerSearchRequest): AppResult<List<com.iptv.tv.core.model.PlaylistCandidate>> {
        if (request.query.isBlank()) return AppResult.Error("Query is empty")
        return runCatching { dataSource.search(request) }
            .fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { throwable ->
                    if (throwable is CancellationException) throw throwable
                    AppResult.Error(
                        message = buildString {
                            append(throwable.message ?: "Scanner failed")
                            append(" | cause=")
                            append(throwable.toLogSummary(maxDepth = 5))
                        },
                        cause = throwable
                    )
                }
            )
    }
}

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao,
    private val favoriteDao: FavoriteDao,
    private val historyDao: HistoryDao,
    private val syncLogDao: SyncLogDao,
    private val parser: M3uParser,
    private val okHttpClient: OkHttpClient
) : PlaylistRepository {
    private val streamCheckClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()
    private val epgCache = ConcurrentHashMap<String, EpgCacheEntry>()

    override fun observePlaylists(): Flow<List<Playlist>> {
        return playlistDao.observePlaylistsWithCount().map { rows ->
            rows.map { row -> row.playlist.toModel(channelCount = row.channelCount) }
        }
    }

    override fun observeChannels(playlistId: Long): Flow<List<Channel>> {
        return channelDao.observeChannels(playlistId).map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun importFromUrl(url: String, name: String): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext AppResult.Error("URL is empty")
        runCatching {
            val request = Request.Builder().url(url).build()
            val body = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = body,
                sourceType = PlaylistSourceType.URL,
                source = url
            )
        }.getOrElse { throwable ->
            AppResult.Error("Unable to import by URL: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
        }
    }

    override suspend fun importFromXtream(
        baseUrl: String,
        username: String,
        password: String,
        name: String
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedUsername = username.trim()
        val normalizedPassword = password.trim()
        if (normalizedBaseUrl.isBlank()) return@withContext AppResult.Error("Xtream server URL is empty")
        if (normalizedUsername.isBlank()) return@withContext AppResult.Error("Xtream username is empty")
        if (normalizedPassword.isBlank()) return@withContext AppResult.Error("Xtream password is empty")

        runCatching {
            val liveStreamsUrl = normalizedBaseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegment("player_api.php")
                .addQueryParameter("username", normalizedUsername)
                .addQueryParameter("password", normalizedPassword)
                .addQueryParameter("action", "get_live_streams")
                .build()
            val liveCategoriesUrl = normalizedBaseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegment("player_api.php")
                .addQueryParameter("username", normalizedUsername)
                .addQueryParameter("password", normalizedPassword)
                .addQueryParameter("action", "get_live_categories")
                .build()

            val body = okHttpClient.newCall(Request.Builder().url(liveStreamsUrl).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                .trim()
            if (!body.startsWith("[")) {
                error("Xtream did not return live channel list; check server, login and password")
            }
            val categoryById = runCatching {
                val categoriesBody = okHttpClient.newCall(Request.Builder().url(liveCategoriesUrl).build())
                    .execute()
                    .use { response ->
                        if (response.isSuccessful) {
                            response.body?.string().orEmpty()
                        } else {
                            ""
                        }
                    }
                    .trim()
                if (categoriesBody.startsWith("[")) {
                    parseXtreamCategoryNames(JSONArray(categoriesBody))
                } else {
                    emptyMap()
                }
            }.getOrDefault(emptyMap())

            val streams = JSONArray(body)
            val rawPlaylist = buildXtreamM3u(
                baseUrl = normalizedBaseUrl,
                username = normalizedUsername,
                password = normalizedPassword,
                streams = streams,
                categoryById = categoryById
            )
            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = rawPlaylist,
                sourceType = PlaylistSourceType.XTREAM,
                source = "$normalizedBaseUrl/player_api.php?username=${urlEncode(normalizedUsername)}&action=get_live_streams"
            )
        }.getOrElse { throwable ->
            AppResult.Error("Unable to import Xtream Codes: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
        }
    }

    override suspend fun importFromStalker(
        portalUrl: String,
        macAddress: String,
        name: String
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        val normalizedPortalUrl = normalizeStalkerPortalUrl(portalUrl)
        val normalizedMac = macAddress.trim().uppercase(Locale.US)
        if (normalizedPortalUrl.isBlank()) return@withContext AppResult.Error("Stalker portal URL is empty")
        if (!STALKER_MAC_REGEX.matches(normalizedMac)) {
            return@withContext AppResult.Error("Stalker MAC must look like 00:1A:79:00:00:00")
        }

        runCatching {
            val token = requestStalkerJson(
                portalUrl = normalizedPortalUrl,
                macAddress = normalizedMac,
                token = null,
                type = "stb",
                action = "handshake"
            ).optJSONObject("js")?.optString("token").orEmpty()
            if (token.isBlank()) error("Stalker portal did not return auth token")

            val genresResponse = requestStalkerJson(
                portalUrl = normalizedPortalUrl,
                macAddress = normalizedMac,
                token = token,
                type = "itv",
                action = "get_genres"
            )
            val genreById = parseStalkerGenres(genresResponse.optJSONArray("js") ?: JSONArray())

            val channelsResponse = requestStalkerJson(
                portalUrl = normalizedPortalUrl,
                macAddress = normalizedMac,
                token = token,
                type = "itv",
                action = "get_all_channels"
            )
            val channels = channelsResponse.optJSONObject("js")
                ?.optJSONArray("data")
                ?: channelsResponse.optJSONArray("js")
                ?: JSONArray()
            val rawPlaylist = buildStalkerM3u(
                channels = channels,
                genreById = genreById
            )

            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = rawPlaylist,
                sourceType = PlaylistSourceType.STALKER,
                source = "$normalizedPortalUrl/server/load.php?type=itv&action=get_all_channels&mac=$normalizedMac"
            )
        }.getOrElse { throwable ->
            AppResult.Error("Unable to import Stalker Portal: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
        }
    }

    override suspend fun importFromText(text: String, name: String): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        importParsedPlaylist(
            playlistName = name,
            rawPlaylist = text,
            sourceType = PlaylistSourceType.TEXT,
            source = "inline"
        )
    }

    override suspend fun importFromFile(pathOrUri: String, name: String): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        if (pathOrUri.isBlank()) return@withContext AppResult.Error("File path/uri is empty")
        val raw = runCatching { readPlaylistContent(pathOrUri) }.getOrElse {
            return@withContext AppResult.Error("Unable to read file: ${it.toLogSummary(maxDepth = 3)}", it)
        }
        importParsedPlaylist(
            playlistName = name,
            rawPlaylist = raw,
            sourceType = PlaylistSourceType.FILE,
            source = pathOrUri
        )
    }

    override suspend fun validatePlaylist(playlistId: Long): AppResult<PlaylistValidationReport> = withContext(Dispatchers.IO) {
        val channels = channelDao.getChannels(playlistId)
        if (channels.isEmpty()) {
            return@withContext AppResult.Success(
                PlaylistValidationReport(
                    playlistId = playlistId,
                    totalChecked = 0,
                    available = 0,
                    unstable = 0,
                    unavailable = 0
                )
            )
        }

        val stats = probeAndPersistHealth(channels)
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = playlistId,
                status = "validation",
                message = "Checked ${stats.totalChecked}; available=${stats.available}, unstable=${stats.unstable}, unavailable=${stats.unavailable}",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(
            PlaylistValidationReport(
                playlistId = playlistId,
                totalChecked = stats.totalChecked,
                available = stats.available,
                unstable = stats.unstable,
                unavailable = stats.unavailable
            )
        )
    }

    override suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")

        playlistDao.updateLastSynced(playlistId, System.currentTimeMillis())
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = playlistId,
                status = "refresh",
                message = "Manual refresh: ${playlist.name}",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(Unit)
    }

    override suspend fun refreshAllPlaylists(): AppResult<Int> = withContext(Dispatchers.IO) {
        val ids = playlistDao.getAllIds()
        if (ids.isEmpty()) {
            syncLogDao.insert(
                SyncLogEntity(
                    playlistId = null,
                    status = "refresh_all",
                    message = "Refresh all skipped: no playlists",
                    createdAt = System.currentTimeMillis()
                )
            )
            return@withContext AppResult.Success(0)
        }

        val now = System.currentTimeMillis()
        ids.forEach { playlistId ->
            playlistDao.updateLastSynced(playlistId, now)
        }
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = "refresh_all",
                message = "Refreshed ${ids.size} playlists",
                createdAt = now
            )
        )
        AppResult.Success(ids.size)
    }

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Int> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")

        val channels = channelDao.getChannels(playlistId)
        val channelIds = channels.map { it.id }
        if (channelIds.isNotEmpty()) {
            favoriteDao.deleteByChannelIds(channelIds)
            historyDao.deleteByChannelIds(channelIds)
        }

        val removedChannels = channelDao.clearPlaylist(playlistId)
        val removedPlaylists = playlistDao.deleteById(playlistId)
        if (removedPlaylists <= 0) {
            return@withContext AppResult.Error("Unable to delete playlist: id=$playlistId")
        }

        syncLogDao.insert(
            SyncLogEntity(
                playlistId = playlistId,
                status = "playlist_deleted",
                message = "Deleted playlist ${playlist.name}, channels=$removedChannels",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(removedChannels)
    }

    override suspend fun setPlaylistEpgSource(playlistId: Long, epgSourceUrl: String?): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")

        val normalized = epgSourceUrl?.trim()?.ifBlank { null }
        if (normalized != null) {
            val lowered = normalized.lowercase(Locale.US)
            if (!lowered.startsWith("http://") && !lowered.startsWith("https://")) {
                return@withContext AppResult.Error("EPG URL должен начинаться с http:// или https://")
            }
        }

        val updated = playlistDao.updateEpgSourceUrl(playlistId, normalized)
        if (updated <= 0) return@withContext AppResult.Error("Unable to update EPG source for playlist id=$playlistId")

        syncLogDao.insert(
            SyncLogEntity(
                playlistId = playlistId,
                status = "epg_source_updated",
                message = "Playlist ${playlist.name}: epgSource=${normalized ?: "-"}",
                createdAt = System.currentTimeMillis()
            )
        )
        AppResult.Success(Unit)
    }

    override suspend fun getChannelById(channelId: Long): AppResult<Channel> = withContext(Dispatchers.IO) {
        if (channelId <= 0) return@withContext AppResult.Error("Invalid channel id")
        val channel = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Channel not found: id=$channelId")
        AppResult.Success(channel.toModel())
    }

    override suspend fun getPlaylistContentSummary(playlistId: Long): AppResult<PlaylistContentSummary> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")
        val channels = channelDao.getChannels(playlistId).map { it.toModel() }
        val healthCounts = channels.groupingBy { it.health }.eachCount()
        val groups = channels
            .asSequence()
            .map { it.group?.trim().orEmpty().ifBlank { "Без группы" } }
            .groupingBy { it }
            .eachCount()
        val previews = channels
            .sortedWith(
                compareByDescending<Channel> { !it.logo.isNullOrBlank() }
                    .thenBy { it.isHidden }
                    .thenBy { it.orderIndex }
            )
            .take(16)
            .map { channel ->
                ChannelPreview(
                    id = channel.id,
                    name = channel.name,
                    group = channel.group,
                    logo = channel.logo,
                    health = channel.health,
                    isHidden = channel.isHidden
                )
            }

        AppResult.Success(
            PlaylistContentSummary(
                playlistId = playlist.id,
                playlistName = playlist.name,
                sourceType = PlaylistSourceType.valueOf(playlist.sourceType),
                source = playlist.source,
                epgSourceUrl = playlist.epgSourceUrl,
                totalChannels = channels.size,
                visibleChannels = channels.count { !it.isHidden },
                hiddenChannels = channels.count { it.isHidden },
                channelsWithLogo = channels.count { !it.logo.isNullOrBlank() },
                channelsWithTvgId = channels.count { !it.tvgId.isNullOrBlank() },
                availableChannels = healthCounts[ChannelHealth.AVAILABLE].orZero(),
                unstableChannels = healthCounts[ChannelHealth.UNSTABLE].orZero(),
                unavailableChannels = healthCounts[ChannelHealth.UNAVAILABLE].orZero(),
                unknownHealthChannels = healthCounts[ChannelHealth.UNKNOWN].orZero(),
                groupCount = groups.size,
                topGroups = groups.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .take(8)
                    .map { it.key to it.value },
                channelPreviews = previews
            )
        )
    }

    override suspend fun getChannelEpgNowNext(channelId: Long): AppResult<ChannelEpgInfo> = withContext(Dispatchers.IO) {
        if (channelId <= 0) return@withContext AppResult.Error("Invalid channel id")
        val channelEntity = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Channel not found: id=$channelId")
        val playlist = playlistDao.findById(channelEntity.playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=${channelEntity.playlistId}")

        val epgUrl = playlist.epgSourceUrl?.trim().orEmpty()
        if (epgUrl.isBlank()) {
            return@withContext AppResult.Error("EPG source URL is not configured for playlist ${playlist.id}")
        }

        val epgData = runCatching { getOrLoadXmlTv(epgUrl) }.getOrElse { throwable ->
            return@withContext AppResult.Error(
                "Unable to load EPG: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }

        val match = matchChannelToEpg(
            channelName = channelEntity.name,
            tvgId = channelEntity.tvgId,
            data = epgData
        )
        val now = System.currentTimeMillis()
        val nowProgram = match.programs.firstOrNull { it.startEpochMs <= now && now < it.endEpochMs }
        val nextProgram = match.programs.firstOrNull { it.startEpochMs > now }
        val upcoming = match.programs
            .filter { it.endEpochMs > now }
            .take(12)

        AppResult.Success(
            ChannelEpgInfo(
                channelId = channelEntity.id,
                channelName = channelEntity.name,
                tvgId = channelEntity.tvgId,
                epgSourceUrl = epgUrl,
                matchedBy = match.matchedBy,
                now = nowProgram,
                next = nextProgram,
                upcoming = upcoming
            )
        )
    }

    override suspend fun getPlaylistEpgWindow(
        playlistId: Long,
        startEpochMs: Long,
        endEpochMs: Long,
        query: String?
    ): AppResult<Map<Long, List<EpgProgram>>> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        if (endEpochMs <= startEpochMs) return@withContext AppResult.Error("Invalid EPG time window")

        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")
        val epgUrl = playlist.epgSourceUrl?.trim().orEmpty()
        if (epgUrl.isBlank()) {
            return@withContext AppResult.Error("EPG source URL is not configured for playlist ${playlist.id}")
        }

        val epgData = runCatching { getOrLoadXmlTv(epgUrl) }.getOrElse { throwable ->
            return@withContext AppResult.Error(
                "Unable to load EPG: ${throwable.toLogSummary(maxDepth = 4)}",
                throwable
            )
        }

        val normalizedQuery = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val result = channelDao.getChannels(playlistId)
            .asSequence()
            .filter { !it.isHidden }
            .mapNotNull { channel ->
                val match = matchChannelToEpg(
                    channelName = channel.name,
                    tvgId = channel.tvgId,
                    data = epgData
                )
                val programs = match.programs
                    .asSequence()
                    .filter { it.endEpochMs > startEpochMs && it.startEpochMs < endEpochMs }
                    .filter { program ->
                        normalizedQuery.isBlank() ||
                            program.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
                            program.description?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true ||
                            program.category?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true ||
                            channel.name.lowercase(Locale.ROOT).contains(normalizedQuery)
                    }
                    .sortedBy { it.startEpochMs }
                    .toList()
                if (programs.isEmpty()) null else channel.id to programs
            }
            .toMap()

        AppResult.Success(result)
    }

    private suspend fun importParsedPlaylist(
        playlistName: String,
        rawPlaylist: String,
        sourceType: PlaylistSourceType,
        source: String
    ): AppResult<PlaylistImportReport> {
        if (playlistName.isBlank()) return AppResult.Error("Playlist name is empty")
        if (rawPlaylist.isBlank()) return AppResult.Error("Playlist content is empty")

        return when (val parsed = parser.parse(playlistId = 0L, raw = rawPlaylist)) {
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
                val deduplicated = deduplicate(parsed.channels)
                val enrichedLogos = deduplicated.map { channel ->
                    if (!channel.logo.isNullOrBlank()) {
                        channel
                    } else {
                        channel.copy(logo = resolveLogoFromCatalog(channel))
                    }
                }
                val playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = playlistName,
                        sourceType = sourceType.name,
                        source = source,
                        epgSourceUrl = parsed.epgUrls.firstOrNull(),
                        scheduleHours = 12,
                        lastSyncedAt = null,
                        isCustom = false,
                        createdAt = System.currentTimeMillis()
                    )
                )

                val prepared = enrichedLogos.mapIndexed { index, channel ->
                    channel.copy(playlistId = playlistId, orderIndex = index)
                }
                prepared
                    .chunked(DB_INSERT_CHUNK)
                    .forEach { chunk -> channelDao.insertAll(chunk.map { it.toEntity() }) }

                val storedChannels = channelDao.getChannels(playlistId)
                val quickStats = probeAndPersistHealth(storedChannels.take(AUTO_HEALTH_CHECK_LIMIT))

                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = playlistId,
                        status = "imported",
                        message = "Imported ${prepared.size}/${parsed.channels.size}, duplicates removed=${parsed.channels.size - prepared.size}, warnings=${parsed.warnings.size}",
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

    private fun readPlaylistContent(pathOrUri: String): String {
        return if (pathOrUri.startsWith("content://", ignoreCase = true)) {
            val uri = Uri.parse(pathOrUri)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Cannot open content uri")
        } else {
            File(pathOrUri).readText()
        }
    }

    private fun normalizeStalkerPortalUrl(portalUrl: String): String {
        return portalUrl
            .trim()
            .trimEnd('/')
            .removeSuffix("/c")
            .let { value ->
                if (value.endsWith("/stalker_portal", ignoreCase = true)) value else "$value/stalker_portal"
            }
    }

    private fun requestStalkerJson(
        portalUrl: String,
        macAddress: String,
        token: String?,
        type: String,
        action: String
    ): JSONObject {
        val url = portalUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("server")
            .addPathSegment("load.php")
            .addQueryParameter("type", type)
            .addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", STALKER_USER_AGENT)
            .header("Cookie", "mac=$macAddress; stb_lang=en; timezone=UTC")
            .header("X-User-Agent", STALKER_USER_AGENT)
            .header("Referer", "$portalUrl/c/")
        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }

        val body = okHttpClient.newCall(requestBuilder.build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            .trim()
        if (!body.startsWith("{")) {
            error("Stalker portal returned non-JSON response")
        }
        return JSONObject(body)
    }

    private fun parseStalkerGenres(genres: JSONArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (index in 0 until genres.length()) {
            val item = genres.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            val title = item.optString("title").trim()
            if (id.isNotBlank() && title.isNotBlank()) {
                result[id] = title
            }
        }
        return result
    }

    private fun buildStalkerM3u(
        channels: JSONArray,
        genreById: Map<String, String>
    ): String {
        val builder = StringBuilder("#EXTM3U\n")
        for (index in 0 until channels.length()) {
            val item = channels.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val streamUrl = normalizeStalkerStreamUrl(item.optString("cmd"))
            if (name.isBlank() || streamUrl.isNullOrBlank()) continue

            val tvgId = item.optString("xmltv_id").trim()
                .ifBlank { item.optString("id").trim() }
            val logo = item.optString("logo").trim()
            val group = genreById[item.optString("tv_genre_id").trim()].orEmpty()

            builder
                .append("#EXTINF:-1")
                .appendM3uAttribute("tvg-id", tvgId)
                .appendM3uAttribute("tvg-logo", logo)
                .appendM3uAttribute("group-title", group)
                .append(',')
                .append(name)
                .append('\n')
                .append(streamUrl)
                .append('\n')
        }
        return builder.toString()
    }

    private fun normalizeStalkerStreamUrl(rawCommand: String): String? {
        val cleaned = rawCommand
            .trim()
            .removePrefix("ffmpeg ")
            .removePrefix("auto ")
            .trim()
        return cleaned
            .takeIf {
                it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("https://", ignoreCase = true)
            }
            ?.substringBefore(' ')
    }

    private fun buildXtreamM3u(
        baseUrl: String,
        username: String,
        password: String,
        streams: JSONArray,
        categoryById: Map<String, String>
    ): String {
        val builder = StringBuilder("#EXTM3U\n")
        for (index in 0 until streams.length()) {
            val item = streams.optJSONObject(index) ?: continue
            val streamId = item.optString("stream_id").trim()
            val channelName = item.optString("name").trim()
            if (streamId.isBlank() || channelName.isBlank()) continue

            val tvgId = item.optString("epg_channel_id").trim()
            val logo = item.optString("stream_icon").trim()
            val categoryId = item.optString("category_id").trim()
            val group = item.optString("category_name").trim()
                .ifBlank { categoryById[categoryId].orEmpty() }
            val extension = item.optString("container_extension")
                .trim()
                .ifBlank { "ts" }
                .trimStart('.')

            builder
                .append("#EXTINF:-1")
                .appendM3uAttribute("tvg-id", tvgId)
                .appendM3uAttribute("tvg-logo", logo)
                .appendM3uAttribute("group-title", group)
                .append(',')
                .append(channelName)
                .append('\n')
                .append(baseUrl)
                .append("/live/")
                .append(urlEncode(username))
                .append('/')
                .append(urlEncode(password))
                .append('/')
                .append(urlEncode(streamId))
                .append('.')
                .append(extension)
                .append('\n')
        }
        return builder.toString()
    }

    private fun parseXtreamCategoryNames(categories: JSONArray): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (index in 0 until categories.length()) {
            val item = categories.optJSONObject(index) ?: continue
            val categoryId = item.optString("category_id").trim()
            val categoryName = item.optString("category_name").trim()
            if (categoryId.isNotBlank() && categoryName.isNotBlank()) {
                result[categoryId] = categoryName
            }
        }
        return result
    }

    private fun StringBuilder.appendM3uAttribute(key: String, value: String): StringBuilder {
        if (value.isBlank()) return this
        return append(' ')
            .append(key)
            .append("=\"")
            .append(value.replace("\"", "'"))
            .append('"')
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }

    private fun deduplicate(channels: List<Channel>): List<Channel> {
        val byUrl = linkedMapOf<String, Channel>()
        channels.forEach { channel ->
            val key = normalizeStreamKey(channel.streamUrl)
            if (!byUrl.containsKey(key)) {
                byUrl[key] = channel
            }
        }

        val byIdAndName = linkedMapOf<String, Channel>()
        byUrl.values.forEach { channel ->
            val normalizedTvgId = channel.tvgId.orEmpty().trim().lowercase(Locale.ROOT)
            val key = if (normalizedTvgId.isNotEmpty()) {
                "$normalizedTvgId::${channel.name.trim().lowercase(Locale.ROOT)}"
            } else {
                "__url__::${normalizeStreamKey(channel.streamUrl)}"
            }
            if (!byIdAndName.containsKey(key)) {
                byIdAndName[key] = channel
            }
        }
        return byIdAndName.values.toList()
    }

    private fun normalizeStreamKey(url: String): String {
        return url.trim().lowercase(Locale.ROOT)
    }

    private fun resolveLogoFromCatalog(channel: Channel): String? {
        val tvgIdKey = channel.tvgId
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("[^a-z0-9._-]"), "")
            .orEmpty()
        if (tvgIdKey.isNotBlank()) {
            LOGO_BY_TVG_ID[tvgIdKey]?.let { return it }
        }

        val nameKey = channel.name.trim().lowercase(Locale.ROOT)
        LOGO_BY_NAME_KEYWORD.firstOrNull { (keyword, _) ->
            nameKey.contains(keyword)
        }?.let { return it.second }

        return null
    }

    private fun getOrLoadXmlTv(url: String): XmlTvData {
        val now = System.currentTimeMillis()
        epgCache[url]?.takeIf { now - it.loadedAtMs <= EPG_CACHE_TTL_MS }?.let { cached ->
            return cached.data
        }

        val xmlPayload = okHttpClient.newCall(
            Request.Builder()
                .url(url)
                .get()
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            response.body?.bytes() ?: error("Empty EPG body")
        }

        val parsed = parseXmlTv(xmlPayload)
        epgCache[url] = EpgCacheEntry(loadedAtMs = now, data = parsed)
        return parsed
    }

    private fun parseXmlTv(payload: ByteArray): XmlTvData {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(payload), Charsets.UTF_8.name())
        }

        val channelDisplayNames = mutableMapOf<String, MutableSet<String>>()
        val programsByChannel = mutableMapOf<String, MutableList<EpgProgram>>()

        try {
            var event = parser.eventType
            var currentChannelId: String? = null
            var currentProgrammeChannel: String? = null
            var currentProgrammeTitle: String? = null
            var currentProgrammeDesc: String? = null
            var currentProgrammeCategory: String? = null
            var currentProgrammeStart: Long? = null
            var currentProgrammeStop: Long? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                currentChannelId = parser.getAttributeValue(null, "id")
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                            }
                            "programme" -> {
                                currentProgrammeChannel = parser.getAttributeValue(null, "channel")
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                currentProgrammeStart = parseXmlTvTime(parser.getAttributeValue(null, "start"))
                                currentProgrammeStop = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
                                currentProgrammeTitle = null
                                currentProgrammeDesc = null
                                currentProgrammeCategory = null
                            }
                            "display-name" -> {
                                if (!currentChannelId.isNullOrBlank()) {
                                    val value = parser.nextText().trim()
                                    if (value.isNotBlank()) {
                                        channelDisplayNames.getOrPut(currentChannelId!!) { linkedSetOf() } += value
                                    }
                                }
                            }
                            "title" -> {
                                if (!currentProgrammeChannel.isNullOrBlank()) {
                                    currentProgrammeTitle = parser.nextText().trim().takeIf { it.isNotBlank() }
                                }
                            }
                            "desc" -> {
                                if (!currentProgrammeChannel.isNullOrBlank()) {
                                    currentProgrammeDesc = parser.nextText().trim().takeIf { it.isNotBlank() }
                                }
                            }
                            "category" -> {
                                if (!currentProgrammeChannel.isNullOrBlank()) {
                                    currentProgrammeCategory = parser.nextText().trim().takeIf { it.isNotBlank() }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "channel" -> currentChannelId = null
                            "programme" -> {
                                val channel = currentProgrammeChannel
                                val start = currentProgrammeStart
                                val stop = currentProgrammeStop
                                val title = currentProgrammeTitle
                                if (!channel.isNullOrBlank() && start != null && stop != null && !title.isNullOrBlank()) {
                                    programsByChannel.getOrPut(channel) { mutableListOf() } += EpgProgram(
                                        title = title,
                                        description = currentProgrammeDesc,
                                        category = currentProgrammeCategory,
                                        startEpochMs = start,
                                        endEpochMs = stop
                                    )
                                }
                                currentProgrammeChannel = null
                                currentProgrammeStart = null
                                currentProgrammeStop = null
                                currentProgrammeTitle = null
                                currentProgrammeDesc = null
                                currentProgrammeCategory = null
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (throwable: XmlPullParserException) {
            throw IOException("Invalid XMLTV format: ${throwable.message}", throwable)
        }

        val normalizedPrograms = programsByChannel
            .mapValues { (_, items) ->
                items
                    .distinctBy { "${it.startEpochMs}:${it.endEpochMs}:${it.title}" }
                    .sortedBy { it.startEpochMs }
            }

        val normalizedDisplayNames = channelDisplayNames
            .mapValues { (_, names) -> names.toSet() }

        return XmlTvData(
            channelDisplayNames = normalizedDisplayNames,
            programsByChannel = normalizedPrograms
        )
    }

    private fun parseXmlTvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        val matcher = XMLTV_TIME_REGEX.find(text) ?: return null
        val year = matcher.groupValues[1].toIntOrNull() ?: return null
        val month = matcher.groupValues[2].toIntOrNull() ?: return null
        val day = matcher.groupValues[3].toIntOrNull() ?: return null
        val hour = matcher.groupValues[4].toIntOrNull() ?: return null
        val minute = matcher.groupValues[5].toIntOrNull() ?: return null
        val second = matcher.groupValues[6].toIntOrNull() ?: 0
        val zoneRaw = matcher.groupValues.getOrNull(7).orEmpty().trim()

        val utcMs = runCatching {
            GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute, second)
            }.timeInMillis
        }.getOrNull() ?: return null

        if (zoneRaw.isBlank()) return utcMs

        val zoneSign = if (zoneRaw.startsWith("-")) -1 else 1
        val zoneDigits = zoneRaw.removePrefix("+").removePrefix("-").replace(":", "")
        val hh = zoneDigits.take(2).toIntOrNull() ?: 0
        val mm = zoneDigits.drop(2).take(2).toIntOrNull() ?: 0
        val offsetMs = ((hh * 60L) + mm) * 60_000L * zoneSign
        return utcMs - offsetMs
    }

    private fun matchChannelToEpg(
        channelName: String,
        tvgId: String?,
        data: XmlTvData
    ): EpgMatch {
        val normalizedTvgId = tvgId
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
        if (normalizedTvgId != null) {
            val exact = data.programsByChannel.entries.firstOrNull { entry ->
                entry.key.trim().lowercase(Locale.ROOT) == normalizedTvgId
            }
            if (exact != null) {
                return EpgMatch(programs = exact.value, matchedBy = "tvg-id")
            }
        }

        val normalizedName = normalizeTextKey(channelName)
        if (normalizedName.isNotBlank()) {
            val byDisplayName = data.channelDisplayNames.entries.firstOrNull { (_, names) ->
                names.any { normalizeTextKey(it) == normalizedName }
            }
            if (byDisplayName != null) {
                val programs = data.programsByChannel[byDisplayName.key].orEmpty()
                return EpgMatch(programs = programs, matchedBy = "display-name")
            }

            val byContains = data.programsByChannel.entries.firstOrNull { (channelId, _) ->
                normalizeTextKey(channelId).contains(normalizedName) ||
                    normalizedName.contains(normalizeTextKey(channelId))
            }
            if (byContains != null) {
                return EpgMatch(programs = byContains.value, matchedBy = "channel-id")
            }
        }

        return EpgMatch(programs = emptyList(), matchedBy = "not-matched")
    }

    private fun normalizeTextKey(raw: String): String {
        return raw
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    private suspend fun probeAndPersistHealth(channels: List<ChannelEntity>): HealthStats {
        if (channels.isEmpty()) return HealthStats()

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

        return HealthStats(
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
                is ProbeOutcome.Success -> {
                    return when {
                        outcome.strongContentType && attempt == 0 -> ChannelHealth.AVAILABLE
                        outcome.strongContentType -> ChannelHealth.UNSTABLE
                        else -> ChannelHealth.UNSTABLE
                    }
                }
                ProbeOutcome.RetryableFailure -> {
                    if (attempt < HEALTH_CHECK_RETRIES - 1) {
                        delay(HEALTH_RETRY_DELAY_MS * (attempt + 1))
                    }
                }
                ProbeOutcome.NonRetryableFailure -> return ChannelHealth.UNAVAILABLE
            }
        }
        return ChannelHealth.UNAVAILABLE
    }

    private fun probeHttpStream(url: String): ProbeOutcome {
        return try {
            val headRequest = Request.Builder().url(url).head().build()
            streamCheckClient.newCall(headRequest).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val contentType = response.header("Content-Type").orEmpty()
                        ProbeOutcome.Success(isStreamLikeContentType(contentType))
                    }
                    response.code == 405 || response.code == 501 -> probeHttpFallbackGet(url)
                    response.code in RETRIABLE_HTTP_CODES -> ProbeOutcome.RetryableFailure
                    else -> ProbeOutcome.NonRetryableFailure
                }
            }
        } catch (_: IOException) {
            ProbeOutcome.RetryableFailure
        }
    }

    private fun probeHttpFallbackGet(url: String): ProbeOutcome {
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-1024")
                .build()
            streamCheckClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 206 -> {
                        val contentType = response.header("Content-Type").orEmpty()
                        ProbeOutcome.Success(isStreamLikeContentType(contentType))
                    }
                    response.code in RETRIABLE_HTTP_CODES -> ProbeOutcome.RetryableFailure
                    else -> ProbeOutcome.NonRetryableFailure
                }
            }
        } catch (_: IOException) {
            ProbeOutcome.RetryableFailure
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

    private data class HealthStats(
        val totalChecked: Int = 0,
        val available: Int = 0,
        val unstable: Int = 0,
        val unavailable: Int = 0
    )

    private sealed interface ProbeOutcome {
        data class Success(val strongContentType: Boolean) : ProbeOutcome
        data object RetryableFailure : ProbeOutcome
        data object NonRetryableFailure : ProbeOutcome
    }

    private data class EpgCacheEntry(
        val loadedAtMs: Long,
        val data: XmlTvData
    )

    private data class XmlTvData(
        val channelDisplayNames: Map<String, Set<String>>,
        val programsByChannel: Map<String, List<EpgProgram>>
    )

    private data class EpgMatch(
        val programs: List<EpgProgram>,
        val matchedBy: String
    )

    private companion object {
        const val DB_INSERT_CHUNK = 500
        const val AUTO_HEALTH_CHECK_LIMIT = 200
        const val HEALTH_CHECK_CONCURRENCY = 20
        const val HEALTH_CHECK_RETRIES = 2
        const val HEALTH_RETRY_DELAY_MS = 450L
        const val EPG_CACHE_TTL_MS = 15 * 60 * 1000L
        const val STALKER_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; MAG200; en-US) AppleWebKit/533.3"
        val XMLTV_TIME_REGEX =
            Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+\\-]\\d{4})?.*$")
        val STALKER_MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
        val RETRIABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
        val LOGO_BY_TVG_ID = mapOf(
            "bbcone.uk" to "https://upload.wikimedia.org/wikipedia/commons/4/47/BBC_One_logo.svg",
            "bbctwo.uk" to "https://upload.wikimedia.org/wikipedia/commons/6/62/BBC_Two_logo_2021.svg",
            "cnn.us" to "https://upload.wikimedia.org/wikipedia/commons/b/b1/CNN.svg",
            "euronews.fr" to "https://upload.wikimedia.org/wikipedia/commons/5/58/Euronews_2016_logo.svg",
            "discoverychannel.us" to "https://upload.wikimedia.org/wikipedia/commons/4/43/Discovery_Channel_Logo.svg",
            "cartoonnetwork.us" to "https://upload.wikimedia.org/wikipedia/commons/0/04/Cartoon_Network_2010_logo.svg",
            "mtv.us" to "https://upload.wikimedia.org/wikipedia/commons/e/ea/MTV_Logo_2010.svg",
            "nickelodeon.us" to "https://upload.wikimedia.org/wikipedia/commons/6/6e/Nickelodeon_2023_logo_%28outline%29.svg",
            "animalplanet.us" to "https://upload.wikimedia.org/wikipedia/commons/2/2b/Animal_Planet_logo_2018.svg",
            "natgeo.us" to "https://upload.wikimedia.org/wikipedia/commons/f/fc/Natgeologo.svg",
            "tnt.us" to "https://upload.wikimedia.org/wikipedia/commons/c/c2/TNT_Logo_2016.svg"
        )
        val LOGO_BY_NAME_KEYWORD = listOf(
            "bbc one" to "https://upload.wikimedia.org/wikipedia/commons/4/47/BBC_One_logo.svg",
            "bbc two" to "https://upload.wikimedia.org/wikipedia/commons/6/62/BBC_Two_logo_2021.svg",
            "cnn" to "https://upload.wikimedia.org/wikipedia/commons/b/b1/CNN.svg",
            "euronews" to "https://upload.wikimedia.org/wikipedia/commons/5/58/Euronews_2016_logo.svg",
            "discovery" to "https://upload.wikimedia.org/wikipedia/commons/4/43/Discovery_Channel_Logo.svg",
            "national geographic" to "https://upload.wikimedia.org/wikipedia/commons/f/fc/Natgeologo.svg",
            "nickelodeon" to "https://upload.wikimedia.org/wikipedia/commons/6/6e/Nickelodeon_2023_logo_%28outline%29.svg",
            "cartoon network" to "https://upload.wikimedia.org/wikipedia/commons/0/04/Cartoon_Network_2010_logo.svg",
            "animal planet" to "https://upload.wikimedia.org/wikipedia/commons/2/2b/Animal_Planet_logo_2018.svg",
            "mtv" to "https://upload.wikimedia.org/wikipedia/commons/e/ea/MTV_Logo_2010.svg",
            "eurosport" to "https://upload.wikimedia.org/wikipedia/commons/e/e7/Eurosport_2023.svg",
            "espn" to "https://upload.wikimedia.org/wikipedia/commons/2/2f/ESPN_wordmark.svg"
        )
    }
}

@Singleton
class ProviderAccountRepositoryImpl @Inject constructor(
    private val providerDao: PlaylistProviderDao,
    private val playlistRepository: PlaylistRepository,
    private val secretCipher: ProviderSecretCipher,
    private val okHttpClient: OkHttpClient
) : ProviderAccountRepository {
    override fun observeProviders(): Flow<List<PlaylistProvider>> {
        return providerDao.observeProviders().map { rows ->
            rows.map { it.toModel().toProviderDisplayModel() }
        }
    }

    override suspend fun saveProvider(provider: PlaylistProvider): AppResult<Long> = withContext(Dispatchers.IO) {
        if (provider.name.isBlank()) return@withContext AppResult.Error("Provider name is empty")
        if (provider.baseUrl.isBlank()) return@withContext AppResult.Error("Provider URL is empty")
        runCatching {
            providerDao.upsert(provider.withEncryptedSecrets().toEntity())
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable -> AppResult.Error("Unable to save provider: ${throwable.toLogSummary(4)}", throwable) }
        )
    }

    override suspend fun checkProvider(providerId: Long): AppResult<ProviderAccountStatus> = withContext(Dispatchers.IO) {
        val provider = runCatching {
            providerDao.findById(providerId)?.toModel()?.withDecryptedSecrets()
        }.getOrElse { throwable ->
            return@withContext AppResult.Error("Unable to decrypt provider secrets: ${throwable.toLogSummary(4)}", throwable)
        } ?: return@withContext AppResult.Error("Provider not found")

        runCatching {
            when (provider.type) {
                ProviderType.XTREAM -> checkXtreamProvider(provider)
                ProviderType.STALKER -> checkStalkerProvider(provider)
                ProviderType.M3U -> checkM3uProvider(provider)
                else -> ProviderAccountStatus(
                    providerId = provider.id,
                    type = provider.type,
                    ok = false,
                    statusText = "Не поддержано",
                    detail = "${provider.type} status check is not implemented yet",
                    checkedAt = System.currentTimeMillis()
                )
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable -> AppResult.Error("Unable to check provider: ${throwable.toLogSummary(4)}", throwable) }
        )
    }

    override suspend fun syncProvider(providerId: Long): AppResult<Long> = withContext(Dispatchers.IO) {
        val provider = runCatching {
            providerDao.findById(providerId)?.toModel()?.withDecryptedSecrets()
        }.getOrElse { throwable ->
            return@withContext AppResult.Error("Unable to decrypt provider secrets: ${throwable.toLogSummary(4)}", throwable)
        } ?: return@withContext AppResult.Error("Provider not found")
        val result = when (provider.type) {
            ProviderType.XTREAM -> playlistRepository.importFromXtream(
                baseUrl = provider.baseUrl,
                username = provider.username.orEmpty(),
                password = provider.password.orEmpty(),
                name = provider.name
            )
            ProviderType.STALKER -> playlistRepository.importFromStalker(
                portalUrl = provider.baseUrl,
                macAddress = provider.macAddress.orEmpty(),
                name = provider.name
            )
            ProviderType.M3U -> playlistRepository.importFromUrl(
                url = provider.baseUrl,
                name = provider.name
            )
            else -> return@withContext AppResult.Error("${provider.type} sync is not implemented yet")
        }
        when (result) {
            is AppResult.Success -> {
                providerDao.markSynced(providerId, result.data.playlistId, System.currentTimeMillis())
                AppResult.Success(result.data.playlistId)
            }
            is AppResult.Error -> AppResult.Error(result.message, result.cause)
            AppResult.Loading -> AppResult.Error("Provider sync is still loading")
        }
    }

    override suspend fun deleteProvider(providerId: Long): AppResult<Int> = withContext(Dispatchers.IO) {
        runCatching {
            providerDao.deleteById(providerId)
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable -> AppResult.Error("Unable to delete provider: ${throwable.toLogSummary(4)}", throwable) }
        )
    }

    override suspend fun getProvidersByType(type: ProviderType): AppResult<List<PlaylistProvider>> = withContext(Dispatchers.IO) {
        runCatching {
            providerDao.findByType(type.name).map { it.toModel().toProviderDisplayModel() }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { throwable -> AppResult.Error("Unable to load providers: ${throwable.toLogSummary(4)}", throwable) }
        )
    }

    private fun PlaylistProvider.withEncryptedSecrets(): PlaylistProvider {
        return copy(
            password = secretCipher.encryptOrNull(password),
            token = secretCipher.encryptOrNull(token),
            macAddress = secretCipher.encryptOrNull(macAddress)
        )
    }

    private fun PlaylistProvider.withDecryptedSecrets(): PlaylistProvider {
        return try {
            copy(
                password = secretCipher.decryptOrNull(password),
                token = secretCipher.decryptOrNull(token),
                macAddress = secretCipher.decryptOrNull(macAddress)
            )
        } catch (throwable: Throwable) {
            throw IllegalStateException("Unable to decrypt provider secrets", throwable)
        }
    }

    private fun PlaylistProvider.toProviderDisplayModel(): PlaylistProvider {
        val displayMac = runCatching { secretCipher.decryptOrNull(macAddress).maskProviderSecret() }
            .getOrDefault("скрыто")
        return copy(
            password = null,
            token = null,
            macAddress = displayMac
        )
    }

    private fun String?.maskProviderSecret(): String? {
        val raw = this?.takeIf { it.isNotBlank() } ?: return this
        if (raw.length <= 5) return "скрыто"
        return "${raw.take(2)}...${raw.takeLast(2)}"
    }

    private fun checkXtreamProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val url = provider.baseUrl.trim().trimEnd('/').toHttpUrl()
            .newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", provider.username.orEmpty())
            .addQueryParameter("password", provider.password.orEmpty())
            .build()
        val body = okHttpClient.newCall(Request.Builder().url(url).build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            .trim()
        if (!body.startsWith("{")) error("Xtream returned non-JSON response")
        val json = JSONObject(body)
        val userInfo = json.optJSONObject("user_info") ?: json
        val authOk = userInfo.optInt("auth", if (userInfo.optBoolean("auth")) 1 else 0) == 1
        val status = userInfo.optString("status").ifBlank { if (authOk) "Active" else "Unknown" }
        val expires = userInfo.optString("exp_date").takeIf { it.isNotBlank() && it != "null" }
        return ProviderAccountStatus(
            providerId = provider.id,
            type = provider.type,
            ok = authOk && !status.equals("Disabled", ignoreCase = true) && !status.equals("Banned", ignoreCase = true),
            statusText = status,
            detail = buildString {
                append("auth=")
                append(if (authOk) "ok" else "failed")
                expires?.let { append(", exp=$it") }
            },
            checkedAt = System.currentTimeMillis()
        )
    }

    private fun checkStalkerProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val portalUrl = normalizeProviderStalkerPortalUrl(provider.baseUrl)
        val mac = provider.macAddress.orEmpty().trim().uppercase(Locale.US)
        if (!PROVIDER_STALKER_MAC_REGEX.matches(mac)) error("Stalker MAC must look like 00:1A:79:00:00:00")
        val token = requestProviderStalkerJson(
            portalUrl = portalUrl,
            macAddress = mac,
            token = null,
            type = "stb",
            action = "handshake"
        ).optJSONObject("js")?.optString("token").orEmpty()
        return ProviderAccountStatus(
            providerId = provider.id,
            type = provider.type,
            ok = token.isNotBlank(),
            statusText = if (token.isNotBlank()) "Handshake OK" else "No token",
            detail = if (token.isNotBlank()) "portal=$portalUrl" else "Stalker portal did not return token",
            checkedAt = System.currentTimeMillis()
        )
    }

    private fun checkM3uProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val response = okHttpClient.newCall(Request.Builder().url(provider.baseUrl).head().build())
            .execute()
            .use { response ->
                response.code to (response.header("content-type") ?: "unknown")
            }
        return ProviderAccountStatus(
            providerId = provider.id,
            type = provider.type,
            ok = response.first in 200..399,
            statusText = "HTTP ${response.first}",
            detail = "content-type=${response.second}",
            checkedAt = System.currentTimeMillis()
        )
    }

    private fun normalizeProviderStalkerPortalUrl(portalUrl: String): String {
        return portalUrl
            .trim()
            .trimEnd('/')
            .removeSuffix("/c")
            .let { value ->
                if (value.endsWith("/stalker_portal", ignoreCase = true)) value else "$value/stalker_portal"
            }
    }

    private fun requestProviderStalkerJson(
        portalUrl: String,
        macAddress: String,
        token: String?,
        type: String,
        action: String
    ): JSONObject {
        val url = portalUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("server")
            .addPathSegment("load.php")
            .addQueryParameter("type", type)
            .addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build()
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", PROVIDER_STALKER_USER_AGENT)
            .header("Cookie", "mac=$macAddress; stb_lang=en; timezone=UTC")
            .header("X-User-Agent", PROVIDER_STALKER_USER_AGENT)
            .header("Referer", "$portalUrl/c/")
        if (!token.isNullOrBlank()) {
            requestBuilder.header("Authorization", "Bearer $token")
        }
        val body = okHttpClient.newCall(requestBuilder.build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            .trim()
        if (!body.startsWith("{")) error("Stalker portal returned non-JSON response")
        return JSONObject(body)
    }

    private companion object {
        const val PROVIDER_STALKER_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; MAG200; en-US) AppleWebKit/533.3"
        val PROVIDER_STALKER_MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    }
}

@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val channelDao: ChannelDao
) : FavoritesRepository {
    override fun observeFavorites(): Flow<List<Channel>> {
        return channelDao.observeFavoriteChannels().map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun toggleFavorite(channelId: Long) {
        if (favoriteDao.exists(channelId)) {
            favoriteDao.delete(channelId)
        } else {
            favoriteDao.upsert(FavoriteEntity(channelId = channelId, addedAt = System.currentTimeMillis()))
        }
    }
}

@Singleton
class HistoryRepositoryImpl @Inject constructor(
    private val historyDao: HistoryDao
) : HistoryRepository {
    override fun observeHistory(limit: Int) = historyDao.observeHistory(limit).map { rows -> rows.map { it.toModel() } }

    override suspend fun add(channelId: Long, channelName: String) {
        historyDao.insert(
            HistoryEntity(
                channelId = channelId,
                channelName = channelName,
                playedAt = System.currentTimeMillis()
            )
        )
    }

    override suspend fun clear() {
        historyDao.clear()
    }
}

@Singleton
class DiagnosticsRepositoryImpl @Inject constructor(
    private val syncLogDao: SyncLogDao
) : DiagnosticsRepository {
    override fun observeLogs(limit: Int): Flow<List<com.iptv.tv.core.model.SyncLog>> {
        return syncLogDao.observeLogs(limit).map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun addLog(status: String, message: String, playlistId: Long?) {
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = playlistId,
                status = status,
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {
    override fun observeDefaultPlayer(): Flow<PlayerType> {
        return context.settingsDataStore.data.map { prefs ->
            val stored = prefs[SettingsKeys.defaultPlayer] ?: PlayerType.INTERNAL.name
            runCatching { PlayerType.valueOf(stored) }.getOrDefault(PlayerType.INTERNAL)
        }
    }

    override fun observeBufferProfile(): Flow<BufferProfile> {
        return context.settingsDataStore.data.map { prefs ->
            val stored = prefs[SettingsKeys.bufferProfile] ?: BufferProfile.STANDARD.name
            runCatching { BufferProfile.valueOf(stored) }.getOrDefault(BufferProfile.STANDARD)
        }
    }

    override fun observeManualBuffer(): Flow<ManualBufferSettings> {
        return context.settingsDataStore.data.map { prefs ->
            ManualBufferSettings(
                startMs = prefs[SettingsKeys.manualStartMs] ?: DEFAULT_MANUAL_START_MS,
                rebufferMs = prefs[SettingsKeys.manualRebufferMs] ?: DEFAULT_MANUAL_REBUFFER_MS,
                maxMs = prefs[SettingsKeys.manualMaxMs] ?: DEFAULT_MANUAL_MAX_MS
            )
        }
    }

    override fun observeChannelPlayerOverride(channelId: Long): Flow<PlayerType?> {
        return context.settingsDataStore.data.map { prefs ->
            parseChannelOverrides(prefs[SettingsKeys.channelPlayerOverrides])[channelId]
        }
    }

    override fun observeEngineEndpoint(): Flow<String> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.engineEndpoint]?.takeIf { it.isNotBlank() } ?: DEFAULT_ENGINE_ENDPOINT
        }
    }

    override fun observeTorEnabled(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.torEnabled] ?: false
        }
    }

    override fun observeLegalAccepted(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.legalAccepted] ?: false
        }
    }

    override fun observeAllowInsecureUrls(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.allowInsecureUrls] ?: false
        }
    }

    override fun observeDownloadsWifiOnly(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.downloadsWifiOnly] ?: true
        }
    }

    override fun observeMaxParallelDownloads(): Flow<Int> {
        return context.settingsDataStore.data.map { prefs ->
            (prefs[SettingsKeys.maxParallelDownloads] ?: 1).coerceIn(1, 5)
        }
    }

    override fun observeRecordingStorageLocation(): Flow<RecordingStorageLocation> {
        return context.settingsDataStore.data.map { prefs ->
            val stored = prefs[SettingsKeys.recordingStorageLocation] ?: RecordingStorageLocation.INTERNAL.name
            runCatching { RecordingStorageLocation.valueOf(stored) }.getOrDefault(RecordingStorageLocation.INTERNAL)
        }
    }

    override fun observeScannerAiEnabled(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.scannerAiEnabled] ?: true
        }
    }

    override fun observeScannerProxySettings(): Flow<ScannerProxySettings> {
        return context.settingsDataStore.data.map { prefs ->
            ScannerProxySettings(
                enabled = prefs[SettingsKeys.scannerProxyEnabled] ?: false,
                host = prefs[SettingsKeys.scannerProxyHost].orEmpty(),
                port = prefs[SettingsKeys.scannerProxyPort]?.takeIf { it in 1..65535 },
                username = prefs[SettingsKeys.scannerProxyUsername].orEmpty(),
                password = prefs[SettingsKeys.scannerProxyPassword].orEmpty()
            )
        }
    }

    override fun observeScannerLearnedQueries(): Flow<List<ScannerLearnedQuery>> {
        return context.settingsDataStore.data.map { prefs ->
            decodeScannerLearnedQueries(prefs[SettingsKeys.scannerLearnedQueries])
        }
    }

    override fun observeParentalControlSettings(): Flow<ParentalControlSettings> {
        return context.settingsDataStore.data.map { prefs ->
            ParentalControlSettings(
                enabled = prefs[SettingsKeys.parentalEnabled] ?: false,
                pinConfigured = !prefs[SettingsKeys.parentalPinHash].isNullOrBlank(),
                hideAdultChannels = prefs[SettingsKeys.parentalHideAdultChannels] ?: true,
                blockedKeywords = decodeKeywordList(
                    prefs[SettingsKeys.parentalBlockedKeywords],
                    DEFAULT_PARENTAL_KEYWORDS
                )
            )
        }
    }

    override suspend fun setDefaultPlayer(playerType: PlayerType) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.defaultPlayer] = playerType.name
        }
    }

    override suspend fun setBufferProfile(profile: BufferProfile) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.bufferProfile] = profile.name
        }
    }

    override suspend fun setManualBuffer(startMs: Int, rebufferMs: Int, maxMs: Int) {
        val boundedStart = startMs.coerceIn(250, 120_000)
        val boundedMax = maxMs.coerceIn(1_000, 240_000).coerceAtLeast(boundedStart)
        val boundedRebuffer = rebufferMs.coerceIn(250, boundedMax)
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.manualStartMs] = boundedStart
            prefs[SettingsKeys.manualRebufferMs] = boundedRebuffer
            prefs[SettingsKeys.manualMaxMs] = boundedMax
        }
    }

    override suspend fun setChannelPlayerOverride(channelId: Long, playerType: PlayerType?) {
        context.settingsDataStore.edit { prefs ->
            val current = parseChannelOverrides(prefs[SettingsKeys.channelPlayerOverrides]).toMutableMap()
            if (playerType == null) {
                current.remove(channelId)
            } else {
                current[channelId] = playerType
            }
            prefs[SettingsKeys.channelPlayerOverrides] = encodeChannelOverrides(current)
        }
    }

    override suspend fun setEngineEndpoint(endpoint: String) {
        val normalized = endpoint.trim().ifEmpty { DEFAULT_ENGINE_ENDPOINT }
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.engineEndpoint] = normalized
        }
    }

    override suspend fun setTorEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.torEnabled] = enabled
        }
    }

    override suspend fun setLegalAccepted(accepted: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.legalAccepted] = accepted
        }
    }

    override suspend fun setAllowInsecureUrls(allowed: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.allowInsecureUrls] = allowed
        }
    }

    override suspend fun setDownloadsWifiOnly(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.downloadsWifiOnly] = enabled
        }
    }

    override suspend fun setMaxParallelDownloads(value: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.maxParallelDownloads] = value.coerceIn(1, 5)
        }
    }

    override suspend fun setRecordingStorageLocation(location: RecordingStorageLocation) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.recordingStorageLocation] = location.name
        }
    }

    override suspend fun setScannerAiEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.scannerAiEnabled] = enabled
        }
    }

    override suspend fun setScannerProxySettings(settings: ScannerProxySettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.scannerProxyEnabled] = settings.enabled
            prefs[SettingsKeys.scannerProxyHost] = settings.host.trim()
            val port = settings.port?.coerceIn(1, 65535)
            if (port == null) {
                prefs.remove(SettingsKeys.scannerProxyPort)
            } else {
                prefs[SettingsKeys.scannerProxyPort] = port
            }
            prefs[SettingsKeys.scannerProxyUsername] = settings.username.trim()
            prefs[SettingsKeys.scannerProxyPassword] = settings.password
        }
    }

    override suspend fun recordScannerLearning(
        query: String,
        relatedQueries: List<String>,
        presetId: String?
    ) {
        val primary = normalizeLearningQuery(query)
        if (primary.isBlank()) return

        val related = relatedQueries
            .asSequence()
            .map(::normalizeLearningQuery)
            .filter { it.isNotBlank() }
            .filterNot { it.equals(primary, ignoreCase = true) }
            .distinct()
            .take(MAX_SCANNER_LEARN_RELATED)
            .toList()

        val now = System.currentTimeMillis()
        val normalizedPreset = presetId?.trim()?.ifBlank { null }

        context.settingsDataStore.edit { prefs ->
            val current = decodeScannerLearnedQueries(prefs[SettingsKeys.scannerLearnedQueries])
                .associateBy { it.query.lowercase(Locale.ROOT) }
                .toMutableMap()

            fun upsert(value: String, increment: Int) {
                val key = value.lowercase(Locale.ROOT)
                val existing = current[key]
                val updated = if (existing == null) {
                    ScannerLearnedQuery(
                        query = value,
                        hits = increment,
                        lastSuccessAt = now,
                        presetId = normalizedPreset
                    )
                } else {
                    existing.copy(
                        hits = (existing.hits + increment).coerceAtMost(MAX_SCANNER_LEARN_HITS),
                        lastSuccessAt = now,
                        presetId = normalizedPreset ?: existing.presetId
                    )
                }
                current[key] = updated
            }

            upsert(primary, increment = 2)
            related.forEach { value ->
                upsert(value, increment = 1)
            }

            val merged = current.values
                .sortedWith(
                    compareByDescending<ScannerLearnedQuery> { it.hits }
                        .thenByDescending { it.lastSuccessAt }
                )
                .take(MAX_SCANNER_LEARN_ITEMS)

            prefs[SettingsKeys.scannerLearnedQueries] = encodeScannerLearnedQueries(merged)
        }
    }

    override suspend fun clearScannerLearning() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(SettingsKeys.scannerLearnedQueries)
        }
    }

    override suspend fun setParentalControl(
        enabled: Boolean,
        pin: String?,
        hideAdultChannels: Boolean,
        blockedKeywords: List<String>
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.parentalEnabled] = enabled
            prefs[SettingsKeys.parentalHideAdultChannels] = hideAdultChannels
            prefs[SettingsKeys.parentalBlockedKeywords] = encodeKeywordList(blockedKeywords)
            val normalizedPin = pin?.trim().orEmpty()
            if (normalizedPin.isNotBlank()) {
                prefs[SettingsKeys.parentalPinHash] = hashPin(normalizedPin)
            }
        }
    }

    override suspend fun verifyParentalPin(pin: String): Boolean {
        val normalized = pin.trim()
        if (normalized.isBlank()) return false
        val storedHash = context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.parentalPinHash].orEmpty()
        }.first()
        return storedHash.isNotBlank() && storedHash == hashPin(normalized)
    }

    private fun parseChannelOverrides(raw: String?): Map<Long, PlayerType> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';')
            .mapNotNull { entry ->
                val parts = entry.split('=', limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val id = parts[0].trim().toLongOrNull() ?: return@mapNotNull null
                val player = runCatching { PlayerType.valueOf(parts[1].trim()) }.getOrNull() ?: return@mapNotNull null
                id to player
            }
            .toMap()
    }

    private fun encodeChannelOverrides(data: Map<Long, PlayerType>): String {
        if (data.isEmpty()) return ""
        return data.entries
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value.name}" }
    }

    private fun normalizeLearningQuery(raw: String): String {
        return raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .trim(',', '.', ';')
            .take(MAX_SCANNER_LEARN_QUERY_LENGTH)
    }

    private fun encodeScannerLearnedQueries(items: List<ScannerLearnedQuery>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(separator = SCANNER_LEARN_ENTRY_SEPARATOR) { item ->
            listOf(
                safeUrlEncode(item.query),
                item.hits.coerceAtLeast(1).toString(),
                item.lastSuccessAt.coerceAtLeast(0L).toString(),
                safeUrlEncode(item.presetId.orEmpty())
            ).joinToString(SCANNER_LEARN_FIELD_SEPARATOR)
        }
    }

    private fun decodeScannerLearnedQueries(raw: String?): List<ScannerLearnedQuery> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(SCANNER_LEARN_ENTRY_SEPARATOR)
            .asSequence()
            .mapNotNull { entry ->
                val fields = entry.split(SCANNER_LEARN_FIELD_SEPARATOR)
                if (fields.size < 3) return@mapNotNull null
                val query = safeUrlDecode(fields[0]).trim()
                if (query.isBlank()) return@mapNotNull null

                val hits = fields.getOrNull(1)?.toIntOrNull()?.coerceIn(1, MAX_SCANNER_LEARN_HITS) ?: 1
                val lastSuccessAt = fields.getOrNull(2)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                val preset = fields.getOrNull(3)?.let(::safeUrlDecode)?.trim().orEmpty().ifBlank { null }

                ScannerLearnedQuery(
                    query = query,
                    hits = hits,
                    lastSuccessAt = lastSuccessAt,
                    presetId = preset
                )
            }
            .distinctBy { it.query.lowercase(Locale.ROOT) }
            .sortedWith(
                compareByDescending<ScannerLearnedQuery> { it.hits }
                    .thenByDescending { it.lastSuccessAt }
            )
            .take(MAX_SCANNER_LEARN_ITEMS)
            .toList()
    }

    private fun safeUrlEncode(raw: String): String {
        return runCatching {
            URLEncoder.encode(raw, StandardCharsets.UTF_8.toString())
        }.getOrDefault("")
    }

    private fun safeUrlDecode(raw: String): String {
        return runCatching {
            URLDecoder.decode(raw, StandardCharsets.UTF_8.toString())
        }.getOrDefault(raw)
    }

    private fun encodeKeywordList(items: List<String>): String {
        return items
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .joinToString("|") { safeUrlEncode(it) }
    }

    private fun decodeKeywordList(raw: String?, fallback: List<String>): List<String> {
        if (raw.isNullOrBlank()) return fallback
        return raw.split("|")
            .map { safeUrlDecode(it).trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .ifEmpty { fallback }
    }

    private fun hashPin(pin: String): String {
        val input = "$PIN_HASH_SALT:$pin"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        const val DEFAULT_MANUAL_START_MS = 12_000
        const val DEFAULT_MANUAL_REBUFFER_MS = 2_000
        const val DEFAULT_MANUAL_MAX_MS = 50_000
        const val DEFAULT_ENGINE_ENDPOINT = "http://127.0.0.1:6878"
        const val SCANNER_LEARN_ENTRY_SEPARATOR = "||"
        const val SCANNER_LEARN_FIELD_SEPARATOR = "\t"
        const val MAX_SCANNER_LEARN_ITEMS = 80
        const val MAX_SCANNER_LEARN_RELATED = 8
        const val MAX_SCANNER_LEARN_HITS = 9_999
        const val MAX_SCANNER_LEARN_QUERY_LENGTH = 120
        const val PIN_HASH_SALT = "myscanerIPTV-parental-v1"
        val DEFAULT_PARENTAL_KEYWORDS = listOf(
            "adult",
            "xxx",
            "18+",
            "porn",
            "porno",
            "erotic",
            "sex",
            "для взрослых",
            "взрослые",
            "эротика"
        )
    }
}

@Singleton
class EngineRepositoryImpl @Inject constructor(
    private val client: EngineStreamClient,
    private val syncLogDao: SyncLogDao
) : EngineRepository {
    override suspend fun connect(endpoint: String): AppResult<Unit> {
        return when (val result = client.connect(endpoint)) {
            is AppResult.Success -> {
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = "engine_connected",
                        message = "Connected to $endpoint",
                        createdAt = System.currentTimeMillis()
                    )
                )
                result
            }
            is AppResult.Error -> {
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = "engine_connect_error",
                        message = result.message,
                        createdAt = System.currentTimeMillis()
                    )
                )
                result
            }
            AppResult.Loading -> result
        }
    }

    override suspend fun refreshStatus(): AppResult<EngineStatus> = client.refreshStatus()

    override fun observeStatus(): Flow<EngineStatus> = client.observeStatus()

    override suspend fun resolveTorrentStream(magnetOrAce: String): AppResult<String> {
        return when (val result = client.resolveStream(magnetOrAce)) {
            is AppResult.Success -> {
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = "engine_resolved",
                        message = "Resolved torrent descriptor",
                        createdAt = System.currentTimeMillis()
                    )
                )
                result
            }
            is AppResult.Error -> {
                syncLogDao.insert(
                    SyncLogEntity(
                        playlistId = null,
                        status = "engine_resolve_error",
                        message = result.message,
                        createdAt = System.currentTimeMillis()
                    )
                )
                result
            }
            AppResult.Loading -> result
        }
    }
}

private fun Int?.orZero(): Int = this ?: 0
