package com.iptv.tv.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.documentfile.provider.DocumentFile
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
import com.iptv.tv.core.database.dao.ParentalProfileDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.PlaylistProviderDao
import com.iptv.tv.core.database.dao.ProviderSyncHistoryDao
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
import com.iptv.tv.core.model.AppStartDestination
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.EngineStatus
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.ParentalControlProfile
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistEpgDiagnostics
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistProvider
import com.iptv.tv.core.model.ProviderAccountStatus
import com.iptv.tv.core.model.ProviderDiagnosticKind
import com.iptv.tv.core.model.ProviderSyncHistory
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.ProviderType
import com.iptv.tv.core.model.RecordingStorageInfo
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
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
import java.io.InputStream
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
    private val okHttpClient: OkHttpClient,
    private val logoCatalogResolver: LogoCatalogResolver = LogoCatalogResolver()
) : PlaylistRepository {
    private val streamCheckClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .build()
    private val epgClient: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val epgCache = ConcurrentHashMap<String, EpgCacheEntry>()
    private val epgFailureBackoff = EpgFailureBackoffCache(MAX_EPG_FAILURE_CACHE_ENTRIES)
    private val epgLoadLock = Any()
    private val epgDiscoveryAttemptAtMs = ConcurrentHashMap<Long, Long>()

    override fun observePlaylists(): Flow<List<Playlist>> {
        return playlistDao.observePlaylistsWithCount().map { rows ->
            rows.map { row -> row.playlist.toModel(channelCount = row.channelCount) }
        }
    }

    override fun observeChannels(playlistId: Long): Flow<List<Channel>> {
        return channelDao.observeChannels(playlistId)
            .combine(observeParentalChannelGate()) { rows, parentalGate ->
                rows
                    .asSequence()
                    .filterNot { it.isBlockedByParental(parentalGate) }
                    .map { it.toModel() }
                    .toList()
            }
    }

    override suspend fun importFromUrl(
        url: String,
        name: String,
        catalogOrigin: CatalogOriginKind
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
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
                source = url,
                catalogOrigin = catalogOrigin
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
            val xmlTvUrl = normalizedBaseUrl.toHttpUrl()
                .newBuilder()
                .addPathSegment("xmltv.php")
                .addQueryParameter("username", normalizedUsername)
                .addQueryParameter("password", normalizedPassword)
                .build()
                .toString()
            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = rawPlaylist,
                sourceType = PlaylistSourceType.XTREAM,
                source = "$normalizedBaseUrl/player_api.php?username=${urlEncode(normalizedUsername)}&action=get_live_streams",
                epgSourceOverride = xmlTvUrl
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

    override suspend fun importFromHdHomeRun(baseUrl: String, name: String): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        val lineupUrl = normalizeHdHomeRunLineupUrl(baseUrl)
        if (lineupUrl.isBlank()) return@withContext AppResult.Error("HDHomeRun URL is empty")

        runCatching {
            val body = okHttpClient.newCall(Request.Builder().url(lineupUrl).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                .trim()
            if (!body.startsWith("[")) error("HDHomeRun lineup.json returned non-JSON array")

            val rawPlaylist = buildHdHomeRunM3u(JSONArray(body))
            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = rawPlaylist,
                sourceType = PlaylistSourceType.HDHOMERUN,
                source = lineupUrl
            )
        }.getOrElse { throwable ->
            AppResult.Error("Unable to import HDHomeRun: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
        }
    }

    override suspend fun importFromTvheadend(
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
                .applyBasicAuth(username, password)
                .build()
            val body = okHttpClient.newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                .trim()
            if (!body.startsWith("#EXTM3U")) error("Tvheadend returned non-M3U response")

            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = body,
                sourceType = PlaylistSourceType.TVHEADEND,
                source = playlistUrl
            )
        }.getOrElse { throwable ->
            AppResult.Error("Unable to import Tvheadend: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
        }
    }

    override suspend fun importFromJellyfin(
        baseUrl: String,
        apiKey: String,
        name: String
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedApiKey = apiKey.trim()
        if (normalizedBaseUrl.isBlank()) return@withContext AppResult.Error("Jellyfin server URL is empty")
        if (normalizedApiKey.isBlank()) return@withContext AppResult.Error("Jellyfin API key is empty")

        runCatching {
            val channelsUrl = jellyfinChannelsUrl(normalizedBaseUrl, normalizedApiKey)
            val body = okHttpClient.newCall(Request.Builder().url(channelsUrl).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                .trim()
            if (!body.startsWith("{")) error("Jellyfin returned non-JSON response")

            val channels = JSONObject(body).optJSONArray("Items") ?: JSONArray()
            val rawPlaylist = buildJellyfinM3u(
                baseUrl = normalizedBaseUrl,
                apiKey = normalizedApiKey,
                channels = channels
            )
            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = rawPlaylist,
                sourceType = PlaylistSourceType.JELLYFIN,
                source = "$normalizedBaseUrl/LiveTv/Channels"
            )
        }.getOrElse { throwable ->
            AppResult.Error("Unable to import Jellyfin: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
        }
    }

    override suspend fun importFromPlex(
        baseUrl: String,
        token: String,
        name: String
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        val normalizedBaseUrl = baseUrl.trim().trimEnd('/')
        val normalizedToken = token.trim()
        if (normalizedBaseUrl.isBlank()) return@withContext AppResult.Error("Plex server URL is empty")
        if (normalizedToken.isBlank()) return@withContext AppResult.Error("Plex token is empty")

        runCatching {
            val dvrs = loadPlexDvrs(normalizedBaseUrl, normalizedToken)
            if (dvrs.isEmpty()) error("Plex did not return Live TV/DVR devices")

            val channels = dvrs.flatMap { dvr ->
                val channelsXml = okHttpClient.newCall(
                    Request.Builder().url(plexDvrChannelsUrl(normalizedBaseUrl, normalizedToken, dvr)).build()
                )
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) error("HTTP ${response.code}")
                        response.body?.string().orEmpty()
                    }
                    .trim()
                if (!channelsXml.startsWith("<")) error("Plex returned non-XML channels response")
                parsePlexChannels(channelsXml, dvr)
            }
            val rawPlaylist = buildPlexM3u(
                baseUrl = normalizedBaseUrl,
                token = normalizedToken,
                channels = channels
            )
            importParsedPlaylist(
                playlistName = name,
                rawPlaylist = rawPlaylist,
                sourceType = PlaylistSourceType.PLEX,
                source = "$normalizedBaseUrl/livetv/dvrs"
            )
        }.getOrElse { throwable ->
            AppResult.Error("Unable to import Plex: ${throwable.toLogSummary(maxDepth = 4)}", throwable)
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

    override suspend fun importReadyPlaylistText(
        text: String,
        name: String,
        sourceKey: String
    ): AppResult<PlaylistImportReport> = withContext(Dispatchers.IO) {
        if (sourceKey.isBlank()) return@withContext AppResult.Error("Ready playlist source key is empty")
        importParsedPlaylist(
            playlistName = name,
            rawPlaylist = text,
            sourceType = PlaylistSourceType.TEXT,
            source = sourceKey,
            catalogOrigin = CatalogOriginKind.READY_CATALOG
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

        listOfNotNull(
            playlist.epgSourceUrl?.trim()?.ifBlank { null },
            normalized
        ).distinct().forEach { source ->
            epgCache.remove(source)
            epgFailureBackoff.remove(source)
        }

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
        if (channel.isBlockedByParental(currentParentalChannelGate())) {
            return@withContext AppResult.Error("Channel is blocked by parental control")
        }
        AppResult.Success(channel.toModel())
    }

    override suspend fun getPlaylistContentSummary(playlistId: Long): AppResult<PlaylistContentSummary> = withContext(Dispatchers.IO) {
        if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")
        val parentalGate = currentParentalChannelGate()
        val channels = channelDao.getChannels(playlistId)
            .asSequence()
            .filterNot { it.isBlockedByParental(parentalGate) }
            .map { it.toModel() }
            .toList()
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
        if (channelEntity.isBlockedByParental(currentParentalChannelGate())) {
            return@withContext AppResult.Error("Channel is blocked by parental control")
        }
        val playlist = playlistDao.findById(channelEntity.playlistId)
            ?: return@withContext AppResult.Error("Playlist not found: id=${channelEntity.playlistId}")

        val candidates = resolveEpgCandidates(playlist)
        if (candidates.isEmpty()) {
            return@withContext AppResult.Error(
                "EPG source URL is not configured and was not discovered for playlist ${playlist.id}"
            )
        }

        var lastLoadError: Throwable? = null
        for (epgUrl in candidates) {
            val epgData = try {
                getOrLoadXmlTv(epgUrl)
            } catch (throwable: Exception) {
                lastLoadError = throwable
                continue
            }
            val match = matchChannelToEpg(
                channelName = channelEntity.name,
                tvgId = channelEntity.tvgId,
                data = epgData
            )
            if (match.programs.isEmpty()) continue

            val now = System.currentTimeMillis()
            val nowProgram = match.programs.firstOrNull { it.startEpochMs <= now && now < it.endEpochMs }
            val nextProgram = match.programs.firstOrNull { it.startEpochMs > now }
            val upcoming = match.programs
                .filter { it.endEpochMs > now }
                .take(12)

            return@withContext AppResult.Success(
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

        if (lastLoadError != null && candidates.size == 1) {
            return@withContext AppResult.Error(
                "Unable to load EPG: ${lastLoadError!!.toLogSummary(maxDepth = 4)}",
                lastLoadError
            )
        }
        AppResult.Error(
            "EPG sources were checked, but channel '${channelEntity.name}' was not matched"
        )
    }

    override suspend fun getPlaylistEpgDiagnostics(playlistId: Long): AppResult<PlaylistEpgDiagnostics> =
        withContext(Dispatchers.IO) {
            if (playlistId <= 0) return@withContext AppResult.Error("Invalid playlist id")

            val playlist = playlistDao.findById(playlistId)
                ?: return@withContext AppResult.Error("Playlist not found: id=$playlistId")
            val candidates = resolveEpgCandidates(playlist)
            if (candidates.isEmpty()) {
                return@withContext AppResult.Error(
                    "EPG source URL is not configured and was not discovered for playlist ${playlist.id}"
                )
            }

            val parentalGate = currentParentalChannelGate()
            val channels = channelDao.getChannels(playlistId)
                .asSequence()
                .filter { !it.isHidden }
                .filterNot { it.isBlockedByParental(parentalGate) }
                .toList()

            var firstLoadedDiagnostics: PlaylistEpgDiagnostics? = null
            var lastLoadError: Throwable? = null
            for (epgUrl in candidates) {
                val epgData = try {
                    getOrLoadXmlTv(epgUrl)
                } catch (throwable: Exception) {
                    lastLoadError = throwable
                    continue
                }
                val diagnostics = EpgMatchDiagnosticsPolicy.summarize(
                    playlistId = playlistId,
                    epgSourceUrl = epgUrl,
                    sourceLoadedAtMs = epgCache[epgUrl]?.loadedAtMs,
                    observations = channels.map { channel ->
                        val match = matchChannelToEpg(
                            channelName = channel.name,
                            tvgId = channel.tvgId,
                            data = epgData
                        )
                        EpgMatchObservation(
                            matchedBy = match.matchedBy,
                            hasPrograms = match.programs.isNotEmpty()
                        )
                    }
                )
                if (firstLoadedDiagnostics == null) firstLoadedDiagnostics = diagnostics
                if (diagnostics.channelsWithPrograms > 0) {
                    return@withContext AppResult.Success(diagnostics)
                }
            }

            firstLoadedDiagnostics?.let { return@withContext AppResult.Success(it) }
            if (lastLoadError != null) {
                return@withContext AppResult.Error(
                    "Unable to load EPG diagnostics: ${lastLoadError!!.toLogSummary(maxDepth = 4)}",
                    lastLoadError
                )
            }
            AppResult.Error("EPG diagnostics are unavailable for playlist $playlistId")
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
        val candidates = resolveEpgCandidates(playlist)
        if (candidates.isEmpty()) {
            return@withContext AppResult.Error(
                "EPG source URL is not configured and was not discovered for playlist ${playlist.id}"
            )
        }

        val parentalGate = currentParentalChannelGate()
        val channels = channelDao.getChannels(playlistId)
            .asSequence()
            .filter { !it.isHidden }
            .filterNot { it.isBlockedByParental(parentalGate) }
            .toList()
        var lastLoadError: Throwable? = null

        for (epgUrl in candidates) {
            val epgData = try {
                getOrLoadXmlTv(epgUrl)
            } catch (throwable: Exception) {
                lastLoadError = throwable
                continue
            }
            val result = channels
                .asSequence()
                .mapNotNull { channel ->
                    val match = matchChannelToEpg(
                        channelName = channel.name,
                        tvgId = channel.tvgId,
                        data = epgData
                    )
                    val programs = EpgProgramWindowIndex.selectWindow(
                        programs = match.programs,
                        startEpochMs = startEpochMs,
                        endEpochMs = endEpochMs,
                        query = query,
                        channelName = channel.name
                    )
                    if (programs.isEmpty()) null else channel.id to programs
                }
                .toMap()
            if (result.isNotEmpty()) return@withContext AppResult.Success(result)
        }

        if (lastLoadError != null && candidates.size == 1) {
            return@withContext AppResult.Error(
                "Unable to load EPG: ${lastLoadError!!.toLogSummary(maxDepth = 4)}",
                lastLoadError
            )
        }
        AppResult.Success(emptyMap())
    }

    private fun observeParentalChannelGate(): Flow<ParentalChannelGate> {
        return context.settingsDataStore.data.map { prefs ->
            ParentalChannelGate(
                enabled = prefs[SettingsKeys.parentalEnabled] ?: false,
                hideAdultChannels = prefs[SettingsKeys.parentalHideAdultChannels] ?: true,
                blockedKeywords = ParentalChannelFilter.decodeKeywords(
                    prefs[SettingsKeys.parentalBlockedKeywords]
                )
            )
        }
    }

    private suspend fun currentParentalChannelGate(): ParentalChannelGate {
        return observeParentalChannelGate().first()
    }

    private fun ChannelEntity.isBlockedByParental(gate: ParentalChannelGate): Boolean {
        return ParentalChannelFilter.isBlocked(
            name = name,
            groupName = groupName,
            tvgId = tvgId,
            gate = gate
        )
    }

    private suspend fun inheritGlobalFavorites(channels: List<ChannelEntity>) {
        if (channels.isEmpty()) return

        val favoriteChannelIds = favoriteDao.getFavorites()
            .map { it.channelId }
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

    private suspend fun importParsedPlaylist(
        playlistName: String,
        rawPlaylist: String,
        sourceType: PlaylistSourceType,
        source: String,
        epgSourceOverride: String? = null,
        catalogOrigin: CatalogOriginKind = defaultCatalogOrigin(sourceType)
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
                        channel.copy(logo = resolveLogoFromCatalog(channel, source))
                    }
                }
                val playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = playlistName,
                        sourceType = sourceType.name,
                        source = source,
                        epgSourceUrl = epgSourceOverride?.trim()?.ifBlank { null }
                            ?: parsed.epgUrls.firstOrNull(),
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
                prepared
                    .chunked(DB_INSERT_CHUNK)
                    .forEach { chunk -> channelDao.insertAll(chunk.map { it.toEntity() }) }

                val storedChannels = channelDao.getChannels(playlistId)
                inheritGlobalFavorites(storedChannels)
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

    private fun defaultCatalogOrigin(sourceType: PlaylistSourceType): CatalogOriginKind = when (sourceType) {
        PlaylistSourceType.XTREAM,
        PlaylistSourceType.STALKER,
        PlaylistSourceType.JELLYFIN,
        PlaylistSourceType.PLEX,
        PlaylistSourceType.TVHEADEND,
        PlaylistSourceType.HDHOMERUN -> CatalogOriginKind.PROVIDER
        PlaylistSourceType.FILE -> CatalogOriginKind.LOCAL
        else -> CatalogOriginKind.USER_IMPORT
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

    private fun buildHdHomeRunM3u(channels: JSONArray): String {
        val builder = StringBuilder("#EXTM3U\n")
        for (index in 0 until channels.length()) {
            val item = channels.optJSONObject(index) ?: continue
            val name = item.optString("GuideName").trim()
                .ifBlank { item.optString("Name").trim() }
            val streamUrl = item.optString("URL").trim()
            if (name.isBlank() || streamUrl.isBlank()) continue

            val guideNumber = item.optString("GuideNumber").trim()
            val guideName = item.optString("GuideName").trim()

            builder
                .append("#EXTINF:-1")
                .appendM3uAttribute("tvg-id", guideNumber)
                .appendM3uAttribute("tvg-name", guideName.ifBlank { name })
                .appendM3uAttribute("group-title", "HDHomeRun")
                .append(',')
                .append(name)
                .append('\n')
                .append(streamUrl)
                .append('\n')
        }
        return builder.toString()
    }

    private fun buildJellyfinM3u(
        baseUrl: String,
        apiKey: String,
        channels: JSONArray
    ): String {
        val builder = StringBuilder("#EXTM3U\n")
        for (index in 0 until channels.length()) {
            val item = channels.optJSONObject(index) ?: continue
            val id = item.optString("Id").trim()
            val name = item.optString("Name").trim()
            if (id.isBlank() || name.isBlank()) continue

            val channelNumber = item.optString("ChannelNumber").trim()
                .ifBlank { item.optString("Number").trim() }
            val logo = jellyfinLogoUrl(baseUrl, apiKey, item)
            val streamUrl = jellyfinStreamUrl(baseUrl, apiKey, id)

            builder
                .append("#EXTINF:-1")
                .appendM3uAttribute("tvg-id", id)
                .appendM3uAttribute("tvg-name", name)
                .appendM3uAttribute("tvg-logo", logo)
                .appendM3uAttribute("group-title", "Jellyfin")
                .appendM3uAttribute("channel-number", channelNumber)
                .append(',')
                .append(name)
                .append('\n')
                .append(streamUrl)
                .append('\n')
        }
        return builder.toString()
    }

    private fun buildPlexM3u(
        baseUrl: String,
        token: String,
        channels: List<PlexChannel>
    ): String {
        val builder = StringBuilder("#EXTM3U\n")
        channels.forEach { channel ->
            val name = channel.name.ifBlank { channel.id }
            val streamUrl = plexChannelTuneUrl(baseUrl, token, channel)
            if (name.isBlank() || streamUrl.isBlank()) return@forEach

            builder
                .append("#EXTINF:-1")
                .appendM3uAttribute("tvg-id", channel.identifier.ifBlank { channel.id })
                .appendM3uAttribute("tvg-name", name)
                .appendM3uAttribute("tvg-logo", plexAbsoluteUrl(baseUrl, token, channel.logoPath))
                .appendM3uAttribute("group-title", "Plex")
                .appendM3uAttribute("channel-number", channel.number)
                .append(',')
                .append(name)
                .append('\n')
                .append(streamUrl)
                .append('\n')
        }
        return builder.toString()
    }

    private fun jellyfinChannelsUrl(baseUrl: String, apiKey: String): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("LiveTv")
            .addPathSegment("Channels")
            .addQueryParameter("api_key", apiKey)
            .build()
            .toString()
    }

    private fun jellyfinStreamUrl(baseUrl: String, apiKey: String, channelId: String): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("LiveTv")
            .addPathSegment("Channels")
            .addPathSegment(channelId)
            .addPathSegment("Stream")
            .addQueryParameter("static", "true")
            .addQueryParameter("api_key", apiKey)
            .build()
            .toString()
    }

    private fun jellyfinLogoUrl(baseUrl: String, apiKey: String, item: JSONObject): String {
        val id = item.optString("Id").trim()
        val primaryTag = item.optJSONObject("ImageTags")?.optString("Primary").orEmpty().trim()
            .ifBlank { item.optString("PrimaryImageTag").trim() }
        if (id.isBlank() || primaryTag.isBlank()) return ""
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("Items")
            .addPathSegment(id)
            .addPathSegment("Images")
            .addPathSegment("Primary")
            .addQueryParameter("tag", primaryTag)
            .addQueryParameter("api_key", apiKey)
            .build()
            .toString()
    }

    private fun loadPlexDvrs(baseUrl: String, token: String): List<PlexDvr> {
        val body = okHttpClient.newCall(Request.Builder().url(plexDvrsUrl(baseUrl, token)).build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            .trim()
        if (!body.startsWith("<")) error("Plex returned non-XML DVR response")
        return parsePlexDvrs(body)
    }

    private fun plexDvrsUrl(baseUrl: String, token: String): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("livetv")
            .addPathSegment("dvrs")
            .addQueryParameter("X-Plex-Token", token)
            .build()
            .toString()
    }

    private fun plexDvrChannelsUrl(baseUrl: String, token: String, dvr: PlexDvr): String {
        val keyPath = dvr.key.takeIf { it.startsWith("/") }
        val path = keyPath?.trimEnd('/')?.plus("/channels")
            ?: "/livetv/dvrs/${dvr.id}/channels"
        return plexPathUrl(baseUrl, token, path)
    }

    private fun plexChannelTuneUrl(baseUrl: String, token: String, channel: PlexChannel): String {
        val keyPath = channel.key.takeIf { it.startsWith("/") }
        val path = keyPath?.trimEnd('/')?.plus("/tune")
            ?: "/livetv/dvrs/${channel.dvr.id}/channels/${channel.id}/tune"
        return plexPathUrl(baseUrl, token, path)
    }

    private fun plexPathUrl(baseUrl: String, token: String, path: String): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .encodedPath(path)
            .addQueryParameter("X-Plex-Token", token)
            .build()
            .toString()
    }

    private fun plexAbsoluteUrl(baseUrl: String, token: String, rawPath: String): String {
        if (rawPath.isBlank()) return ""
        if (rawPath.startsWith("http://", ignoreCase = true) || rawPath.startsWith("https://", ignoreCase = true)) {
            return rawPath
        }
        return plexPathUrl(baseUrl, token, rawPath)
    }

    private fun parsePlexDvrs(xml: String): List<PlexDvr> {
        val result = mutableListOf<PlexDvr>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), StandardCharsets.UTF_8.name())
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("Dvr", ignoreCase = true)) {
                val id = parser.attr("uuid")
                    .ifBlank { parser.attr("id") }
                    .ifBlank { parser.attr("key").trim('/') }
                if (id.isNotBlank()) {
                    result += PlexDvr(
                        id = id,
                        key = parser.attr("key"),
                        title = parser.attr("title").ifBlank { parser.attr("name") }
                    )
                }
            }
            event = parser.next()
        }
        return result.distinctBy { it.id }
    }

    private fun parsePlexChannels(xml: String, dvr: PlexDvr): List<PlexChannel> {
        val result = mutableListOf<PlexChannel>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), StandardCharsets.UTF_8.name())
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("Channel", ignoreCase = true)) {
                val id = parser.attr("id")
                    .ifBlank { parser.attr("key").substringAfterLast('/') }
                    .ifBlank { parser.attr("channelIdentifier") }
                val name = parser.attr("title")
                    .ifBlank { parser.attr("name") }
                    .ifBlank { parser.attr("callSign") }
                if (id.isNotBlank() && name.isNotBlank()) {
                    result += PlexChannel(
                        dvr = dvr,
                        id = id,
                        key = parser.attr("key"),
                        identifier = parser.attr("channelIdentifier"),
                        name = name,
                        number = parser.attr("channelNumber").ifBlank { parser.attr("number") },
                        logoPath = parser.attr("thumb").ifBlank { parser.attr("art") }
                    )
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun XmlPullParser.attr(name: String): String {
        return getAttributeValue(null, name).orEmpty().trim()
    }

    private fun normalizeHdHomeRunLineupUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return ""
        val url = trimmed.toHttpUrl()
        if (url.encodedPath.endsWith("/lineup.json", ignoreCase = true)) {
            return url.toString()
        }
        return url.newBuilder()
            .encodedPath(url.encodedPath.trimEnd('/') + "/lineup.json")
            .query(null)
            .build()
            .toString()
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

    private fun Request.Builder.applyBasicAuth(username: String?, password: String?): Request.Builder {
        val normalizedUsername = username?.trim().orEmpty()
        val normalizedPassword = password?.trim().orEmpty()
        if (normalizedUsername.isNotBlank() || normalizedPassword.isNotBlank()) {
            header("Authorization", Credentials.basic(normalizedUsername, normalizedPassword))
        }
        return this
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

    private fun resolveLogoFromCatalog(channel: Channel, playlistSource: String): String? {
        return logoCatalogResolver.resolve(
            name = channel.name,
            tvgId = channel.tvgId,
            playlistSource = playlistSource
        )?.url
    }

    private suspend fun resolveEpgCandidates(playlist: PlaylistEntity): List<String> {
        val own = playlist.epgSourceUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: discoverEpgSourceFromPlaylist(playlist)
        val shared = playlistDao.getPlaylistsWithEpgSource()
            .asSequence()
            .filter { it.id != playlist.id }
            .mapNotNull { it.epgSourceUrl?.trim()?.takeIf(String::isNotBlank) }
        return sequenceOf(own)
            .plus(shared)
            .filterNotNull()
            .distinct()
            .take(MAX_EPG_SOURCE_CANDIDATES)
            .toList()
    }

    private suspend fun discoverEpgSourceFromPlaylist(playlist: PlaylistEntity): String? {
        if (!playlist.sourceType.equals(PlaylistSourceType.URL.name, ignoreCase = true)) return null
        val sourceUrl = playlist.source.trim()
        if (!sourceUrl.startsWith("http://", true) && !sourceUrl.startsWith("https://", true)) return null
        val now = System.currentTimeMillis()
        val lastAttempt = epgDiscoveryAttemptAtMs[playlist.id] ?: 0L
        if (now - lastAttempt < EPG_DISCOVERY_RETRY_MS) return null
        epgDiscoveryAttemptAtMs[playlist.id] = now

        val discovered = runCatching {
            val body = okHttpClient.newCall(Request.Builder().url(sourceUrl).get().build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
            when (val parsed = parser.parse(playlistId = playlist.id, raw = body)) {
                is ParseResult.Valid -> parsed.epgUrls.firstOrNull()
                is ParseResult.Invalid -> null
            }
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

        if (discovered != null) {
            playlistDao.updateEpgSourceUrl(playlist.id, discovered)
            syncLogDao.insert(
                SyncLogEntity(
                    playlistId = playlist.id,
                    status = "epg_source_discovered",
                    message = "EPG source discovered from playlist header: $discovered",
                    createdAt = now
                )
            )
        }
        return discovered
    }

    private fun getOrLoadXmlTv(url: String): XmlTvData {
        val now = System.currentTimeMillis()
        cachedEpgData(url, now)?.let { return it }
        epgFailureBackoff.active(url)?.let { failure ->
            throw IOException("EPG temporarily unavailable: ${failure.reason}")
        }

        return synchronized(epgLoadLock) {
            val lockedNow = System.currentTimeMillis()
            cachedEpgData(url, lockedNow)?.let { return@synchronized it }
            epgFailureBackoff.active(url)?.let { failure ->
                throw IOException("EPG temporarily unavailable: ${failure.reason}")
            }

            purgeExpiredEpgCache(lockedNow)

            try {
                ensureEpgHeapHeadroom(EPG_MIN_START_HEADROOM_BYTES)
                val parsed = epgClient.newCall(
                    Request.Builder()
                        .url(url)
                        .get()
                        .build()
                ).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty EPG body")
                    val contentLength = body.contentLength()
                    if (contentLength > MAX_EPG_INPUT_BYTES) {
                        throw EpgInputLimitExceededException(
                            maxBytes = MAX_EPG_INPUT_BYTES,
                            observedBytes = contentLength
                        )
                    }
                    EpgBoundedInputStream(
                        input = body.byteStream(),
                        maxBytes = MAX_EPG_INPUT_BYTES
                    ).use(::parseXmlTv)
                }

                epgFailureBackoff.remove(url)
                putEpgCache(
                    url = url,
                    entry = EpgCacheEntry(
                        loadedAtMs = System.currentTimeMillis(),
                        data = parsed
                    )
                )
                parsed
            } catch (_: OutOfMemoryError) {
                epgCache.clear()
                epgFailureBackoff.record(
                    url = url,
                    reason = "EPG aborted because heap headroom was exhausted",
                    retryAfterMs = EPG_LOW_MEMORY_BACKOFF_MS
                )
                throw IOException("EPG deferred: insufficient heap headroom")
            } catch (throwable: Exception) {
                val failure = throwable as? IOException
                    ?: IOException("Unable to load EPG: ${throwable.message ?: throwable.javaClass.simpleName}")
                epgFailureBackoff.record(
                    url = url,
                    reason = failure.message ?: failure.javaClass.simpleName,
                    retryAfterMs = epgFailureBackoffMs(failure)
                )
                throw failure
            }
        }
    }

    private fun cachedEpgData(url: String, now: Long): XmlTvData? {
        val cached = epgCache[url] ?: return null
        if (now - cached.loadedAtMs <= EPG_CACHE_TTL_MS) return cached.data
        epgCache.remove(url, cached)
        return null
    }

    private fun purgeExpiredEpgCache(now: Long) {
        epgCache.entries.forEach { entry ->
            if (now - entry.value.loadedAtMs > EPG_CACHE_TTL_MS) {
                epgCache.remove(entry.key, entry.value)
            }
        }
    }

    private fun putEpgCache(url: String, entry: EpgCacheEntry) {
        while (epgCache.size >= MAX_EPG_CACHE_ENTRIES && !epgCache.containsKey(url)) {
            val oldest = epgCache.entries.minByOrNull { it.value.loadedAtMs } ?: break
            epgCache.remove(oldest.key, oldest.value)
        }
        epgCache[url] = entry
    }

    private fun epgFailureBackoffMs(failure: IOException): Long {
        val message = failure.message.orEmpty().lowercase(Locale.ROOT)
        return when {
            failure is EpgInputLimitExceededException -> EPG_MALFORMED_BACKOFF_MS
            "invalid xmltv" in message || "xmlpullparser" in message -> EPG_MALFORMED_BACKOFF_MS
            "heap headroom" in message || "insufficient heap" in message -> EPG_LOW_MEMORY_BACKOFF_MS
            message.startsWith("http 4") -> EPG_HTTP_BACKOFF_MS
            else -> EPG_TRANSIENT_BACKOFF_MS
        }
    }

    private fun heapHeadroomBytes(): Long {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        return (runtime.maxMemory() - used).coerceAtLeast(0L)
    }

    private fun ensureEpgHeapHeadroom(minHeadroomBytes: Long) {
        val headroom = heapHeadroomBytes()
        if (headroom < minHeadroomBytes) {
            throw IOException(
                "EPG deferred: low heap headroom ${headroom / MIB}MiB, required ${minHeadroomBytes / MIB}MiB"
            )
        }
    }

    private fun parseXmlTv(input: InputStream): XmlTvData {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(input, Charsets.UTF_8.name())
        }

        val channelDisplayNames = mutableMapOf<String, MutableSet<String>>()
        val declaredChannelIds = linkedSetOf<String>()
        val programsByChannel = mutableMapOf<String, MutableList<EpgProgram>>()
        val now = System.currentTimeMillis()
        val retainFromMs = now - EPG_RETAIN_PAST_MS
        val retainUntilMs = now + EPG_RETAIN_FUTURE_MS
        var storedPrograms = 0

        try {
            var event = parser.eventType
            var currentChannelId: String? = null
            var currentProgrammeChannel: String? = null
            var currentProgrammeTitle: String? = null
            var currentProgrammeDesc: String? = null
            var currentProgrammeCategory: String? = null
            var currentProgrammeStart: Long? = null
            var currentProgrammeStop: Long? = null
            var collectCurrentProgramme = false

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "channel" -> {
                                val id = parser.getAttributeValue(null, "id")
                                    ?.trim()
                                    ?.take(MAX_EPG_CHANNEL_ID_CHARS)
                                    ?.takeIf { it.isNotEmpty() }
                                currentChannelId = id?.takeIf { channelId ->
                                    channelId in declaredChannelIds || declaredChannelIds.size < MAX_EPG_CHANNELS
                                }
                                currentChannelId?.let(declaredChannelIds::add)
                            }
                            "programme" -> {
                                val channel = parser.getAttributeValue(null, "channel")
                                    ?.trim()
                                    ?.take(MAX_EPG_CHANNEL_ID_CHARS)
                                    ?.takeIf { it.isNotEmpty() }
                                val start = parseXmlTvTime(parser.getAttributeValue(null, "start"))
                                val stop = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
                                val existingCount = channel?.let { programsByChannel[it]?.size } ?: 0
                                val channelCapacityAvailable = channel != null &&
                                    (channel in programsByChannel || programsByChannel.size < MAX_EPG_CHANNELS)
                                collectCurrentProgramme = channelCapacityAvailable &&
                                    start != null &&
                                    stop != null &&
                                    stop > retainFromMs &&
                                    start < retainUntilMs &&
                                    storedPrograms < MAX_EPG_PROGRAMS_TOTAL &&
                                    existingCount < MAX_EPG_PROGRAMS_PER_CHANNEL
                                currentProgrammeChannel = channel.takeIf { collectCurrentProgramme }
                                currentProgrammeStart = start
                                currentProgrammeStop = stop
                                currentProgrammeTitle = null
                                currentProgrammeDesc = null
                                currentProgrammeCategory = null
                            }
                            "display-name" -> {
                                val channelId = currentChannelId
                                if (channelId != null) {
                                    val names = channelDisplayNames.getOrPut(channelId) { linkedSetOf() }
                                    if (names.size < MAX_EPG_DISPLAY_NAMES_PER_CHANNEL) {
                                        val value = readBoundedXmlText(parser, MAX_EPG_DISPLAY_NAME_CHARS)
                                        if (value.isNotBlank()) names += value
                                    } else {
                                        skipCurrentXmlElement(parser)
                                    }
                                } else {
                                    skipCurrentXmlElement(parser)
                                }
                            }
                            "title" -> {
                                if (collectCurrentProgramme) {
                                    currentProgrammeTitle = readBoundedXmlText(parser, MAX_EPG_TITLE_CHARS)
                                        .takeIf { it.isNotBlank() }
                                } else {
                                    skipCurrentXmlElement(parser)
                                }
                            }
                            "desc" -> {
                                if (collectCurrentProgramme) {
                                    currentProgrammeDesc = readBoundedXmlText(parser, MAX_EPG_DESCRIPTION_CHARS)
                                        .takeIf { it.isNotBlank() }
                                } else {
                                    skipCurrentXmlElement(parser)
                                }
                            }
                            "category" -> {
                                if (collectCurrentProgramme) {
                                    currentProgrammeCategory = readBoundedXmlText(parser, MAX_EPG_CATEGORY_CHARS)
                                        .takeIf { it.isNotBlank() }
                                } else {
                                    skipCurrentXmlElement(parser)
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
                                if (
                                    collectCurrentProgramme &&
                                    !channel.isNullOrBlank() &&
                                    start != null &&
                                    stop != null &&
                                    !title.isNullOrBlank()
                                ) {
                                    val items = programsByChannel.getOrPut(channel) { mutableListOf() }
                                    if (
                                        items.size < MAX_EPG_PROGRAMS_PER_CHANNEL &&
                                        storedPrograms < MAX_EPG_PROGRAMS_TOTAL
                                    ) {
                                        items += EpgProgram(
                                            title = title,
                                            description = currentProgrammeDesc,
                                            category = currentProgrammeCategory,
                                            startEpochMs = start,
                                            endEpochMs = stop
                                        )
                                        storedPrograms += 1
                                        if (storedPrograms % EPG_HEAP_CHECK_PROGRAM_INTERVAL == 0) {
                                            ensureEpgHeapHeadroom(EPG_MIN_PARSE_HEADROOM_BYTES)
                                        }
                                    }
                                }
                                currentProgrammeChannel = null
                                currentProgrammeStart = null
                                currentProgrammeStop = null
                                currentProgrammeTitle = null
                                currentProgrammeDesc = null
                                currentProgrammeCategory = null
                                collectCurrentProgramme = false
                            }
                        }
                    }
                }
                event = parser.next()
            }
        } catch (throwable: XmlPullParserException) {
            throw IOException("Invalid XMLTV format: ${throwable.message}", throwable)
        }

        programsByChannel.values.forEach(::sortAndDeduplicateProgramsInPlace)
        val knownChannelIds = EpgXmlTvChannelIndexPolicy.knownChannelIds(
            declaredChannelIds = declaredChannelIds,
            programmedChannelIds = programsByChannel.keys
        )
        val channelIdByLowercase = knownChannelIds
            .associateByFirst { it.trim().lowercase(Locale.ROOT) }
        val channelIdByTextKey = knownChannelIds
            .associateByFirst { normalizeTextKey(it) }
        val channelIdsByTextKey = knownChannelIds.mapNotNull { channelId ->
            normalizeTextKey(channelId)
                .takeIf { it.isNotBlank() }
                ?.let { normalizedKey -> normalizedKey to channelId }
        }
        val channelIdByDisplayNameKey = channelDisplayNames
            .entries
            .asSequence()
            .flatMap { (channelId, names) ->
                names.asSequence().map { displayName -> normalizeTextKey(displayName) to channelId }
            }
            .filter { (key, _) -> key.isNotBlank() }
            .associateFirst()

        return XmlTvData(
            channelDisplayNames = channelDisplayNames,
            programsByChannel = programsByChannel,
            channelIdByLowercase = channelIdByLowercase,
            channelIdByTextKey = channelIdByTextKey,
            channelIdsByTextKey = channelIdsByTextKey,
            channelIdByDisplayNameKey = channelIdByDisplayNameKey
        )
    }

    private fun readBoundedXmlText(parser: XmlPullParser, maxChars: Int): String {
        val startDepth = parser.depth
        val result = StringBuilder(minOf(maxChars, 128))
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == startDepth) break
            if (
                event == XmlPullParser.TEXT ||
                event == XmlPullParser.CDSECT ||
                event == XmlPullParser.ENTITY_REF ||
                event == XmlPullParser.IGNORABLE_WHITESPACE
            ) {
                val value = parser.text.orEmpty()
                val remaining = maxChars - result.length
                if (remaining > 0 && value.isNotEmpty()) {
                    result.append(value, 0, minOf(remaining, value.length))
                }
            }
            event = parser.next()
        }
        return result.toString().trim()
    }

    private fun skipCurrentXmlElement(parser: XmlPullParser) {
        val startDepth = parser.depth
        var event = parser.next()
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.END_TAG && parser.depth == startDepth) return
            event = parser.next()
        }
    }

    private fun sortAndDeduplicateProgramsInPlace(items: MutableList<EpgProgram>) {
        if (items.size <= 1) return
        items.sortBy { it.startEpochMs }
        var writeIndex = 0
        var previous: EpgProgram? = null
        for (readIndex in items.indices) {
            val item = items[readIndex]
            val duplicate = previous?.let { prior ->
                prior.startEpochMs == item.startEpochMs &&
                    prior.endEpochMs == item.endEpochMs &&
                    prior.title == item.title
            } == true
            if (!duplicate) {
                if (writeIndex != readIndex) items[writeIndex] = item
                writeIndex += 1
                previous = item
            }
        }
        while (items.size > writeIndex) {
            items.removeAt(items.lastIndex)
        }
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
            val channelId = data.channelIdByLowercase[normalizedTvgId]
            if (channelId != null) {
                return EpgMatch(programs = data.programsByChannel[channelId].orEmpty(), matchedBy = "tvg-id")
            }
        }

        val normalizedName = normalizeTextKey(channelName)
        if (normalizedName.isNotBlank()) {
            val displayNameChannelId = data.channelIdByDisplayNameKey[normalizedName]
            if (displayNameChannelId != null) {
                val programs = data.programsByChannel[displayNameChannelId].orEmpty()
                return EpgMatch(programs = programs, matchedBy = "display-name")
            }

            val exactChannelId = data.channelIdByTextKey[normalizedName]
            if (exactChannelId != null) {
                return EpgMatch(programs = data.programsByChannel[exactChannelId].orEmpty(), matchedBy = "channel-id")
            }

            val partialChannelId = EpgChannelMatchPolicy.uniquePartialChannelId(
                normalizedChannelName = normalizedName,
                channelIdsByTextKey = data.channelIdsByTextKey
            )
            if (partialChannelId != null) {
                return EpgMatch(programs = data.programsByChannel[partialChannelId].orEmpty(), matchedBy = "channel-id")
            }
        }

        return EpgMatch(programs = emptyList(), matchedBy = "not-matched")
    }

    private fun <T> Iterable<T>.associateByFirst(keySelector: (T) -> String): Map<String, T> {
        val result = linkedMapOf<String, T>()
        forEach { value ->
            val key = keySelector(value)
            if (key.isNotBlank() && key !in result) {
                result[key] = value
            }
        }
        return result
    }

    private fun Sequence<Pair<String, String>>.associateFirst(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        forEach { (key, value) ->
            if (key.isNotBlank() && key !in result) {
                result[key] = value
            }
        }
        return result
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
        val programsByChannel: Map<String, List<EpgProgram>>,
        val channelIdByLowercase: Map<String, String>,
        val channelIdByTextKey: Map<String, String>,
        val channelIdsByTextKey: List<Pair<String, String>>,
        val channelIdByDisplayNameKey: Map<String, String>
    )

    private data class EpgMatch(
        val programs: List<EpgProgram>,
        val matchedBy: String
    )

    private data class PlexDvr(
        val id: String,
        val key: String,
        val title: String
    )

    private data class PlexChannel(
        val dvr: PlexDvr,
        val id: String,
        val key: String,
        val identifier: String,
        val name: String,
        val number: String,
        val logoPath: String
    )

    private companion object {
        const val DB_INSERT_CHUNK = 500
        const val AUTO_HEALTH_CHECK_LIMIT = 200
        const val HEALTH_CHECK_CONCURRENCY = 20
        const val HEALTH_CHECK_RETRIES = 2
        const val HEALTH_RETRY_DELAY_MS = 450L
        const val EPG_CACHE_TTL_MS = 15 * 60 * 1000L
        const val EPG_DISCOVERY_RETRY_MS = 10 * 60 * 1000L
        const val MAX_EPG_SOURCE_CANDIDATES = 4
        const val MAX_EPG_CACHE_ENTRIES = 1
        const val MAX_EPG_FAILURE_CACHE_ENTRIES = 12
        const val MIB = 1024L * 1024L
        const val MAX_EPG_INPUT_BYTES = 64L * MIB
        const val EPG_MIN_START_HEADROOM_BYTES = 48L * MIB
        const val EPG_MIN_PARSE_HEADROOM_BYTES = 24L * MIB
        const val EPG_LOW_MEMORY_BACKOFF_MS = 30 * 1000L
        const val EPG_TRANSIENT_BACKOFF_MS = 2 * 60 * 1000L
        const val EPG_HTTP_BACKOFF_MS = 5 * 60 * 1000L
        const val EPG_MALFORMED_BACKOFF_MS = 10 * 60 * 1000L
        const val EPG_RETAIN_PAST_MS = 12 * 60 * 60 * 1000L
        const val EPG_RETAIN_FUTURE_MS = 96 * 60 * 60 * 1000L
        const val MAX_EPG_CHANNELS = 5_000
        const val MAX_EPG_PROGRAMS_TOTAL = 30_000
        const val MAX_EPG_PROGRAMS_PER_CHANNEL = 192
        const val MAX_EPG_DISPLAY_NAMES_PER_CHANNEL = 4
        const val MAX_EPG_CHANNEL_ID_CHARS = 256
        const val MAX_EPG_DISPLAY_NAME_CHARS = 256
        const val MAX_EPG_TITLE_CHARS = 256
        const val MAX_EPG_DESCRIPTION_CHARS = 1_024
        const val MAX_EPG_CATEGORY_CHARS = 128
        const val EPG_HEAP_CHECK_PROGRAM_INTERVAL = 256
        const val STALKER_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; MAG200; en-US) AppleWebKit/533.3"
        val XMLTV_TIME_REGEX =
            Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+\\-]\\d{4})?.*$")
        val STALKER_MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
        val RETRIABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
    }
}

