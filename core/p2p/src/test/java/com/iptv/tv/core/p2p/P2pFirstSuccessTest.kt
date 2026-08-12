package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pFirstSuccessTest {
    @Test
    fun `bounded first success never exceeds configured concurrency`() = runBlocking {
        val active = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val result = firstSuccessfulP2p(
            items = (1..12).toList(),
            maxConcurrency = 4,
            failureMessage = "all attempts failed"
        ) { item ->
            val current = active.incrementAndGet()
            maxObserved.updateAndGet { previous -> maxOf(previous, current) }
            try {
                delay(20)
                if (item == 7) {
                    P2pResult.Success(item)
                } else {
                    P2pResult.Error("peer $item failed")
                }
            } finally {
                active.decrementAndGet()
            }
        }

        assertTrue(result is P2pResult.Success)
        assertEquals(7, (result as P2pResult.Success).data)
        assertTrue(maxObserved.get() <= 4)
    }

    @Test
    fun `metadata result cancels a slow speculative direct startup`() = runBlocking {
        val directCancelled = AtomicBoolean(false)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 5_000,
            directAttempt = {
                try {
                    delay(30_000)
                    P2pResult.Success("direct")
                } finally {
                    directCancelled.set(true)
                }
            },
            metadataResolve = {
                delay(20)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = { true },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Success)
        assertEquals("metadata:descriptor", (result as P2pResult.Success).data)
        assertTrue(directCancelled.get())
    }

    @Test
    fun `direct startup wins without waiting for slow metadata`() = runBlocking {
        val metadataCancelled = AtomicBoolean(false)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 5_000,
            directAttempt = {
                delay(20)
                P2pResult.Success("direct")
            },
            metadataResolve = {
                try {
                    delay(30_000)
                    P2pResult.Success("descriptor")
                } finally {
                    metadataCancelled.set(true)
                }
            },
            metadataAttempt = { descriptor ->
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = { true },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Success)
        assertEquals("direct", (result as P2pResult.Success).data)
        assertTrue(metadataCancelled.get())
    }

    @Test
    fun `metadata failure does not hard cancel direct at advisory deadline`() = runBlocking {
        val directCompleted = AtomicBoolean(false)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 20,
            directAttempt = {
                delay(80)
                directCompleted.set(true)
                P2pResult.Success("direct-after-advisory-deadline")
            },
            metadataResolve = {
                delay(10)
                P2pResult.Error("metadata unavailable")
            },
            metadataAttempt = { descriptor: String ->
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = { true },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Success)
        assertEquals("direct-after-advisory-deadline", (result as P2pResult.Success).data)
        assertTrue(directCompleted.get())
    }
}
