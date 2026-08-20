package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
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
 * Metadata resolution is intentionally allowed to run in parallel with direct startup, but the
 * resolved transport must not immediately replace an already progressing direct runtime. Starting
 * metadata playback calls into the same embedded engine and closes that runtime, throwing away DHT,
 * peer and startup-buffer progress that may already be close to producing media.
 *
 * The policy is therefore:
 * - direct playback wins immediately if it produces media before the soft window expires;
 * - metadata may resolve at any time, but playback from it starts only when direct startup fails or
 *   [directSoftTimeoutMillis] expires;
 * - a direct runtime that is currently connected/qualified may receive a caller-defined bounded
 *   grace window instead of being torn down exactly at the soft boundary; the predicate must read
 *   the active direct runtime, never a historical/shared diagnostic timeline;
 * - if metadata resolves only after the soft window, it can replace a still-pending direct attempt
 *   immediately;
 * - failed metadata never aborts direct startup at the soft deadline. Direct startup keeps its own
 *   absolute bounded timeout, so this optimization does not weaken the existing safety bounds;
 * - when a successfully resolved descriptor starts but its derived live swarm cannot produce a
 *   stream, retry the speculative direct identity once. The retry uses the same soft window and may
 *   receive the same fixed qualification grace only while its current runtime has peer progress.
 *   This keeps a volatile/dead derived swarm from turning metadata-resolution success into a
 *   playback regression, while preserving the caller's absolute preparation bound.
 */
internal suspend fun <T, M> raceP2pDirectAgainstMetadata(
    directSoftTimeoutMillis: Long,
    directProgressGraceMillis: Long = 0L,
    directHasQualificationProgress: () -> Boolean = { false },
    onDirectProgressGraceStarted: () -> Unit = {},
    onDirectRetryStarted: () -> Unit = {},
    onDirectRetryProgressGraceStarted: () -> Unit = {},
    directAttempt: suspend () -> P2pResult<T>,
    metadataResolve: suspend () -> P2pResult<M>,
    metadataAttempt: suspend (M) -> P2pResult<T>,
    isCurrent: () -> Boolean,
    superseded: () -> P2pResult.Error,
    combinedFailureMessage: (P2pResult.Error, P2pResult.Error) -> String,
    fallbackCombinedFailureMessage: (P2pResult.Error, P2pResult.Error) -> String =
        combinedFailureMessage
): P2pResult<T> = supervisorScope {
    require(directSoftTimeoutMillis > 0L) { "directSoftTimeoutMillis must be positive" }
    require(directProgressGraceMillis >= 0L) {
        "directProgressGraceMillis must be non-negative"
    }
    require(directProgressGraceMillis <= Long.MAX_VALUE - directSoftTimeoutMillis) {
        "direct progress deadline overflows"
    }
    val directProgressDeadlineMillis = directSoftTimeoutMillis + directProgressGraceMillis

    // Four independent producers can report into the race: direct startup, metadata resolution,
    // the soft deadline and the optional progress-grace deadline. A small bounded channel avoids
    // producer suspension while the coordinator is cleaning up the losing runtime.
    val events = Channel<StartupRaceEvent<T, M>>(capacity = 4)
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
    val progressDeadlineJob = launch {
        delay(directProgressDeadlineMillis)
        events.send(StartupRaceEvent.DirectProgressDeadline)
    }

    var directFailure: P2pResult.Error? = null
    var metadataFailure: P2pResult.Error? = null
    var resolvedMetadata: P2pResult.Success<M>? = null
    var directSoftDeadlineReached = false
    var directProgressDeadlineReached = false
    var progressGraceReported = false
    var completed: P2pResult<T>? = null

    while (completed == null) {
        when (val event = events.receive()) {
            is StartupRaceEvent.Direct -> when (val result = event.result) {
                is P2pResult.Success -> {
                    metadataJob.cancel()
                    softDeadlineJob.cancel()
                    progressDeadlineJob.cancel()
                    metadataJob.join()
                    softDeadlineJob.join()
                    progressDeadlineJob.join()
                    completed = result
                }
                is P2pResult.Error -> directFailure = result
            }

            is StartupRaceEvent.Metadata -> when (val result = event.result) {
                is P2pResult.Success -> resolvedMetadata = result
                is P2pResult.Error -> metadataFailure = result
            }

            StartupRaceEvent.DirectSoftDeadline -> directSoftDeadlineReached = true
            StartupRaceEvent.DirectProgressDeadline -> directProgressDeadlineReached = true
        }

        val metadataReady = resolvedMetadata
        val deferForQualifiedDirect =
            directFailure == null &&
                directSoftDeadlineReached &&
                !directProgressDeadlineReached &&
                isCurrent() &&
                directHasQualificationProgress()
        if (deferForQualifiedDirect && !progressGraceReported) {
            progressGraceReported = true
            runCatching(onDirectProgressGraceStarted)
        }
        if (
            completed == null &&
            metadataReady != null &&
            (directFailure != null || (directSoftDeadlineReached && !deferForQualifiedDirect))
        ) {
            directJob.cancel()
            directJob.join()
            softDeadlineJob.cancel()
            progressDeadlineJob.cancel()
            softDeadlineJob.join()
            progressDeadlineJob.join()
            completed = if (!isCurrent()) {
                superseded()
            } else {
                when (val metadataStartup = metadataAttempt(metadataReady.data)) {
                    is P2pResult.Success -> metadataStartup
                    is P2pResult.Error -> {
                        if (!isCurrent()) {
                            superseded()
                        } else {
                            runCatching(onDirectRetryStarted)
                            when (
                                val directRetry = runDirectRetryWithQualificationGrace(
                                    directSoftTimeoutMillis = directSoftTimeoutMillis,
                                    directProgressGraceMillis = directProgressGraceMillis,
                                    directHasQualificationProgress = directHasQualificationProgress,
                                    onDirectRetryProgressGraceStarted =
                                        onDirectRetryProgressGraceStarted,
                                    directAttempt = directAttempt,
                                    isCurrent = isCurrent,
                                    superseded = superseded
                                )
                            ) {
                                is P2pResult.Success ->
                                    if (isCurrent()) directRetry else superseded()
                                is P2pResult.Error ->
                                    if (!isCurrent()) {
                                        superseded()
                                    } else {
                                        P2pResult.Error(
                                            message = fallbackCombinedFailureMessage(
                                                directRetry,
                                                metadataStartup
                                            ),
                                            cause = metadataStartup.cause ?: directRetry.cause
                                        )
                                    }
                            }
                        }
                    }
                }
            }
        }

        val directError = directFailure
        val metadataError = metadataFailure
        if (completed == null && directError != null && metadataError != null) {
            softDeadlineJob.cancel()
            progressDeadlineJob.cancel()
            softDeadlineJob.join()
            progressDeadlineJob.join()
            completed = P2pResult.Error(
                message = combinedFailureMessage(directError, metadataError),
                cause = directError.cause ?: metadataError.cause
            )
        }
    }

    completed
}