@Singleton
class ProviderAccountRepositoryImpl @Inject constructor(
    private val providerDao: PlaylistProviderDao,
    private val providerSyncHistoryDao: ProviderSyncHistoryDao,
    private val playlistRepository: PlaylistRepository,
    private val syncLogDao: SyncLogDao,
    private val secretCipher: ProviderSecretCipher,
    private val okHttpClient: OkHttpClient
) : ProviderAccountRepository {
    override fun observeProviders(): Flow<List<PlaylistProvider>> {
        return providerDao.observeProviders().map { rows ->
            rows.map { it.toModel().toProviderDisplayModel() }
        }
    }

    override fun observeSyncHistory(limit: Int): Flow<List<ProviderSyncHistory>> {
        return providerSyncHistoryDao.observeRecent(limit.coerceIn(1, PROVIDER_SYNC_HISTORY_LIMIT)).map { rows ->
            rows.map { it.toModel() }
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
                ProviderType.HDHOMERUN -> checkHdHomeRunProvider(provider)
                ProviderType.TVHEADEND -> checkTvheadendProvider(provider)
                ProviderType.JELLYFIN -> checkJellyfinProvider(provider)
                ProviderType.PLEX -> checkPlexProvider(provider)
                else -> ProviderAccountStatus(
                    providerId = provider.id,
                    type = provider.type,
                    ok = false,
                    statusText = "Не поддержано",
                    detail = "${provider.type} status check is not implemented yet",
                    checkedAt = System.currentTimeMillis(),
                    diagnosticKind = ProviderDiagnosticKind.UNSUPPORTED,
                    hint = "Для этого типа провайдера проверка статуса пока не реализована.",
                    testedUrl = provider.baseUrl
                )
            }
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = {
                AppResult.Success(
                    buildProviderFailureStatus(
                        provider = provider,
                        testedUrl = provider.baseUrl,
                        message = it.toLogSummary(4)
                    )
                )
            }
        )
    }

    override suspend fun syncProvider(providerId: Long): AppResult<Long> = withContext(Dispatchers.IO) {
        val provider = runCatching {
            providerDao.findById(providerId)?.toModel()?.withDecryptedSecrets()
        }.getOrElse { throwable ->
            return@withContext AppResult.Error("Unable to decrypt provider secrets: ${throwable.toLogSummary(4)}", throwable)
        } ?: return@withContext AppResult.Error("Provider not found")
        addProviderSyncLog(
            status = "provider_sync_item_start",
            provider = provider,
            message = "providerId=${provider.id}, type=${provider.type}, name=${provider.name}"
        )
        addProviderSyncHistory(
            provider = provider,
            status = "provider_sync_item_start",
            playlistId = null,
            reason = null,
            detail = "Старт синхронизации"
        )
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
                name = provider.name,
                catalogOrigin = CatalogOriginKind.PROVIDER
            )
            ProviderType.HDHOMERUN -> playlistRepository.importFromHdHomeRun(
                baseUrl = provider.baseUrl,
                name = provider.name
            )
            ProviderType.TVHEADEND -> playlistRepository.importFromTvheadend(
                baseUrl = provider.baseUrl,
                username = provider.username,
                password = provider.password,
                name = provider.name
            )
            ProviderType.JELLYFIN -> playlistRepository.importFromJellyfin(
                baseUrl = provider.baseUrl,
                apiKey = provider.token.orEmpty(),
                name = provider.name
            )
            ProviderType.PLEX -> playlistRepository.importFromPlex(
                baseUrl = provider.baseUrl,
                token = provider.token.orEmpty(),
                name = provider.name
            )
            else -> {
                addProviderSyncHistory(
                    provider = provider,
                    status = "provider_sync_item_error",
                    playlistId = provider.linkedPlaylistId,
                    reason = ProviderDiagnosticKind.UNSUPPORTED,
                    detail = "${provider.type} sync is not implemented yet"
                )
                return@withContext AppResult.Error("${provider.type} sync is not implemented yet")
            }
        }
        when (result) {
            is AppResult.Success -> {
                providerDao.markSynced(providerId, result.data.playlistId, System.currentTimeMillis())
                addProviderSyncLog(
                    status = "provider_sync_item_ok",
                    provider = provider,
                    message = "providerId=${provider.id}, type=${provider.type}, playlistId=${result.data.playlistId}"
                )
                addProviderSyncHistory(
                    provider = provider,
                    status = "provider_sync_item_ok",
                    playlistId = result.data.playlistId,
                    reason = ProviderDiagnosticKind.OK,
                    detail = "Imported=${result.data.totalImported}, parsed=${result.data.totalParsed}"
                )
                AppResult.Success(result.data.playlistId)
            }
            is AppResult.Error -> {
                val reason = classifyProviderSyncFailure(result.message)
                val diagnosticKind = classifyProviderDiagnosticKind(result.message)
                addProviderSyncLog(
                    status = "provider_sync_item_error",
                    provider = provider,
                    message = "providerId=${provider.id}, type=${provider.type}, reason=$reason, detail=${result.message.take(250)}"
                )
                addProviderSyncHistory(
                    provider = provider,
                    status = "provider_sync_item_error",
                    playlistId = provider.linkedPlaylistId,
                    reason = diagnosticKind,
                    detail = result.message.take(500)
                )
                AppResult.Error(result.message, result.cause)
            }
            AppResult.Loading -> {
                addProviderSyncLog(
                    status = "provider_sync_item_loading",
                    provider = provider,
                    message = "providerId=${provider.id}, type=${provider.type}"
                )
                addProviderSyncHistory(
                    provider = provider,
                    status = "provider_sync_item_loading",
                    playlistId = provider.linkedPlaylistId,
                    reason = null,
                    detail = "Provider sync is still loading"
                )
                AppResult.Error("Provider sync is still loading")
            }
        }
    }

    override suspend fun syncAllProviders(): AppResult<Int> = withContext(Dispatchers.IO) {
        val providers = runCatching {
            providerDao.getProviders().map { it.toModel().withDecryptedSecrets() }
        }.getOrElse { throwable ->
            return@withContext AppResult.Error("Unable to load provider accounts: ${throwable.toLogSummary(4)}", throwable)
        }
        if (providers.isEmpty()) return@withContext AppResult.Success(0)

        var synced = 0
        val failures = mutableListOf<String>()
        providers.forEach { provider ->
            when (val result = syncProvider(provider.id)) {
                is AppResult.Success -> synced += 1
                is AppResult.Error -> failures += "${provider.type}:${provider.name}=${classifyProviderSyncFailure(result.message)}"
                AppResult.Loading -> failures += "${provider.type}:${provider.name}=loading"
            }
        }

        if (synced > 0 || failures.isEmpty()) {
            AppResult.Success(synced)
        } else {
            AppResult.Error("Provider sync failed: ${failures.joinToString("; ").take(500)}")
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

    private suspend fun addProviderSyncLog(status: String, provider: PlaylistProvider, message: String) {
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = provider.linkedPlaylistId,
                status = status,
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun addProviderSyncHistory(
        provider: PlaylistProvider,
        status: String,
        playlistId: Long?,
        reason: ProviderDiagnosticKind?,
        detail: String?
    ) {
        providerSyncHistoryDao.insert(
            ProviderSyncHistory(
                id = 0,
                providerId = provider.id,
                providerName = provider.name,
                providerType = provider.type,
                status = status,
                playlistId = playlistId,
                reason = reason,
                detail = detail?.take(500),
                createdAt = System.currentTimeMillis()
            ).toEntity()
        )
        providerSyncHistoryDao.trimToLatest(PROVIDER_SYNC_HISTORY_LIMIT)
    }

    private fun classifyProviderSyncFailure(message: String): String {
        val lowered = message.lowercase(Locale.ROOT)
        return when {
            "401" in lowered || "403" in lowered || "auth" in lowered ||
                "token" in lowered || "password" in lowered || "mac" in lowered -> "auth"
            "timeout" in lowered || "unable to resolve host" in lowered ||
                "failed to connect" in lowered || "network" in lowered -> "network"
            "empty" in lowered || "no channels" in lowered || "channels=0" in lowered -> "empty_playlist"
            "parse" in lowered || "json" in lowered || "xml" in lowered || "m3u" in lowered -> "parser"
            else -> "provider_error"
        }
    }

    private fun buildProviderFailureStatus(
        provider: PlaylistProvider,
        testedUrl: String,
        message: String
    ): ProviderAccountStatus {
        val diagnosticKind = classifyProviderDiagnosticKind(message)
        val statusText = when (diagnosticKind) {
            ProviderDiagnosticKind.AUTH -> "Ошибка авторизации"
            ProviderDiagnosticKind.NETWORK -> "Ошибка сети"
            ProviderDiagnosticKind.PARSER -> "Ошибка формата ответа"
            ProviderDiagnosticKind.EMPTY_PLAYLIST -> "Пустой ответ"
            ProviderDiagnosticKind.UNSUPPORTED -> "Не поддержано"
            ProviderDiagnosticKind.OK -> "OK"
            ProviderDiagnosticKind.PROVIDER_ERROR -> "Ошибка провайдера"
        }
        return ProviderAccountStatus(
            providerId = provider.id,
            type = provider.type,
            ok = false,
            statusText = statusText,
            detail = message.take(260),
            checkedAt = System.currentTimeMillis(),
            diagnosticKind = diagnosticKind,
            hint = providerDiagnosticHint(diagnosticKind, provider.type),
            testedUrl = testedUrl
        )
    }

    private fun classifyProviderDiagnosticKind(message: String): ProviderDiagnosticKind {
        return when (classifyProviderSyncFailure(message)) {
            "auth" -> ProviderDiagnosticKind.AUTH
            "network" -> ProviderDiagnosticKind.NETWORK
            "parser" -> ProviderDiagnosticKind.PARSER
            "empty_playlist" -> ProviderDiagnosticKind.EMPTY_PLAYLIST
            else -> ProviderDiagnosticKind.PROVIDER_ERROR
        }
    }

    private fun providerDiagnosticHint(
        kind: ProviderDiagnosticKind,
        providerType: ProviderType
    ): String {
        return when (kind) {
            ProviderDiagnosticKind.OK -> "Провайдер ответил корректно."
            ProviderDiagnosticKind.AUTH -> "Проверьте логин, пароль, token или MAC для ${providerType.name}."
            ProviderDiagnosticKind.NETWORK -> "Проверьте URL сервера, DNS, интернет и доступность endpoint."
            ProviderDiagnosticKind.PARSER -> "Сервер ответил в неожиданном формате. Проверьте тип провайдера и endpoint."
            ProviderDiagnosticKind.EMPTY_PLAYLIST -> "Сервер доступен, но не вернул каналов. Проверьте права аккаунта и пакет каналов."
            ProviderDiagnosticKind.UNSUPPORTED -> "Для этого типа провайдера проверка пока не реализована."
            ProviderDiagnosticKind.PROVIDER_ERROR -> "Проверьте параметры провайдера и повторите попытку."
        }
    }

    private fun createProviderStatus(
        provider: PlaylistProvider,
        ok: Boolean,
        statusText: String,
        detail: String?,
        testedUrl: String,
        diagnosticKind: ProviderDiagnosticKind = if (ok) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.PROVIDER_ERROR
    ): ProviderAccountStatus {
        return ProviderAccountStatus(
            providerId = provider.id,
            type = provider.type,
            ok = ok,
            statusText = statusText,
            detail = detail,
            checkedAt = System.currentTimeMillis(),
            diagnosticKind = diagnosticKind,
            hint = providerDiagnosticHint(diagnosticKind, provider.type),
            testedUrl = testedUrl
        )
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
        return createProviderStatus(
            provider = provider,
            ok = authOk && !status.equals("Disabled", ignoreCase = true) && !status.equals("Banned", ignoreCase = true),
            statusText = status,
            detail = buildString {
                append("auth=")
                append(if (authOk) "ok" else "failed")
                expires?.let { append(", exp=$it") }
            },
            testedUrl = url.toString(),
            diagnosticKind = if (authOk) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.AUTH
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
        return createProviderStatus(
            provider = provider,
            ok = token.isNotBlank(),
            statusText = if (token.isNotBlank()) "Handshake OK" else "No token",
            detail = if (token.isNotBlank()) "portal=$portalUrl" else "Stalker portal did not return token",
            testedUrl = portalUrl,
            diagnosticKind = if (token.isNotBlank()) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.AUTH
        )
    }

    private fun checkM3uProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val response = okHttpClient.newCall(Request.Builder().url(provider.baseUrl).head().build())
            .execute()
            .use { response ->
                response.code to (response.header("content-type") ?: "unknown")
            }
        val code = response.first
        val diagnosticKind = when {
            code in 200..399 -> ProviderDiagnosticKind.OK
            code == 401 || code == 403 -> ProviderDiagnosticKind.AUTH
            else -> ProviderDiagnosticKind.PROVIDER_ERROR
        }
        return createProviderStatus(
            provider = provider,
            ok = response.first in 200..399,
            statusText = "HTTP ${response.first}",
            detail = "content-type=${response.second}",
            testedUrl = provider.baseUrl,
            diagnosticKind = diagnosticKind
        )
    }

    private fun checkHdHomeRunProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val lineupUrl = normalizeProviderHdHomeRunLineupUrl(provider.baseUrl)
        val body = okHttpClient.newCall(Request.Builder().url(lineupUrl).build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
        }
            .trim()
        if (!body.startsWith("[")) error("HDHomeRun lineup.json returned non-JSON array")
        val channels = JSONArray(body)
        return createProviderStatus(
            provider = provider,
            ok = channels.length() > 0,
            statusText = "lineup.json OK",
            detail = "channels=${channels.length()}, url=$lineupUrl",
            testedUrl = lineupUrl,
            diagnosticKind = if (channels.length() > 0) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.EMPTY_PLAYLIST
        )
    }

    private fun checkTvheadendProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val playlistUrl = normalizeProviderTvheadendPlaylistUrl(provider.baseUrl)
        val body = okHttpClient.newCall(
            Request.Builder()
                .url(playlistUrl)
                .applyProviderBasicAuth(provider.username, provider.password)
                .build()
        )
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            .trim()
        if (!body.startsWith("#EXTM3U")) error("Tvheadend returned non-M3U response")
        val channels = body.lineSequence().count { it.startsWith("#EXTINF", ignoreCase = true) }
        return createProviderStatus(
            provider = provider,
            ok = channels > 0,
            statusText = "M3U OK",
            detail = "channels=$channels, url=$playlistUrl",
            testedUrl = playlistUrl,
            diagnosticKind = if (channels > 0) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.EMPTY_PLAYLIST
        )
    }

    private fun checkJellyfinProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val baseUrl = provider.baseUrl.trim().trimEnd('/')
        val apiKey = provider.token.orEmpty().trim()
        if (baseUrl.isBlank()) error("Jellyfin server URL is empty")
        if (apiKey.isBlank()) error("Jellyfin API key is empty")
        val channelsUrl = providerJellyfinChannelsUrl(baseUrl, apiKey)
        val body = okHttpClient.newCall(Request.Builder().url(channelsUrl).build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
        }
            .trim()
        if (!body.startsWith("{")) error("Jellyfin returned non-JSON response")
        val channels = JSONObject(body).optJSONArray("Items") ?: JSONArray()
        return createProviderStatus(
            provider = provider,
            ok = channels.length() > 0,
            statusText = "Live TV OK",
            detail = "channels=${channels.length()}, url=$baseUrl/LiveTv/Channels",
            testedUrl = channelsUrl,
            diagnosticKind = if (channels.length() > 0) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.EMPTY_PLAYLIST
        )
    }

    private fun checkPlexProvider(provider: PlaylistProvider): ProviderAccountStatus {
        val baseUrl = provider.baseUrl.trim().trimEnd('/')
        val token = provider.token.orEmpty().trim()
        if (baseUrl.isBlank()) error("Plex server URL is empty")
        if (token.isBlank()) error("Plex token is empty")
        val dvrs = loadProviderPlexDvrs(baseUrl, token)
        val channelCount = dvrs.sumOf { dvr ->
            val xml = okHttpClient.newCall(Request.Builder().url(providerPlexDvrChannelsUrl(baseUrl, token, dvr)).build())
                .execute()
                .use { response ->
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                .trim()
            if (!xml.startsWith("<")) error("Plex returned non-XML channels response")
            parseProviderPlexChannels(xml, dvr).size
        }
        return createProviderStatus(
            provider = provider,
            ok = channelCount > 0,
            statusText = "Live TV OK",
            detail = "dvrs=${dvrs.size}, channels=$channelCount, url=$baseUrl/livetv/dvrs",
            testedUrl = "$baseUrl/livetv/dvrs",
            diagnosticKind = if (channelCount > 0) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.EMPTY_PLAYLIST
        )
    }

    private fun providerJellyfinChannelsUrl(baseUrl: String, apiKey: String): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("LiveTv")
            .addPathSegment("Channels")
            .addQueryParameter("api_key", apiKey)
            .build()
            .toString()
    }

    private fun loadProviderPlexDvrs(baseUrl: String, token: String): List<ProviderPlexDvr> {
        val body = okHttpClient.newCall(Request.Builder().url(providerPlexDvrsUrl(baseUrl, token)).build())
            .execute()
            .use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            .trim()
        if (!body.startsWith("<")) error("Plex returned non-XML DVR response")
        return parseProviderPlexDvrs(body)
    }

    private fun providerPlexDvrsUrl(baseUrl: String, token: String): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .addPathSegment("livetv")
            .addPathSegment("dvrs")
            .addQueryParameter("X-Plex-Token", token)
            .build()
            .toString()
    }

    private fun providerPlexDvrChannelsUrl(baseUrl: String, token: String, dvr: ProviderPlexDvr): String {
        val path = dvr.key.takeIf { it.startsWith("/") }?.trimEnd('/')?.plus("/channels")
            ?: "/livetv/dvrs/${dvr.id}/channels"
        return providerPlexPathUrl(baseUrl, token, path)
    }

    private fun providerPlexPathUrl(baseUrl: String, token: String, path: String): String {
        return baseUrl.toHttpUrl()
            .newBuilder()
            .encodedPath(path)
            .addQueryParameter("X-Plex-Token", token)
            .build()
            .toString()
    }

    private fun parseProviderPlexDvrs(xml: String): List<ProviderPlexDvr> {
        val result = mutableListOf<ProviderPlexDvr>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), StandardCharsets.UTF_8.name())
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("Dvr", ignoreCase = true)) {
                val id = parser.providerAttr("uuid")
                    .ifBlank { parser.providerAttr("id") }
                    .ifBlank { parser.providerAttr("key").trim('/') }
                if (id.isNotBlank()) {
                    result += ProviderPlexDvr(id = id, key = parser.providerAttr("key"))
                }
            }
            event = parser.next()
        }
        return result.distinctBy { it.id }
    }

    private fun parseProviderPlexChannels(xml: String, dvr: ProviderPlexDvr): List<String> {
        val result = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)), StandardCharsets.UTF_8.name())
        }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name.equals("Channel", ignoreCase = true)) {
                val id = parser.providerAttr("id")
                    .ifBlank { parser.providerAttr("key").substringAfterLast('/') }
                    .ifBlank { parser.providerAttr("channelIdentifier") }
                val name = parser.providerAttr("title")
                    .ifBlank { parser.providerAttr("name") }
                    .ifBlank { parser.providerAttr("callSign") }
                if (id.isNotBlank() && name.isNotBlank()) {
                    result += "${dvr.id}:$id"
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun XmlPullParser.providerAttr(name: String): String {
        return getAttributeValue(null, name).orEmpty().trim()
    }

    private fun normalizeProviderHdHomeRunLineupUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) error("HDHomeRun URL is empty")
        val url = trimmed.toHttpUrl()
        if (url.encodedPath.endsWith("/lineup.json", ignoreCase = true)) {
            return url.toString()
        }
        return url.newBuilder()
            .encodedPath(url.encodedPath.trimEnd('/') + "/lineup.json")
            .query(null)
            .build()
            .toString()
    }

    private fun normalizeProviderTvheadendPlaylistUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) error("Tvheadend URL is empty")
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

    private fun Request.Builder.applyProviderBasicAuth(username: String?, password: String?): Request.Builder {
        val normalizedUsername = username?.trim().orEmpty()
        val normalizedPassword = password?.trim().orEmpty()
        if (normalizedUsername.isNotBlank() || normalizedPassword.isNotBlank()) {
            header("Authorization", Credentials.basic(normalizedUsername, normalizedPassword))
        }
        return this
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
        const val PROVIDER_SYNC_HISTORY_LIMIT = 500
        const val PROVIDER_STALKER_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; MAG200; en-US) AppleWebKit/533.3"
        val PROVIDER_STALKER_MAC_REGEX = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
    }

    private data class ProviderPlexDvr(
        val id: String,
        val key: String
    )
}

