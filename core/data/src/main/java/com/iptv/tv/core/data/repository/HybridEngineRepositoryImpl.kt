package com.iptv.tv.core.data.repository

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.engine.data.AceContentIdResolverUnavailableException
import com.iptv.tv.core.engine.data.AceContentTransportResolution
import com.iptv.tv.core.engine.data.AceContentTransportResolver
import com.iptv.tv.core.engine.data.AceTransportMetadata
import com.iptv.tv.core.engine.data.EngineStreamClient
import com.iptv.tv.core.model.EngineStatus
import com.iptv.tv.core.p2p.AceLiveEmbeddedEngine
import com.iptv.tv.core.p2p.LibtorrentEmbeddedEngine
import com.iptv.tv.core.p2p.P2pResult
import com.iptv.tv.core.p2p.P2pSource
import com.iptv.tv.core.p2p.P2pSourceParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Player-facing engine repository.
 *
 * Standard BitTorrent sources (`magnet:`, explicit infohash and `.torrent`) are owned exclusively by
 * the in-process libtorrent backend and never probe Ace Engine as a fallback. True Ace content ids
 * are never reinterpreted as ordinary torrents: content IDs enter an Ace-specific metadata resolver,
 * and legacy gateway `infohash` values enter the direct Ace Live path. Torrent TV content IDs and
 * live infohashes never fall back to an installed Ace Engine; proven non-live BitTorrent identities
 * are handed to libtorrent.
 */
