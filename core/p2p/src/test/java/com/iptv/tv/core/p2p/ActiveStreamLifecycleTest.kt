package com.iptv.tv.core.p2p

import java.io.Closeable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveStreamLifecycleTest {
    @Test
    fun replacingDifferentTorrentClosesAndReleasesPrevious() {
        val released = mutableListOf<String>()
        val lifecycle = ActiveStreamLifecycle<String>(released::add)
        val firstServer = RecordingCloseable()
        val secondServer = RecordingCloseable()

        lifecycle.replace(ActiveStreamResources("hash-a", firstServer, "torrent-a"))
        lifecycle.replace(ActiveStreamResources("hash-b", secondServer, "torrent-b"))

        assertEquals(1, firstServer.closeCalls)
        assertEquals(0, secondServer.closeCalls)
        assertEquals(listOf("torrent-a"), released)
        assertTrue(lifecycle.containsTorrent("hash-b"))
        assertFalse(lifecycle.containsTorrent("hash-a"))
    }

    @Test
    fun replacingSameTorrentClosesOldServerButKeepsTorrentSession() {
        val released = mutableListOf<String>()
        val lifecycle = ActiveStreamLifecycle<String>(released::add)
        val firstServer = RecordingCloseable()
        val secondServer = RecordingCloseable()

        lifecycle.replace(ActiveStreamResources("same-hash", firstServer, "handle"))
        lifecycle.replace(ActiveStreamResources("same-hash", secondServer, "handle"))

        assertEquals(1, firstServer.closeCalls)
        assertEquals(0, secondServer.closeCalls)
        assertTrue(released.isEmpty())
        assertTrue(lifecycle.containsTorrent("same-hash"))
    }

    @Test
    fun clearClosesServerAndReleasesTorrentExactlyOnce() {
        val released = mutableListOf<String>()
        val lifecycle = ActiveStreamLifecycle<String>(released::add)
        val server = RecordingCloseable()

        lifecycle.replace(ActiveStreamResources("hash", server, "handle"))
        lifecycle.clear()
        lifecycle.clear()

        assertEquals(1, server.closeCalls)
        assertEquals(listOf("handle"), released)
        assertFalse(lifecycle.containsTorrent("hash"))
    }

    private class RecordingCloseable : Closeable {
        var closeCalls: Int = 0

        override fun close() {
            closeCalls += 1
        }
    }
}