@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val channelDao: ChannelDao
) : FavoritesRepository {
    override fun observeFavorites(): Flow<List<Channel>> {
        return channelDao.observeFavoriteChannels().map { rows ->
            rows
                .distinctBy { channel ->
                    GlobalFavoriteIdentity.key(channel.tvgId, channel.name, channel.streamUrl)
                }
                .map { it.toModel() }
        }
    }

    override fun observeFavoriteChannelIds(): Flow<Set<Long>> {
        return favoriteDao.observeFavorites().map { rows -> rows.mapTo(mutableSetOf()) { it.channelId } }
    }

    override suspend fun toggleFavorite(channelId: Long) {
        val selected = channelDao.findById(channelId) ?: return
        val identity = GlobalFavoriteIdentity.key(selected.tvgId, selected.name, selected.streamUrl)
        val equivalentIds = channelDao.getAllChannels()
            .asSequence()
            .filter { channel ->
                GlobalFavoriteIdentity.key(channel.tvgId, channel.name, channel.streamUrl) == identity
            }
            .map { it.id }
            .toList()
            .ifEmpty { listOf(channelId) }
        val favoriteIds = favoriteDao.getFavorites().mapTo(mutableSetOf()) { it.channelId }
        val removeGlobally = equivalentIds.any { it in favoriteIds }

        if (removeGlobally) {
            favoriteDao.deleteByChannelIds(equivalentIds)
        } else {
            val now = System.currentTimeMillis()
            favoriteDao.upsertAll(
                equivalentIds.map { id -> FavoriteEntity(channelId = id, addedAt = now) }
            )
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
    @ApplicationContext private val context: Context,
    private val parentalProfileDao: ParentalProfileDao
) : SettingsRepository {
    override fun observeAppStartDestination(): Flow<AppStartDestination> {
        return context.settingsDataStore.data.map { prefs ->
            val stored = prefs[SettingsKeys.appStartDestination] ?: AppStartDestination.HOME.name
            runCatching { AppStartDestination.valueOf(stored) }.getOrDefault(AppStartDestination.HOME)
        }
    }

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

    override fun observeProviderAutoSyncEnabled(): Flow<Boolean> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.providerAutoSyncEnabled] ?: true
        }
    }

    override fun observeProviderAutoSyncIntervalHours(): Flow<Int> {
        return context.settingsDataStore.data.map { prefs ->
            normalizeProviderAutoSyncInterval(prefs[SettingsKeys.providerAutoSyncIntervalHours] ?: DEFAULT_PROVIDER_AUTO_SYNC_HOURS)
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

    override fun observeRecordingStorageCustomTreeUri(): Flow<String?> {
        return context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.recordingStorageCustomTreeUri]?.trim()?.ifBlank { null }
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

    override fun observeParentalControlProfiles(): Flow<List<ParentalControlProfile>> {
        return parentalProfileDao.observeProfiles().map { rows -> rows.map { it.toModel() } }
    }

    override suspend fun setAppStartDestination(destination: AppStartDestination) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.appStartDestination] = destination.name
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

    override suspend fun setProviderAutoSyncEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.providerAutoSyncEnabled] = enabled
        }
    }

    override suspend fun setProviderAutoSyncIntervalHours(hours: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.providerAutoSyncIntervalHours] = normalizeProviderAutoSyncInterval(hours)
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

    override suspend fun setRecordingStorageCustomTreeUri(uri: String?) {
        context.settingsDataStore.edit { prefs ->
            val normalized = uri?.trim().orEmpty()
            if (normalized.isBlank()) {
                prefs.remove(SettingsKeys.recordingStorageCustomTreeUri)
            } else {
                prefs[SettingsKeys.recordingStorageCustomTreeUri] = normalized
            }
        }
    }

    override suspend fun getRecordingStorageInfo(location: RecordingStorageLocation): RecordingStorageInfo {
        return withContext(Dispatchers.IO) {
            when (location) {
                RecordingStorageLocation.CUSTOM_EXTERNAL -> {
                    val rawUri = context.settingsDataStore.data.map { prefs ->
                        prefs[SettingsKeys.recordingStorageCustomTreeUri]?.trim()?.ifBlank { null }
                    }.first()
                    if (rawUri == null) {
                        RecordingStorageInfo(
                            location = location,
                            path = "SAF папка не выбрана",
                            exists = false,
                            writable = false,
                            freeBytes = -1L,
                            usingFallback = false,
                            configured = false
                        )
                    } else {
                        val root = DocumentFile.fromTreeUri(context, Uri.parse(rawUri))
                        val label = root?.name?.takeIf { it.isNotBlank() } ?: "SAF папка"
                        RecordingStorageInfo(
                            location = location,
                            path = "$label\n$rawUri",
                            exists = root?.exists() == true,
                            writable = root?.canWrite() == true,
                            freeBytes = -1L,
                            usingFallback = false,
                            configured = true
                        )
                    }
                }

                else -> {
                    val directory = recordingStorageDirectory(location)
                    RecordingStorageInfo(
                        location = location,
                        path = directory.absolutePath,
                        exists = directory.exists(),
                        writable = directory.isDirectory && directory.canWrite(),
                        freeBytes = directory.usableSpace,
                        usingFallback = location == RecordingStorageLocation.APP_EXTERNAL &&
                            context.getExternalFilesDir(null) == null,
                        configured = true
                    )
                }
            }
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
        val normalizedKeywords = normalizeBlockedKeywords(blockedKeywords).ifEmpty { DEFAULT_PARENTAL_KEYWORDS }
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.parentalEnabled] = enabled
            prefs[SettingsKeys.parentalHideAdultChannels] = hideAdultChannels
            prefs[SettingsKeys.parentalBlockedKeywords] = encodeKeywordList(normalizedKeywords)
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

    override suspend fun saveParentalControlProfile(
        name: String,
        pin: String?,
        blockedKeywords: List<String>,
        lockedSettings: Boolean
    ): AppResult<ParentalControlProfile> = withContext(Dispatchers.IO) {
        val normalizedName = name.trim()
        if (normalizedName.isBlank()) {
            return@withContext AppResult.Error("Укажите имя профиля")
        }

        val normalizedKeywords = normalizeBlockedKeywords(blockedKeywords)
        if (normalizedKeywords.isEmpty()) {
            return@withContext AppResult.Error("Добавьте ключевые слова для профиля")
        }

        val storedHash = context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.parentalPinHash].orEmpty()
        }.first()
        val normalizedPin = pin?.trim().orEmpty()
        val effectiveHash = when {
            normalizedPin.isNotBlank() -> hashPin(normalizedPin)
            storedHash.isNotBlank() -> storedHash
            else -> return@withContext AppResult.Error("Сначала задайте PIN для родительского контроля")
        }

        val createdAt = System.currentTimeMillis()
        val entity = com.iptv.tv.core.database.entity.ParentalProfileEntity(
            name = normalizedName,
            pinHash = effectiveHash,
            blockedKeywordsCsv = normalizedKeywords.joinToString(","),
            lockedSettings = lockedSettings,
            enabled = false,
            createdAt = createdAt
        )
        val profileId = parentalProfileDao.upsert(entity)
        AppResult.Success(
            entity.copy(id = profileId).toModel()
        )
    }

    override suspend fun activateParentalControlProfile(profileId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        val profile = parentalProfileDao.observeProfiles()
            .first()
            .firstOrNull { it.id == profileId }
            ?.toModel()
            ?: return@withContext AppResult.Error("Профиль не найден")

        val currentSettings = observeParentalControlSettings().first()
        parentalProfileDao.disableAll()
        parentalProfileDao.setEnabled(profileId, enabled = true)
        setParentalControlWithHash(
            enabled = true,
            pinHash = profile.pinHash,
            hideAdultChannels = currentSettings.hideAdultChannels,
            blockedKeywords = profile.blockedKeywords
        )
        AppResult.Success(Unit)
    }

    override suspend fun clearActiveParentalControlProfile(): AppResult<Unit> = withContext(Dispatchers.IO) {
        val currentSettings = observeParentalControlSettings().first()
        val storedHash = context.settingsDataStore.data.map { prefs ->
            prefs[SettingsKeys.parentalPinHash].orEmpty()
        }.first()
        parentalProfileDao.disableAll()
        if (storedHash.isNotBlank()) {
            setParentalControlWithHash(
                enabled = false,
                pinHash = storedHash,
                hideAdultChannels = currentSettings.hideAdultChannels,
                blockedKeywords = currentSettings.blockedKeywords
            )
        }
        AppResult.Success(Unit)
    }

    override suspend fun deleteParentalControlProfile(profileId: Long): AppResult<Unit> = withContext(Dispatchers.IO) {
        val active = parentalProfileDao.findActiveProfile()
        parentalProfileDao.deleteById(profileId)
        if (active?.id == profileId) {
            clearActiveParentalControlProfile()
        }
        AppResult.Success(Unit)
    }

    override suspend fun exportParentalControlProfiles(): AppResult<String> = withContext(Dispatchers.IO) {
        val profiles = parentalProfileDao.observeProfiles().first().map { it.toModel() }
        val json = JSONObject().apply {
            put("version", 1)
            put(
                "profiles",
                JSONArray().apply {
                    profiles.forEach { profile ->
                        put(
                            JSONObject().apply {
                                put("name", profile.name)
                                put("pinHash", profile.pinHash)
                                put("blockedKeywords", JSONArray(profile.blockedKeywords))
                                put("lockedSettings", profile.lockedSettings)
                                put("enabled", profile.enabled)
                                put("createdAt", profile.createdAt)
                            }
                        )
                    }
                }
            )
        }
        AppResult.Success(json.toString(2))
    }

    override suspend fun importParentalControlProfiles(
        payload: String,
        replaceExisting: Boolean
    ): AppResult<Int> = withContext(Dispatchers.IO) {
        val normalizedPayload = payload.trim()
        if (normalizedPayload.isBlank()) {
            return@withContext AppResult.Error("Вставьте JSON с профилями")
        }

        val root = runCatching { JSONObject(normalizedPayload) }
            .getOrElse { return@withContext AppResult.Error("Невалидный JSON профилей: ${it.message}", it) }
        val profiles = root.optJSONArray("profiles")
            ?: return@withContext AppResult.Error("JSON не содержит массива profiles")

        if (replaceExisting) {
            parentalProfileDao.observeProfiles().first().forEach { profile ->
                parentalProfileDao.deleteById(profile.id)
            }
        }

        var imported = 0
        var activeProfileId: Long? = null
        for (index in 0 until profiles.length()) {
            val item = profiles.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            val pinHash = item.optString("pinHash").trim()
            if (name.isBlank() || pinHash.isBlank()) continue
            val blockedKeywords = buildList {
                val rawKeywords = item.optJSONArray("blockedKeywords")
                for (keywordIndex in 0 until (rawKeywords?.length() ?: 0)) {
                    rawKeywords?.optString(keywordIndex)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(::add)
                }
            }.ifEmpty { DEFAULT_PARENTAL_KEYWORDS }
            val createdAt = item.optLong("createdAt").takeIf { it > 0 } ?: System.currentTimeMillis()
            val enabled = item.optBoolean("enabled", false)
            val id = parentalProfileDao.upsert(
                com.iptv.tv.core.database.entity.ParentalProfileEntity(
                    name = name,
                    pinHash = pinHash,
                    blockedKeywordsCsv = normalizeBlockedKeywords(blockedKeywords).joinToString(","),
                    lockedSettings = item.optBoolean("lockedSettings", true),
                    enabled = false,
                    createdAt = createdAt
                )
            )
            if (enabled) activeProfileId = id
            imported += 1
        }

        if (imported == 0) {
            return@withContext AppResult.Error("В JSON нет ни одного корректного профиля")
        }

        parentalProfileDao.disableAll()
        activeProfileId?.let { profileId ->
            parentalProfileDao.setEnabled(profileId, enabled = true)
            val profile = parentalProfileDao.observeProfiles().first()
                .firstOrNull { it.id == profileId }
                ?.toModel()
            if (profile != null) {
                val currentSettings = observeParentalControlSettings().first()
                setParentalControlWithHash(
                    enabled = true,
                    pinHash = profile.pinHash,
                    hideAdultChannels = currentSettings.hideAdultChannels,
                    blockedKeywords = profile.blockedKeywords
                )
            }
        }

        AppResult.Success(imported)
    }

    private fun recordingStorageDirectory(location: RecordingStorageLocation): File {
        return when (location) {
            RecordingStorageLocation.INTERNAL -> File(context.filesDir, "recordings")
            RecordingStorageLocation.APP_EXTERNAL -> {
                context.getExternalFilesDir(null)?.let { File(it, "recordings") }
                    ?: File(context.filesDir, "recordings")
            }
            RecordingStorageLocation.CUSTOM_EXTERNAL -> File(context.filesDir, "recordings")
        }
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

    private suspend fun setParentalControlWithHash(
        enabled: Boolean,
        pinHash: String,
        hideAdultChannels: Boolean,
        blockedKeywords: List<String>
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[SettingsKeys.parentalEnabled] = enabled
            prefs[SettingsKeys.parentalHideAdultChannels] = hideAdultChannels
            prefs[SettingsKeys.parentalBlockedKeywords] = encodeKeywordList(normalizeBlockedKeywords(blockedKeywords))
            prefs[SettingsKeys.parentalPinHash] = pinHash
        }
    }

    private fun normalizeBlockedKeywords(blockedKeywords: List<String>): List<String> {
        return blockedKeywords
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .toList()
    }

    private fun normalizeLearningQuery(raw: String): String {
        return raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .trim(',', '.', ';')
            .take(MAX_SCANNER_LEARN_QUERY_LENGTH)
    }

    private fun normalizeProviderAutoSyncInterval(hours: Int): Int {
        val allowed = listOf(6, 12, 24)
        if (hours <= 0) return DEFAULT_PROVIDER_AUTO_SYNC_HOURS
        return allowed.minBy { candidate -> kotlin.math.abs(candidate - hours) }
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
        const val DEFAULT_PROVIDER_AUTO_SYNC_HOURS = 12
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
