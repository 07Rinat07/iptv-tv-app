package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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
 * Metadata resolution is intentionally allowed to run in parallel with direct startup, but the
 * resolved transport must not immediately replace an already progressing direct runtime. Starting
 * metadata playback calls into the same embedded engine and closes that runtime, throwing away DHT,
 * peer and startup-buffer progress that may already be close to producing media.
 *
 * The policy is therefore:
 * - direct playback wins immediately if it produces media before the soft window expires;
 * - metadata may resolve at any time, but playback from it starts only when direct startup fails or
 *   [directSoftTimeoutMillis] expires;
 * - if metadata resolves only after the soft window, it can replace a still-pending direct attempt
 *   immediately;
 * - failed metadata never aborts direct startup at the soft deadline. Direct startup keeps its own
 *   absolute bounded timeout, so this optimization does not weaken the existing safety bounds.
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

    // Three independent producers can report into the race: direct startup, metadata resolution and
    // the soft deadline. A small bounded channel avoids producer suspension while the coordinator is
    // cleaning up the losing runtime.
    val events = Channel<StartupRaceEvent<T, M>>(capacity = 3)
    val directJob = launch {
        // Do not wrap directAttempt in a coroutine timeout. The soft window controls only when a
        // usable metadata alternative is allowed to replace it; when metadata is unavailable the
        // direct runtime must retain its existing absolute startup bound.
        val result = runP2pAttempt("Direct Ace Live startup failed") {
            directAttempt()
        }
        events.send(StartupRaceEvent.Direct(result))
    }
    val metadataJob = launch {
        val result = runP2pAttempt("Ace transport metadata resolution failed") {
            metadataResolve()
        }
        events.send(StartupRaceEvent.Metadata(result))
    }
    val softDeadlineJob = launch {
        delay(directSoftTimeoutMillis)
        events.send(StartupRaceEvent.DirectSoftDeadline)
    }

    var directFailure: P2pResult.Error? = null
    var metadataFailure: P2pResult.Error? = null
    var resolvedMetadata: P2pResult.Success<M>? = null
    var directSoftDeadlineReached = false
    var completed: P2pResult<T>? = null

    while (completed == null) {
        when (val event = events.receive()) {
            is StartupRaceEvent.Direct -> when (val result = event.result) {
                is P2pResult.Success -> {
                    // Direct media became usable while it was still allowed to keep the active
                    // runtime. Metadata resolution and the soft timer are no longer needed.
                    metadataJob.cancel()
                    softDeadlineJob.cancel()
                    metadataJob.join()
                    softDeadlineJob.join()
                    completed = result
                }
                is P2pResult.Error -> directFailure = result
            }

            is StartupRaceEvent.Metadata -> when (val result = event.result) {
                is P2pResult.Success -> {
                    // Keep only the descriptor here. Starting metadata playback now would close the
                    // direct runtime; the coordinator below decides when that replacement is safe.
                    resolvedMetadata = result
                }
                is P2pResult.Error -> metadataFailure = result
            }

            StartupRaceEvent.DirectSoftDeadline -> {
                directSoftDeadlineReached = true
            }
        }

        val metadataReady = resolvedMetadata
        if (
            completed == null &&
            metadataReady != null &&
            (directFailure != null || directSoftDeadlineReached)
        ) {
            // Metadata is now the chosen path. Cancel and fully clean the speculative direct runtime
            // before creating the resolved runtime because both attempts share the embedded engine.
            directJob.cancel()
            directJob.join()
            softDeadlineJob.cancel()
            softDeadlineJob.join()
            completed = if (!isCurrent()) {
                superseded()
            } else {
                metadataAttempt(metadataReady.data)
            }
        }

        val directError = directFailure
        val metadataError = metadataFailure
        if (completed == null && directError != null && metadataError != null) {
            softDeadlineJob.cancel()
            softDeadlineJob.join()
            completed = P2pResult.Error(
                message = combinedFailureMessage(directError, metadataError),
                cause = directError.cause ?: metadataError.cause
            )
        }
    }

    completed ?: error("Ace Live startup race ended without a result")
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
    data object DirectSoftDeadline : StartupRaceEvent<Nothing, Nothing>
}
