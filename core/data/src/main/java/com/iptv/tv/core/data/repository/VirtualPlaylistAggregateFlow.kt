package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.DispatcherProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn

/** Application-process scope for shared virtual-playlist aggregates. */
@Singleton
class VirtualPlaylistAggregateScope private constructor(
    internal val coroutineScope: CoroutineScope
) {
    @Inject
    constructor(dispatcherProvider: DispatcherProvider) : this(
        CoroutineScope(SupervisorJob() + dispatcherProvider.default)
    )

    internal companion object {
        fun forTest(coroutineScope: CoroutineScope): VirtualPlaylistAggregateScope =
            VirtualPlaylistAggregateScope(coroutineScope)
    }
}

/**
 * Shares one virtual-playlist aggregate across concurrent UI/count/summary consumers.
 *
 * Virtual decorators are singletons, but their Room/DataStore-backed flows are cold. Without a
 * shared boundary, observing the playlist row, opening its catalog and requesting a summary can
 * subscribe to the same upstream independently. Keep one upstream while consumers are active,
 * drop structurally identical aggregate emissions, and clear replay once the short grace window
 * expires so a later one-shot summary cannot receive stale data.
 */
internal fun <T> Flow<T>.shareVirtualAggregate(
    aggregateScope: VirtualPlaylistAggregateScope
): SharedFlow<T> = distinctUntilChanged().shareIn(
    scope = aggregateScope.coroutineScope,
    started = SharingStarted.WhileSubscribed(
        stopTimeoutMillis = VIRTUAL_AGGREGATE_STOP_TIMEOUT_MS,
        replayExpirationMillis = 0L
    ),
    replay = 1
)

internal const val VIRTUAL_AGGREGATE_STOP_TIMEOUT_MS = 5_000L
