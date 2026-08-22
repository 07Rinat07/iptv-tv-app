package com.iptv.tv.core.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VirtualPlaylistAggregateFlowTest {
    @Test
    fun concurrentConsumersShareOneColdUpstreamSubscription() = runTest {
        var subscriptions = 0
        val upstreamStarted = CompletableDeferred<Unit>()
        val keepUpstreamAlive = CompletableDeferred<Unit>()
        val shared = flow {
            subscriptions++
            emit(7)
            upstreamStarted.complete(Unit)
            keepUpstreamAlive.await()
        }.shareVirtualAggregate(VirtualPlaylistAggregateScope.forTest(backgroundScope))

        val first = backgroundScope.launch { shared.collect { } }
        upstreamStarted.await()
        val second = backgroundScope.launch { shared.collect { } }
        runCurrent()

        assertEquals(1, subscriptions)

        first.cancel()
        second.cancel()
        keepUpstreamAlive.complete(Unit)
    }

    @Test
    fun structurallyUnchangedValuesAreCoalescedBeforePublication() = runTest {
        val upstream = MutableSharedFlow<List<Int>>(extraBufferCapacity = 4)
        val shared = upstream.shareVirtualAggregate(
            VirtualPlaylistAggregateScope.forTest(backgroundScope)
        )
        val emissions = mutableListOf<List<Int>>()
        backgroundScope.launch {
            shared.take(2).toList(emissions)
        }
        runCurrent()

        upstream.emit(listOf(1, 2, 3))
        upstream.emit(listOf(1, 2, 3))
        upstream.emit(listOf(1, 2, 4))
        runCurrent()

        assertEquals(listOf(listOf(1, 2, 3), listOf(1, 2, 4)), emissions)
    }
}
