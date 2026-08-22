package com.iptv.tv.core.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn

/**
 * Shares one virtual-playlist aggregate across concurrent UI/count/summary consumers.
 *
 * Virtual decorators are singletons, but their Room/DataStore-backed flows are cold. Without a
 * shared boundary, observing the playlist row, opening its catalog and requesting a summary can
 * subscribe to the same upstream independently. Keep one upstream while consumers are active,
 * drop structurally identical aggregate emissions, and clear replay once the short grace window
 * expires so a later one-shot summary cannot receive stale data.
 */
internal fun <T> Flow<T>.shareVirtualAggregate(scope: CoroutineScope): SharedFlow<T> =
    distinctUntilChanged().shareIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(
            stopTimeoutMillis = VIRTUAL_AGGREGATE_STOP_TIMEOUT_MS,
            replayExpirationMillis = 0L
        ),
        replay = 1
    )

internal const val VIRTUAL_AGGREGATE_STOP_TIMEOUT_MS = 5_000L
