package com.iptv.tv.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentPieceWaitBudgetTest {
    @Test
    fun timeoutMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) {
            TorrentPieceWaitBudget(timeoutMillis = 0L)
        }
    }

    @Test
    fun budgetExpiresAtConfiguredTotalDuration() {
        var nowNanos = 10_000_000L
        val budget = TorrentPieceWaitBudget(timeoutMillis = 1_000L) { nowNanos }

        nowNanos += 999_000_000L
        assertFalse(budget.isExpired())

        nowNanos += 1_000_000L
        assertTrue(budget.isExpired())
    }

    @Test
    fun repeatedChecksDoNotRestartBudget() {
        var nowNanos = 0L
        val budget = TorrentPieceWaitBudget(timeoutMillis = 500L) { nowNanos }

        nowNanos = 200_000_000L
        assertFalse(budget.isExpired())

        nowNanos = 400_000_000L
        assertFalse(budget.isExpired())

        nowNanos = 500_000_000L
        assertTrue(budget.isExpired())
    }
}