@Singleton
class HybridEngineRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val client: EngineStreamClient,
    private val contentTransportResolver: AceContentTransportResolver,
    private val syncLogDao: SyncLogDao,
    okHttpClient: OkHttpClient
) : EngineRepository {
    private val appContext = context.applicationContext
    private val embeddedEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LibtorrentEmbeddedEngine(appContext, okHttpClient)
    }
    private val aceLiveEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AceLiveEmbeddedEngine(okHttpClient)
    }
    private val streamEpoch = AtomicLong(0L)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val embeddedEngineUsed = AtomicBoolean(false)
    private val aceLiveEngineUsed = AtomicBoolean(false)

    override suspend fun connect(endpoint: String): AppResult<Unit> {
        return when (val result = client.connect(endpoint)) {
            is AppResult.Success -> {
                log("engine_connected", "Connected to $endpoint")
                result
            }
            is AppResult.Error -> {
                log("engine_connect_error", result.message)
                result
            }
            AppResult.Loading -> result
        }
    }

    override suspend fun refreshStatus(): AppResult<EngineStatus> = client.refreshStatus()

    override fun observeStatus(): Flow<EngineStatus> = client.observeStatus()

    override suspend fun resolveTorrentStream(magnetOrAce: String): AppResult<String> {
        val epoch = streamEpoch.incrementAndGet()
        return when (EngineStreamRouting.route(magnetOrAce)) {
            EngineStreamRoute.ACE_CONTENT_ID -> {
                resolveAceContentIdWithEmbeddedMetadata(magnetOrAce, epoch)
            }
            EngineStreamRoute.ACE_LIVE_INFOHASH -> {
                resolveAceLiveInfoHash(magnetOrAce, epoch)
            }
            EngineStreamRoute.ACE_LIVE_COMPATIBILITY -> {
                resolveAceLiveCompatibility(magnetOrAce, epoch)
            }
            EngineStreamRoute.EXTERNAL_COMPATIBILITY -> {
                stopEmbeddedForEpoch(epoch)
                if (streamEpoch.get() != epoch) {
                    supersededResult()
                } else {
                    resolveExternal(magnetOrAce).takeIf { streamEpoch.get() == epoch }
                        ?: supersededResult()
                }
            }
            EngineStreamRoute.EMBEDDED_BITTORRENT -> resolveEmbeddedOnly(magnetOrAce, epoch)
        }
    }

    override suspend fun stopTorrentStream(): AppResult<Unit> {
        val epoch = streamEpoch.incrementAndGet()
        return stopEmbeddedForEpoch(epoch)
    }

    override fun releaseTorrentStream() {
        val epoch = streamEpoch.incrementAndGet()
        if (!embeddedEngineUsed.get() && !aceLiveEngineUsed.get()) return

        cleanupScope.launch {
            stopEmbeddedForEpoch(epoch)
        }
    }

    /**
     * Stop requests are no longer serialized behind metadata preparation. The embedded engine
     * invalidates its own generation before touching the stream lock, so a slow stale magnet
     * fetch can finish in the background without keeping direct IPTV or a newer P2P request waiting.
     */
    private suspend fun stopEmbeddedForEpoch(epoch: Long): AppResult<Unit> {
        if (
            streamEpoch.get() != epoch ||
            (!embeddedEngineUsed.get() && !aceLiveEngineUsed.get())
        ) {
            return AppResult.Success(Unit)
        }

        val failures = mutableListOf<P2pResult.Error>()
        if (embeddedEngineUsed.get()) {
            val stopped = embeddedEngine.stopStream()
            if (stopped is P2pResult.Error) failures += stopped
        }
        if (aceLiveEngineUsed.get()) {
            val stopped = aceLiveEngine.stopStream()
            if (stopped is P2pResult.Error) failures += stopped
        }
        if (streamEpoch.get() != epoch || failures.isEmpty()) {
            return AppResult.Success(Unit)
        }

        val failure = failures.first()
        log("embedded_p2p_stop_error", failure.message)
        return AppResult.Error(failure.message, failure.cause)
    }

    /**
     * Standard BitTorrent is deliberately self-contained. An embedded-engine failure must remain a
     * BitTorrent failure instead of silently changing protocols and probing Ace Engine.
     */
    private suspend fun resolveEmbeddedOnly(
        rawSource: String,
        epoch: Long
    ): AppResult<String> {
        if (streamEpoch.get() != epoch) return supersededResult()

        val parsed = P2pSourceParser.parse(rawSource)
        if (parsed !is P2pResult.Success) {
            if (streamEpoch.get() != epoch) return supersededResult()
            val error = parsed as P2pResult.Error
            log("embedded_p2p_parse_error", error.message)
            return AppResult.Error(
                message = "Embedded BitTorrent source parsing failed: ${error.message}",
                cause = error.cause
            )
        }

        embeddedEngineUsed.set(true)
        val embedded = embeddedEngine.prepareStream(parsed.data)
        if (streamEpoch.get() != epoch) return supersededResult()

        return when (embedded) {
            is P2pResult.Success -> {
                log(
                    status = "embedded_p2p_resolved",
                    message = "Embedded BitTorrent stream prepared: ${embedded.data.file.name}"
                )
                if (streamEpoch.get() == epoch) {
                    AppResult.Success(embedded.data.url)
                } else {
                    supersededResult()
                }
            }
            is P2pResult.Error -> {
                if (streamEpoch.get() != epoch) return supersededResult()

                log("embedded_p2p_resolve_error", embedded.message)
                AppResult.Error(
                    message = "Embedded BitTorrent failed: ${embedded.message}",
                    cause = embedded.cause
                )
            }
        }
    }

    private suspend fun resolveAceLiveCompatibility(
        rawSource: String,
        epoch: Long
    ): AppResult<String> {
        stopEmbeddedForEpoch(epoch)
        if (streamEpoch.get() != epoch) return supersededResult()

        log(
            "ace_live_compatibility_route",
            "Ace Live transport routed to the external compatibility engine"
        )
        val result = resolveExternal(rawSource)
        return if (streamEpoch.get() == epoch) result else supersededResult()
    }

    private suspend fun resolveAceLiveInfoHash(
        rawSource: String,
        epoch: Long
    ): AppResult<String> {
        stopEmbeddedForEpoch(epoch)
        if (streamEpoch.get() != epoch) return supersededResult()

        val infoHash = P2pSourceParser.parseAceLiveInfoHash(rawSource)
            ?: return AppResult.Error("Некорректный Ace Live infohash в ссылке Torrent TV")
        aceLiveEngineUsed.set(true)
        return when (val live = aceLiveEngine.prepareInfoHash(infoHash)) {
            is P2pResult.Success -> {
                if (streamEpoch.get() != epoch) return supersededResult()
                log("embedded_ace_live_infohash_resolved", "Direct Ace Live swarm prepared")
                AppResult.Success(live.data.url)
            }
            is P2pResult.Error -> {
                if (streamEpoch.get() != epoch) return supersededResult()
                log("embedded_ace_live_infohash_error", live.message)
                AppResult.Error(
                    message = "Встроенный Ace Live не смог подготовить поток: ${live.message}",
                    cause = live.cause
                )
            }
        }
    }

    /**
     * A pure Ace content id is not a BitTorrent hash. Transport discovery is delegated to the
     * resolver boundary so playlist syntax no longer determines the runtime backend. Only a proven
     * non-live BitTorrent transport may enter standard libtorrent. With no Ace-specific resolver
     * available, the failure is explicit instead of retrying the same missing engine as playback.
     */
    private suspend fun resolveAceContentIdWithEmbeddedMetadata(
        rawSource: String,
        epoch: Long
    ): AppResult<String> {
        stopEmbeddedForEpoch(epoch)
        if (streamEpoch.get() != epoch) return supersededResult()

        val parsedSource = P2pSourceParser.parse(rawSource)
        val contentId = (parsedSource as? P2pResult.Success)
            ?.data
            ?.let { source -> (source as? P2pSource.AceContentId)?.contentId }
        if (!contentId.isNullOrBlank()) {
            aceLiveEngineUsed.set(true)
            return when (val live = aceLiveEngine.prepareStream(contentId)) {
                is P2pResult.Success -> {
                    if (streamEpoch.get() != epoch) return supersededResult()
                    log(
                        "embedded_ace_live_resolved",
                        "Autonomous Ace Live stream prepared: ${live.data.name}"
                    )
                    AppResult.Success(live.data.url)
                }

                is P2pResult.Error -> {
                    if (streamEpoch.get() != epoch) return supersededResult()
                    log("embedded_ace_live_resolve_error", live.message)
                    AppResult.Error(
                        message = "Встроенный Ace Live не смог подготовить поток: ${live.message}",
                        cause = live.cause
                    )
                }
            }
        }

        val resolution = contentTransportResolver.resolve(rawSource)
        if (streamEpoch.get() != epoch) return supersededResult()

        val embeddedSource = when (resolution) {
            is AppResult.Success -> when (val transport = resolution.data) {
                is AceContentTransportResolution.EmbeddedBitTorrent -> {
                    log(
                        "engine_content_id_metadata_resolved",
                        "Ace content id mapped to proven non-live BitTorrent infohash ${transport.infoHash}"
                    )
                    P2pSource.InfoHash(transport.infoHash)
                }
                is AceContentTransportResolution.EmbeddedTorrentFile -> {
                    when (
                        val materialized = materializeAceTransportFile(
                            rawSource = rawSource,
                            encoded = transport.transportFileDataBase64
                        )
                    ) {
                        is P2pResult.Success -> {
                            log(
                                "engine_content_id_transport_file_resolved",
                                "Ace content id supplied non-live BitTorrent transport-file data"
                            )
                            materialized.data
                        }
                        is P2pResult.Error -> {
                            log("engine_content_id_transport_file_error", materialized.message)
                            return resolveExternalIfCurrent(rawSource, epoch)
                        }
                    }
                }
                is AceContentTransportResolution.AceLive -> {
                    log(
                        "engine_content_id_live_transport",
                        "Ace content id resolved to live transport; preparing it with the embedded Ace Live runtime"
                    )
                    return prepareResolvedAceLiveMetadata(
                        rawSource = rawSource,
                        metadata = transport.metadata,
                        epoch = epoch
                    )
                }
                is AceContentTransportResolution.Unsupported -> {
                    log("engine_content_id_transport_unsupported", transport.reason)
                    return resolveExternalIfCurrent(rawSource, epoch)
                }
            }
            is AppResult.Error -> {
                log("engine_content_id_metadata_error", resolution.message)
                if (resolution.cause is AceContentIdResolverUnavailableException) {
                    log(
                        "engine_content_id_resolver_unavailable",
                        "No Ace-specific content-id resolver is available"
                    )
                    return aceContentIdResolverUnavailable(resolution)
                }
                return resolveExternalIfCurrent(rawSource, epoch)
            }
            AppResult.Loading -> return AppResult.Loading
        }

        embeddedEngineUsed.set(true)
        val embedded = embeddedEngine.prepareStream(embeddedSource)
        if (streamEpoch.get() != epoch) return supersededResult()

        return when (embedded) {
            is P2pResult.Success -> {
                log(
                    "embedded_p2p_content_id_resolved",
                    "Embedded BitTorrent stream prepared from Ace content metadata: ${embedded.data.file.name}"
                )
                AppResult.Success(embedded.data.url)
            }
            is P2pResult.Error -> {
                log("embedded_p2p_content_id_error", embedded.message)
                resolveExternalIfCurrent(rawSource, epoch)
            }
        }
    }

    private fun aceContentIdResolverUnavailable(error: AppResult.Error): AppResult.Error =
        AppResult.Error(
            message = "Для ссылок Ace Stream content_id сейчас требуется доступный Ace Stream Engine. " +
                "Magnet, .torrent и BitTorrent infohash воспроизводятся встроенным P2P-движком.",
            cause = error.cause
        )

    private suspend fun prepareResolvedAceLiveMetadata(
        rawSource: String,
        metadata: AceTransportMetadata,
        epoch: Long
    ): AppResult<String> {
        metadata.transportFileData?.takeIf(String::isNotBlank)?.let { encoded ->
            when (val decoded = decodeAceTransportFile(encoded)) {
                is P2pResult.Success -> when (
                    val prepared = aceLiveEngine.prepareTransportFile(decoded.data)
                ) {
                    is P2pResult.Success -> {
                        if (streamEpoch.get() != epoch) return supersededResult()
                        log(
                            "embedded_ace_live_transport_resolved",
                            "Ace Live transport descriptor prepared by the embedded runtime"
                        )
                        return AppResult.Success(prepared.data.url)
                    }

                    is P2pResult.Error -> log(
                        "embedded_ace_live_transport_error",
                        prepared.message
                    )
                }

                is P2pResult.Error -> log("engine_content_id_transport_file_error", decoded.message)
            }
        }

        metadata.liveSwarmInfoHash?.let { infoHash ->
            when (val prepared = aceLiveEngine.prepareInfoHash(infoHash)) {
                is P2pResult.Success -> {
                    if (streamEpoch.get() != epoch) return supersededResult()
                    log(
                        "embedded_ace_live_metadata_infohash_resolved",
                        "Resolved live swarm prepared by the embedded Ace Live runtime"
                    )
                    return AppResult.Success(prepared.data.url)
                }

                is P2pResult.Error -> log(
                    "embedded_ace_live_metadata_infohash_error",
                    prepared.message
                )
            }
        }

        return resolveExternalIfCurrent(rawSource, epoch)
    }

    private fun decodeAceTransportFile(encoded: String): P2pResult<ByteArray> = runCatching {
        require(encoded.length <= MAX_ACE_TRANSPORT_FILE_BASE64_CHARS) {
            "Ace transport-file payload is too large"
        }
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        require(bytes.isNotEmpty()) { "Ace transport-file payload is empty" }
        require(bytes.size <= MAX_ACE_TRANSPORT_FILE_BYTES) {
            "Ace transport-file payload exceeds the embedded metadata limit"
        }
        bytes
    }.fold(
        onSuccess = { P2pResult.Success(it) },
        onFailure = { error ->
            P2pResult.Error(
                error.message ?: "Unable to decode Ace transport-file payload",
                error
            )
        }
    )

    private fun materializeAceTransportFile(
        rawSource: String,
        encoded: String
    ): P2pResult<P2pSource.LocalTorrentUri> {
        return runCatching {
            require(encoded.length <= MAX_ACE_TRANSPORT_FILE_BASE64_CHARS) {
                "Ace transport-file payload is too large"
            }

            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            require(bytes.isNotEmpty()) { "Ace transport-file payload is empty" }
            require(bytes.size <= MAX_ACE_TRANSPORT_FILE_BYTES) {
                "Ace transport-file payload exceeds the embedded metadata limit"
            }

            val directory = File(appContext.cacheDir, "p2p/ace-transport").apply {
                if (!exists() && !mkdirs()) {
                    error("Unable to create Ace transport metadata cache")
                }
            }
            val cacheKey = sha256Hex(rawSource)
            val file = File(directory, "$cacheKey.torrent")
            file.writeBytes(bytes)
            P2pSource.LocalTorrentUri(Uri.fromFile(file).toString())
        }.fold(
            onSuccess = { P2pResult.Success(it) },
            onFailure = {
                P2pResult.Error(
                    it.message ?: "Unable to materialize Ace transport-file payload",
                    it
                )
            }
        )
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private fun supersededResult(): AppResult.Error = AppResult.Error(
        "P2P playback request was superseded by a newer player action"
    )

    private suspend fun resolveExternalIfCurrent(
        rawSource: String,
        epoch: Long
    ): AppResult<String> {
        if (streamEpoch.get() != epoch) return supersededResult()
        val fallback = resolveExternal(rawSource)
        return if (streamEpoch.get() == epoch) fallback else supersededResult()
    }

    private suspend fun resolveExternal(rawSource: String): AppResult<String> {
        return when (val result = client.resolveStream(rawSource)) {
            is AppResult.Success -> {
                log("engine_resolved", "Resolved P2P descriptor via external Ace Engine")
                result
            }
            is AppResult.Error -> {
                log("engine_resolve_error", result.message)
                result
            }
            AppResult.Loading -> result
        }
    }

    private suspend fun log(status: String, message: String) {
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = status,
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private companion object {
        const val MAX_ACE_TRANSPORT_FILE_BYTES = 8 * 1024 * 1024
        const val MAX_ACE_TRANSPORT_FILE_BASE64_CHARS = 12 * 1024 * 1024
    }
}
