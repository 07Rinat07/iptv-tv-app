package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.EngineRepository
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the first P2P selection responsive while coalescing a burst of subsequent rapid-zap
 * selections. The Player cancels both the previous primary resolution and any pending retry job on
 * every new channel request; therefore a request waiting in [delay] is cancelled before it can enter
 * the expensive P2P resolver when an even newer selection arrives.
 *
 * Direct HTTP IPTV never reaches [EngineRepository.resolveTorrentStream], so this policy adds no
 * latency to ordinary IPTV playback.
 */
@Singleton
class CoalescingEngineRepository @Inject constructor(
    private val delegate: HybridEngineRepositoryImpl,
) : EngineRepository by delegate {
    private val rapidZapGate = P2pRapidZapGate()

    override suspend fun resolveTorrentStream(magnetOrAce: String): AppResult<String> {
        val settleDelayMs = rapidZapGate.onRequest(monotonicNowMs())
        if (settleDelayMs > 0L) {
            delay(settleDelayMs)
        }
        return delegate.resolveTorrentStream(magnetOrAce)
    }

    override suspend fun stopTorrentStream(): AppResult<Unit> {
        rapidZapGate.reset()
        return delegate.stopTorrentStream()
    }

    override fun releaseTorrentStream() {
        rapidZapGate.reset()
        delegate.releaseTorrentStream()
    }

    private fun monotonicNowMs(): Long = System.nanoTime() / NANOS_PER_MILLISECOND

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

/**
 * Pure, thread-safe timing policy for P2P rapid-zap coalescing.
 *
 * - first request after idle: no delay;
 * - another request within [rapidWindowMs]: cancellable [settleDelayMs] delay;
 * - request after the rapid window: no delay;
 * - reset/clock re-anchor: no delay.
 */
internal class P2pRapidZapGate(
    private val rapidWindowMs: Long = DEFAULT_RAPID_WINDOW_MS,
    private val settleDelayMs: Long = DEFAULT_SETTLE_DELAY_MS,
) {
    private val lastRequestAtMs = AtomicLong(NO_REQUEST)

    init {
        require(rapidWindowMs >= 0L) { "rapidWindowMs must be >= 0" }
        require(settleDelayMs >= 0L) { "settleDelayMs must be >= 0" }
    }

    fun onRequest(nowMs: Long): Long {
        val previousMs = lastRequestAtMs.getAndSet(nowMs)
        if (previousMs == NO_REQUEST || nowMs < previousMs) {
            return 0L
        }

        return if (nowMs - previousMs <= rapidWindowMs) settleDelayMs else 0L
    }

    fun reset() {
        lastRequestAtMs.set(NO_REQUEST)
    }

    internal companion object {
        const val DEFAULT_RAPID_WINDOW_MS = 1_200L
        const val DEFAULT_SETTLE_DELAY_MS = 550L
        private const val NO_REQUEST = Long.MIN_VALUE
    }
}