/**
 * Applies the handoff soft window to a fallback retry without cancelling its runtime at the exact
 * boundary. The child is always joined or cancelled before this function returns, so a failed or
 * superseded retry cannot retain loopback/TCP resources.
 */
private suspend fun <T> runDirectRetryWithQualificationGrace(
    directSoftTimeoutMillis: Long,
    directProgressGraceMillis: Long,
    directHasQualificationProgress: () -> Boolean,
    onDirectRetryProgressGraceStarted: () -> Unit,
    directAttempt: suspend () -> P2pResult<T>,
    isCurrent: () -> Boolean,
    superseded: () -> P2pResult.Error
): P2pResult<T> = supervisorScope {
    val directRetryJob = async {
        runP2pAttempt("Direct Ace Live fallback retry failed") {
            directAttempt()
        }
    }

    val softResult = withTimeoutOrNull(directSoftTimeoutMillis) {
        directRetryJob.await()
    }
    if (softResult != null || directRetryJob.isCompleted) {
        return@supervisorScope softResult ?: directRetryJob.await()
    }

    if (!isCurrent()) {
        directRetryJob.cancelAndJoin()
        return@supervisorScope superseded()
    }
    if (
        directProgressGraceMillis == 0L ||
        !directHasQualificationProgress()
    ) {
        directRetryJob.cancelAndJoin()
        return@supervisorScope P2pResult.Error(
            "Timed out waiting for $directSoftTimeoutMillis ms"
        )
    }

    runCatching(onDirectRetryProgressGraceStarted)
    val graceResult = withTimeoutOrNull(directProgressGraceMillis) {
        directRetryJob.await()
    }
    if (graceResult != null || directRetryJob.isCompleted) {
        return@supervisorScope graceResult ?: directRetryJob.await()
    }

    directRetryJob.cancelAndJoin()
    if (!isCurrent()) {
        superseded()
    } else {
        P2pResult.Error(DIRECT_RETRY_PROGRESS_TIMEOUT_MESSAGE)
    }
}

private suspend fun <T> runP2pAttempt(
    fallbackMessage: String,
    block: suspend () -> P2pResult<T>
): P2pResult<T> = try {
    block()
} catch (cancelled: CancellationException) {
    currentCoroutineContext().ensureActive()
    P2pResult.Error(cancelled.message ?: fallbackMessage, cancelled)
} catch (error: Throwable) {
    P2pResult.Error(error.message ?: fallbackMessage, error)
}

private sealed interface StartupRaceEvent<out T, out M> {
    data class Direct<T>(val result: P2pResult<T>) : StartupRaceEvent<T, Nothing>
    data class Metadata<M>(val result: P2pResult<M>) : StartupRaceEvent<Nothing, M>
    data object DirectSoftDeadline : StartupRaceEvent<Nothing, Nothing>
    data object DirectProgressDeadline : StartupRaceEvent<Nothing, Nothing>
}

private const val DIRECT_RETRY_PROGRESS_TIMEOUT_MESSAGE =
    "failure=qualified_peer_no_media; " +
        "Direct Ace Live fallback made peer connection progress but did not produce media " +
        "before the bounded qualification grace expired"
