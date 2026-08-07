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
    private val embeddedEngineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        LibtorrentEmbeddedEngine(context, okHttpClient)
    }
    private val embeddedEngine by embeddedEngineDelegate
    private val streamMutex = Mutex()
    private val streamEpoch = AtomicLong(0L)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var embeddedStreamActive: Boolean = false

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
        return stopEmbeddedForEpoch(epoch)
    }

    override fun releaseTorrentStream() {
        val epoch = streamEpoch.incrementAndGet()
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
        // Do not initialize libtorrent just to stop a stream that never used the embedded backend.
        // Once initialized, however, always forward stopStream(): it also invalidates an in-flight
        // metadata preparation even when no loopback stream has become active yet.
        if (!embeddedEngineDelegate.isInitialized()) {
            embeddedStreamActive = false
            return AppResult.Success(Unit)
        }

        return when (val stopped = embeddedEngine.stopStream()) {
            is P2pResult.Success -> {
                embeddedStreamActive = false
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
    ): AppResult<String> {
        if (streamEpoch.get() != epoch) return supersededResult()

        val parsed = P2pSourceParser.parse(rawSource)
        if (parsed !is P2pResult.Success) {
            return if (streamEpoch.get() == epoch) {
                resolveExternal(rawSource)
            } else {
                supersededResult()
            }
        }

        // Intentionally do not hold streamMutex while prepareStream() performs magnet/.torrent
        // metadata work. stopTorrentStream() and direct-channel switches must be able to enter
        // stopEmbeddedLocked() immediately and invalidate the engine preparation epoch.
        return when (val embedded = embeddedEngine.prepareStream(parsed.data)) {
            is P2pResult.Success -> {
                streamMutex.withLock {
                    if (streamEpoch.get() != epoch) {
                        return@withLock supersededResult()
                    }

                    embeddedStreamActive = true
                    log(
                        status = "embedded_p2p_resolved",
                        message = "Embedded BitTorrent stream prepared: ${embedded.data.file.name}"
                    )
                    AppResult.Success(embedded.data.url)
                }
            }
            is P2pResult.Error -> {
                if (streamEpoch.get() != epoch) return supersededResult()

                embeddedStreamActive = false
                log("embedded_p2p_resolve_error", embedded.message)
                when (val fallback = resolveExternal(rawSource)) {
                    is AppResult.Success -> {
                        if (streamEpoch.get() != epoch) {
                            supersededResult()
                        } else {
                            log("embedded_p2p_fallback", "External Ace fallback resolved BitTorrent source")
                            fallback
                        }
                    }
                    is AppResult.Error -> {
                        if (streamEpoch.get() != epoch) {
                            supersededResult()
                        } else {
                            AppResult.Error(
                                message = buildString {
                                    append("Embedded BitTorrent failed: ")
                                    append(embedded.message)
                                    append("; external Ace fallback failed: ")
                                    append(fallback.message)
                                },
                                cause = fallback.cause ?: embedded.cause
                            )
                        }
                    }
                    AppResult.Loading -> if (streamEpoch.get() == epoch) {
                        AppResult.Loading
                    } else {
                        supersededResult()
                    }
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
