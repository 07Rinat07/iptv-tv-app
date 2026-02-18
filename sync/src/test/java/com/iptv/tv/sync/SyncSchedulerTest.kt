package com.iptv.tv.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncSchedulerTest {
    @Test
    fun normalizeSyncHours_picksSupportedIntervals() {
        assertEquals(12, SyncScheduler.normalizeSyncHours(0))
        assertEquals(6, SyncScheduler.normalizeSyncHours(5))
        assertEquals(12, SyncScheduler.normalizeSyncHours(11))
        assertEquals(24, SyncScheduler.normalizeSyncHours(23))
        assertEquals(24, SyncScheduler.normalizeSyncHours(48))
    }
}

