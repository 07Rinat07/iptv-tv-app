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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
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
        return when (EngineStreamRouting.route(magnetOrAce)) {
            EngineStreamRoute.EXTERNAL_ACE -> {
                runCatching { embeddedEngine.stopStream() }
                resolveExternal(magnetOrAce)
            }
            EngineStreamRoute.EMBEDDED_BITTORRENT -> resolveEmbeddedWithFallback(magnetOrAce)
        }
    }

    private suspend fun resolveEmbeddedWithFallback(rawSource: String): AppResult<String> {
        val parsed = P2pSourceParser.parse(rawSource)
        if (parsed !is P2pResult.Success) {
            return resolveExternal(rawSource)
        }

        return when (val embedded = embeddedEngine.prepareStream(parsed.data)) {
            is P2pResult.Success -> {
                log(
                    status = "embedded_p2p_resolved",
                    message = "Embedded BitTorrent stream prepared: ${embedded.data.file.name}"
                )
                AppResult.Success(embedded.data.url)
            }
            is P2pResult.Error -> {
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
