package com.iptv.tv.core.data.repository

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-safe counterpart of [loadEpgCandidatesFreshFirst]. It preserves the same source
 * precedence while allowing Room-backed stale capture and fresh loading without blocking bridges.
 */
internal suspend fun <T> loadEpgCandidatesFreshFirstSuspending(
    candidates: List<String>,
    loadFresh: suspend (url: String) -> T,
    captureStaleFallback: suspend (url: String) -> T?,
    onLoadError: (Exception) -> Unit
): List<EpgCandidateLoad<T>> {
    val fresh = ArrayList<EpgCandidateLoad<T>>(candidates.size)
    val deferredStale = ArrayList<EpgCandidateLoad<T>>(candidates.size)

    for (url in candidates) {
        try {
            fresh += EpgCandidateLoad(
                url = url,
                value = loadFresh(url),
                servedFromStaleFallback = false
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            onLoadError(failure)
            if (failure is EpgLowMemoryException) {
                deferredStale.clear()
            } else {
                captureStaleFallback(url)?.let { stale ->
                    deferredStale += EpgCandidateLoad(
                        url = url,
                        value = stale,
                        servedFromStaleFallback = true
                    )
                }
            }
        }
    }

    return fresh + deferredStale
}
