package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentPriorityWindowTrackerTest {
    @Test
    fun firstRangeHasNoPreviousWindowToReset() {
        val tracker = TorrentPriorityWindowTracker()
        val first = window(first = 2, last = 4, priorityLast = 10)

        assertNull(tracker.replace(first))
    }

    @Test
    fun seekResetsMostRecentlyScheduledReadWindow() {
        val tracker = TorrentPriorityWindowTracker()
        val requestWindow = window(first = 0, last = 3, priorityLast = 9)
        val latestReadWindow = window(first = 8, last = 8, priorityLast = 14)
        val seekWindow = window(first = 120, last = 123, priorityLast = 129)

        tracker.replace(requestWindow)
        tracker.record(latestReadWindow)

        assertEquals(latestReadWindow, tracker.replace(seekWindow))
    }

    @Test
    fun consecutiveRangeReplacementsReturnTheImmediatelyPreviousWindow() {
        val tracker = TorrentPriorityWindowTracker()
        val first = window(first = 5, last = 6, priorityLast = 12)
        val second = window(first = 40, last = 41, priorityLast = 47)
        val third = window(first = 80, last = 81, priorityLast = 87)

        tracker.replace(first)
        assertEquals(first, tracker.replace(second))
        assertEquals(second, tracker.replace(third))
    }

    private fun window(first: Int, last: Int, priorityLast: Int) = TorrentPieceWindow(
        firstRequestedPiece = first,
        lastRequestedPiece = last,
        lastPriorityPiece = priorityLast
    )
}
