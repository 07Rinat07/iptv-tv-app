package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.EngineRepository
import com.iptv.tv.core.engine.data.EngineStreamClient
import com.iptv.tv.core.model.EngineStatus
import com.iptv.tv.core.p2p.LibtorrentEmbeddedEngine
import com.iptv.tv.core.p2p.P2pResult
import com.iptv.tv.core.p2p.P2pSourceParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/**
 * Player-facing engine repository.
 *
 * BitTorrent metadata is resolved by the in-process libtorrent backend first. True Ace
 * descriptors remain on the external Ace Engine integration. If embedded BitTorrent cannot
 * prepare a stream, the existing Ace path is retained as a compatibility fallback.
 */
@Singleton
class HybridEngineRepositoryImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val client: EngineStreamClient,
    private val syncLogDao: SyncLogDao,
    okHttpClient: OkHttpClient
) : EngineRepository {
    private val embeddedEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LibtorrentEmbeddedEngine(context, okHttpClient)
    }
    private val streamMutex = Mutex()
    private val streamEpoch = AtomicLong(0L)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val embeddedStreamActive = AtomicBoolean(false)

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
            EngineStreamRoute.EXTERNAL_ACE -> {
                stopEmbeddedForEpoch(epoch)
                if (streamEpoch.get() != epoch) {
                    supersededResult()
                } else {
                    resolveExternal(magnetOrAce)
                }
            }
            EngineStreamRoute.EMBEDDED_BITTORRENT -> resolveEmbeddedWithFallback(magnetOrAce, epoch)
        }
    }

    override suspend fun stopTorrentStream(): AppResult<Unit> {
        val epoch = streamEpoch.incrementAndGet()

        // An in-flight magnet/torrent preparation can spend tens of seconds inside native
        // metadata resolution while holding streamMutex. If it has not published an active
        // stream yet, do not make direct IPTV playback wait for that obsolete work. The epoch
        // invalidation above guarantees the stale preparation tears itself down when it returns.
        if (!embeddedStreamActive.get()) return AppResult.Success(Unit)

        return stopEmbeddedForEpoch(epoch)
    }

    override fun releaseTorrentStream() {
        val epoch = streamEpoch.incrementAndGet()

        // Same rule as stopTorrentStream(): no active stream means there is nothing synchronous
        // to close. Any preparation already in progress is invalidated by the new epoch and will
        // destroy its late result before exposing it to the player.
        if (!embeddedStreamActive.get()) return

        cleanupScope.launch {
            stopEmbeddedForEpoch(epoch)
        }
    }

    private suspend fun stopEmbeddedForEpoch(epoch: Long): AppResult<Unit> = streamMutex.withLock {
        if (streamEpoch.get() != epoch) {
            return@withLock AppResult.Success(Unit)
        }
        stopEmbeddedLocked()
    }

    private suspend fun stopEmbeddedLocked(): AppResult<Unit> {
        if (!embeddedStreamActive.get()) return AppResult.Success(Unit)

        return when (val stopped = embeddedEngine.stopStream()) {
            is P2pResult.Success -> {
                embeddedStreamActive.set(false)
                log("embedded_p2p_stopped", "Embedded BitTorrent stream stopped")
                AppResult.Success(Unit)
            }
            is P2pResult.Error -> {
                log("embedded_p2p_stop_error", stopped.message)
                AppResult.Error(stopped.message, stopped.cause)
            }
        }
    }

    private suspend fun resolveEmbeddedWithFallback(
        rawSource: String,
        epoch: Long
    ): AppResult<String> = streamMutex.withLock {
        if (streamEpoch.get() != epoch) return@withLock supersededResult()

        val parsed = P2pSourceParser.parse(rawSource)
        if (parsed !is P2pResult.Success) {
            return@withLock if (streamEpoch.get() == epoch) {
                resolveExternal(rawSource)
            } else {
                supersededResult()
            }
        }

        when (val embedded = embeddedEngine.prepareStream(parsed.data)) {
            is P2pResult.Success -> {
                if (streamEpoch.get() != epoch) {
                    embeddedStreamActive.set(true)
                    stopEmbeddedLocked()
                    return@withLock supersededResult()
                }

                embeddedStreamActive.set(true)
                log(
                    status = "embedded_p2p_resolved",
                    message = "Embedded BitTorrent stream prepared: ${embedded.data.file.name}"
                )
                AppResult.Success(embedded.data.url)
            }
            is P2pResult.Error -> {
                embeddedStreamActive.set(false)
                if (streamEpoch.get() != epoch) return@withLock supersededResult()

                log("embedded_p2p_resolve_error", embedded.message)
                when (val fallback = resolveExternal(rawSource)) {
                    is AppResult.Success -> {
                        log("embedded_p2p_fallback", "External Ace fallback resolved BitTorrent source")
                        fallback
                    }
                    is AppResult.Error -> AppResult.Error(
                        message = buildString {
                            append("Embedded BitTorrent failed: ")
                            append(embedded.message)
                            append("; external Ace fallback failed: ")
                            append(fallback.message)
                        },
                        cause = fallback.cause ?: embedded.cause
                    )
                    AppResult.Loading -> AppResult.Loading
                }
            }
        }
    }

    private fun supersededResult(): AppResult.Error = AppResult.Error(
        "P2P playback request was superseded by a newer player action"
    )

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
}
