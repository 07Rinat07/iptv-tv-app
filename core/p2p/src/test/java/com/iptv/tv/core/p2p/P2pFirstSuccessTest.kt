package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2pFirstSuccessTest {
    @Test
    fun `bounded first success never exceeds configured concurrency`() = runTest {
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
    fun `metadata resolved early preserves direct startup inside soft window`() = runTest {
        val metadataResolved = AtomicBoolean(false)
        val metadataAttempts = AtomicInteger(0)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 5_000,
            directAttempt = {
                delay(4_000)
                P2pResult.Success("direct")
            },
            metadataResolve = {
                delay(100)
                metadataResolved.set(true)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataAttempts.incrementAndGet()
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = { true },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(metadataResolved.get())
        assertTrue(result is P2pResult.Success)
        assertEquals("direct", (result as P2pResult.Success).data)
        assertEquals(0, metadataAttempts.get())
    }

    @Test
    fun `metadata replaces pending direct startup at soft deadline`() = runTest {
        val directCancelled = AtomicBoolean(false)
        val metadataAttempts = AtomicInteger(0)

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
                delay(100)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataAttempts.incrementAndGet()
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
        assertEquals(1, metadataAttempts.get())
    }

    @Test
    fun `direct failure starts already resolved metadata without waiting for soft deadline`() = runTest {
        val metadataAttempts = AtomicInteger(0)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 8_000,
            directAttempt = {
                delay(1_000)
                P2pResult.Error("direct failed")
            },
            metadataResolve = {
                delay(100)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataAttempts.incrementAndGet()
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
        assertEquals(1, metadataAttempts.get())
    }

    @Test
    fun `direct startup wins without waiting for slow metadata`() = runTest {
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
    fun `metadata failure does not hard cancel direct at soft deadline`() = runTest {
        val directCompleted = AtomicBoolean(false)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 5_000,
            directAttempt = {
                delay(8_000)
                directCompleted.set(true)
                P2pResult.Success("direct-after-soft-deadline")
            },
            metadataResolve = {
                delay(100)
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
        assertEquals("direct-after-soft-deadline", (result as P2pResult.Success).data)
        assertTrue(directCompleted.get())
    }

    @Test
    fun `metadata resolving after soft deadline preempts still pending direct`() = runTest {
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
                delay(7_000)
                P2pResult.Success("late-descriptor")
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
        assertEquals("metadata:late-descriptor", (result as P2pResult.Success).data)
        assertTrue(directCancelled.get())
    }

    @Test
    fun `metadata handoff refuses to start when request was superseded`() = runTest {
        val metadataAttempts = AtomicInteger(0)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 5_000,
            directAttempt = {
                delay(30_000)
                P2pResult.Success("direct")
            },
            metadataResolve = {
                delay(100)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataAttempts.incrementAndGet()
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = { false },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Error)
        assertEquals("superseded", (result as P2pResult.Error).message)
        assertEquals(0, metadataAttempts.get())
    }

    @Test
    fun `qualified direct startup is not torn down at the soft deadline`() = runTest {
        val directProgress = AtomicBoolean(false)
        val graceStarted = AtomicInteger(0)
        val metadataAttempts = AtomicInteger(0)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 8_000,
            directProgressGraceMillis = 4_000,
            directHasQualificationProgress = directProgress::get,
            onDirectProgressGraceStarted = { graceStarted.incrementAndGet() },
            directAttempt = {
                delay(7_800)
                directProgress.set(true)
                delay(700)
                P2pResult.Success("direct-qualified")
            },
            metadataResolve = {
                delay(100)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataAttempts.incrementAndGet()
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = { true },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Success)
        assertEquals("direct-qualified", (result as P2pResult.Success).data)
        assertEquals(1, graceStarted.get())
        assertEquals(0, metadataAttempts.get())
    }

    @Test
    fun `qualified direct grace remains bounded before metadata handoff`() = runTest {
        val directProgress = AtomicBoolean(false)
        val directCancelled = AtomicBoolean(false)
        var metadataStartedAt = -1L

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 8_000,
            directProgressGraceMillis = 4_000,
            directHasQualificationProgress = directProgress::get,
            directAttempt = {
                try {
                    delay(7_800)
                    directProgress.set(true)
                    awaitCancellation()
                } finally {
                    directCancelled.set(true)
                }
            },
            metadataResolve = {
                delay(100)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataStartedAt = testScheduler.currentTime
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
        assertEquals(12_000L, metadataStartedAt)
    }

    @Test
    fun `stale direct progress does not open grace after disconnect`() = runTest {
        val directProgress = AtomicBoolean(false)
        val graceStarted = AtomicInteger(0)
        var metadataStartedAt = -1L

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 8_000,
            directProgressGraceMillis = 2_000,
            directHasQualificationProgress = directProgress::get,
            onDirectProgressGraceStarted = { graceStarted.incrementAndGet() },
            directAttempt = {
                directProgress.set(true)
                delay(7_900)
                directProgress.set(false)
                awaitCancellation()
            },
            metadataResolve = {
                delay(100)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataStartedAt = testScheduler.currentTime
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = { true },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Success)
        assertEquals(8_000L, metadataStartedAt)
        assertEquals(0, graceStarted.get())
    }

    @Test
    fun `superseded qualified direct does not receive progress grace`() = runTest {
        val current = AtomicBoolean(true)
        val graceStarted = AtomicInteger(0)
        val metadataAttempts = AtomicInteger(0)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 8_000,
            directProgressGraceMillis = 2_000,
            directHasQualificationProgress = { true },
            onDirectProgressGraceStarted = { graceStarted.incrementAndGet() },
            directAttempt = {
                delay(7_900)
                current.set(false)
                awaitCancellation()
            },
            metadataResolve = {
                delay(100)
                P2pResult.Success("descriptor")
            },
            metadataAttempt = { descriptor ->
                metadataAttempts.incrementAndGet()
                P2pResult.Success("metadata:$descriptor")
            },
            isCurrent = current::get,
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Error)
        assertEquals("superseded", (result as P2pResult.Error).message)
        assertEquals(8_000L, testScheduler.currentTime)
        assertEquals(0, graceStarted.get())
        assertEquals(0, metadataAttempts.get())
    }
}
