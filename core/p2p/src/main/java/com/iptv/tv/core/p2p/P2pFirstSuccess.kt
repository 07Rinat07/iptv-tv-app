package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs P2P attempts with a bounded worker pool and returns as soon as one attempt succeeds.
 *
 * A live-TV startup path should not wait for every slow peer/provider after a usable result already
 * exists. Losers are cancelled immediately. Ordinary attempt failures stay isolated from siblings,
 * while parent coroutine cancellation is always propagated.
 */
internal suspend fun <I, O> firstSuccessfulP2p(
    items: List<I>,
    maxConcurrency: Int,
    failureMessage: String,
    attempt: suspend (I) -> P2pResult<O>
): P2pResult<O> = supervisorScope {
    require(items.isNotEmpty()) { "At least one P2P attempt is required" }
    require(maxConcurrency > 0) { "maxConcurrency must be positive" }

    val workerCount = minOf(maxConcurrency, items.size)
    val nextIndex = AtomicInteger(0)
    val results = Channel<P2pResult<O>>(capacity = items.size)
    val workers = List(workerCount) {
        launch {
            while (true) {
                val index = nextIndex.getAndIncrement()
                if (index >= items.size) break
                val result = runP2pAttempt(failureMessage) {
                    attempt(items[index])
                }
                results.send(result)
                if (result is P2pResult.Success) break
            }
        }
    }

    var lastFailure: P2pResult.Error? = null
    repeat(items.size) {
        when (val result = results.receive()) {
            is P2pResult.Success -> {
                workers.forEach { worker -> worker.cancel() }
                workers.joinAll()
                return@supervisorScope result
            }
            is P2pResult.Error -> lastFailure = result
        }
    }

    workers.joinAll()
    P2pResult.Error(
        message = failureMessage,
        cause = lastFailure?.cause
    )
}

/**
 * Races speculative direct Ace Live startup against transport-metadata resolution.
 *
 * - direct playback wins immediately if it produces media first;
 * - resolved metadata wins immediately if it becomes available first, after the speculative direct
 *   runtime is cancelled and fully cleaned up;
 * - a failed metadata provider does not abort a still-promising direct attempt;
 * - the direct path has a short soft deadline so a dead swarm cannot impose the full runtime startup
 *   timeout on every channel switch.
 */
internal suspend fun <T, M> raceP2pDirectAgainstMetadata(
    directSoftTimeoutMillis: Long,
    directAttempt: suspend () -> P2pResult<T>,
    metadataResolve: suspend () -> P2pResult<M>,
    metadataAttempt: suspend (M) -> P2pResult<T>,
    isCurrent: () -> Boolean,
    superseded: () -> P2pResult.Error,
    combinedFailureMessage: (P2pResult.Error, P2pResult.Error) -> String
): P2pResult<T> = supervisorScope {
    require(directSoftTimeoutMillis > 0L) { "directSoftTimeoutMillis must be positive" }

    val events = Channel<StartupRaceEvent<T, M>>(capacity = 2)
    val directJob = launch {
        val result = withTimeoutOrNull(directSoftTimeoutMillis) {
            directAttempt()
        } ?: P2pResult.Error(
            "Direct Ace Live startup exceeded the ${directSoftTimeoutMillis} ms soft deadline"
        )
        events.send(StartupRaceEvent.Direct(result))
    }
    val metadataJob = launch {
        val result = runP2pAttempt("Ace transport metadata resolution failed") {
            metadataResolve()
        }
        events.send(StartupRaceEvent.Metadata(result))
    }

    var directFailure: P2pResult.Error? = null
    var metadataFailure: P2pResult.Error? = null

    while (true) {
        when (val event = events.receive()) {
            is StartupRaceEvent.Direct -> when (val result = event.result) {
                is P2pResult.Success -> {
                    metadataJob.cancel()
                    metadataJob.join()
                    return@supervisorScope result
                }
                is P2pResult.Error -> directFailure = result
            }

            is StartupRaceEvent.Metadata -> when (val result = event.result) {
                is P2pResult.Success -> {
                    directJob.cancel()
                    directJob.join()
                    if (!isCurrent()) return@supervisorScope superseded()
                    return@supervisorScope metadataAttempt(result.data)
                }
                is P2pResult.Error -> metadataFailure = result
            }
        }

        val directError = directFailure
        val metadataError = metadataFailure
        if (directError != null && metadataError != null) {
            return@supervisorScope P2pResult.Error(
                message = combinedFailureMessage(directError, metadataError),
                cause = directError.cause ?: metadataError.cause
            )
        }
    }
}

private suspend fun <T> runP2pAttempt(
    fallbackMessage: String,
    block: suspend () -> P2pResult<T>
): P2pResult<T> = try {
    block()
} catch (cancelled: CancellationException) {
    // A local timeout may use CancellationException while the parent remains active. Convert that
    // case into an ordinary attempt failure, but never swallow cancellation of the caller.
    currentCoroutineContext().ensureActive()
    P2pResult.Error(cancelled.message ?: fallbackMessage, cancelled)
} catch (error: Throwable) {
    P2pResult.Error(error.message ?: fallbackMessage, error)
}

private sealed interface StartupRaceEvent<out T, out M> {
    data class Direct<T>(val result: P2pResult<T>) : StartupRaceEvent<T, Nothing>
    data class Metadata<M>(val result: P2pResult<M>) : StartupRaceEvent<Nothing, M>
}
