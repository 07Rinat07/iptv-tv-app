package com.iptv.tv.core.p2p

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class P2pActiveStreamTest {
    @Test
    fun closeReleasesServerAndHandleExactlyOnce() {
        var serverCloseCount = 0
        var removeCount = 0
        val stream = P2pActiveStream(
            server = Closeable { serverCloseCount += 1 },
            handle = "handle-1",
            removeHandle = { handle ->
                assertEquals("handle-1", handle)
                removeCount += 1
            }
        )

        stream.close()
        stream.close()

        assertEquals(1, serverCloseCount)
        assertEquals(1, removeCount)
    }

    @Test
    fun handleIsReleasedEvenWhenServerCloseFails() {
        val serverFailure = IllegalStateException("server close failed")
        var removeCount = 0
        val stream = P2pActiveStream(
            server = Closeable { throw serverFailure },
            handle = 42,
            removeHandle = { removeCount += 1 }
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            stream.close()
        }

        assertSame(serverFailure, thrown)
        assertEquals(1, removeCount)
    }

    @Test
    fun preservesBothCleanupFailures() {
        val serverFailure = IllegalStateException("server close failed")
        val handleFailure = IllegalArgumentException("handle remove failed")
        val stream = P2pActiveStream(
            server = Closeable { throw serverFailure },
            handle = Unit,
            removeHandle = { throw handleFailure }
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            stream.close()
        }

        assertSame(serverFailure, thrown)
        assertEquals(1, thrown.suppressed.size)
        assertSame(handleFailure, thrown.suppressed[0])
    }
}
