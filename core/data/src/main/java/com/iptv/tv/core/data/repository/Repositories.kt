package com.iptv.tv.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toEntity
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.data.settings.SettingsKeys
import com.iptv.tv.core.data.settings.settingsDataStore
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.HistoryDao
import com.iptv.tv.core.database.dao.PlaylistDao
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
import com.iptv.tv.core.domain.repository.ScannerRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.engine.data.EngineStreamClient
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EngineStatus
import com.iptv.tv.core.model.ManualBufferSettings
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.ScannerSearchRequest
import com.iptv.tv.core.network.datasource.PublicRepositoryScannerDataSource
import com.iptv.tv.core.parser.M3uParser
import com.iptv.tv.core.parser.ParseResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Locale
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
                    AppResult.Error(
                        message = throwable.message ?: "Scanner failed",
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
        }.getOrElse { AppResult.Error("Unable to import by URL", it) }
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
            return@withContext AppResult.Error("Unable to read file", it)
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

    override suspend fun getChannelById(channelId: Long): AppResult<Channel> = withContext(Dispatchers.IO) {
        if (channelId <= 0) return@withContext AppResult.Error("Invalid channel id")
        val channel = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Channel not found: id=$channelId")
        AppResult.Success(channel.toModel())
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
                val playlistId = playlistDao.insertPlaylist(
                    PlaylistEntity(
                        name = playlistName,
                        sourceType = sourceType.name,
                        source = source,
                        scheduleHours = 12,
                        lastSyncedAt = null,
                        isCustom = false,
                        createdAt = System.currentTimeMillis()
                    )
                )

                val prepared = deduplicated.mapIndexed { index, channel ->
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

    private companion object {
        const val DB_INSERT_CHUNK = 500
        const val AUTO_HEALTH_CHECK_LIMIT = 200
        const val HEALTH_CHECK_CONCURRENCY = 20
        const val HEALTH_CHECK_RETRIES = 2
        const val HEALTH_RETRY_DELAY_MS = 450L
        val RETRIABLE_HTTP_CODES = setOf(408, 429, 500, 502, 503, 504)
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

    private companion object {
        const val DEFAULT_MANUAL_START_MS = 12_000
        const val DEFAULT_MANUAL_REBUFFER_MS = 2_000
        const val DEFAULT_MANUAL_MAX_MS = 50_000
        const val DEFAULT_ENGINE_ENDPOINT = "http://127.0.0.1:6878"
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
