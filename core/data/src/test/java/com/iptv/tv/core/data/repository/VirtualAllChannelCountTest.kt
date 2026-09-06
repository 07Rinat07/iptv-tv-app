package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.ParentalChannelGateRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualAllChannelCountTest {
    @Test
    fun usesSqlVisibleCountWhenParentalBlockingIsDisabled() {
        val result = virtualAllChannelCount(
            visibleCount = 12_345,
            parentalRows = listOf(
                ParentalChannelGateRow("adult", "Adult", "18+")
            ),
            parentalGate = ParentalChannelGate(
                enabled = false,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult")
            )
        )

        assertEquals(12_345, result)
    }

    @Test
    fun filtersOnlyNarrowRowsWhenParentalBlockingIsEnabled() {
        val result = virtualAllChannelCount(
            visibleCount = 3,
            parentalRows = listOf(
                ParentalChannelGateRow("one", "News", "News"),
                ParentalChannelGateRow("adult", "Adult Cinema", "18+"),
                ParentalChannelGateRow("three", "Travel", "Travel")
            ),
            parentalGate = ParentalChannelGate(
                enabled = true,
                hideAdultChannels = true,
                blockedKeywords = listOf("adult", "18+")
            )
        )

        assertEquals(2, result)
    }

    @Test
    fun disabledParentalGateUsesScalarCountWithoutPageScan() = runTest {
        var countSubscriptions = 0
        var invalidationSubscriptions = 0
        var parentalPageWalks = 0
        val disabledGate = ParentalChannelGate(
            enabled = false,
            hideAdultChannels = true,
            blockedKeywords = listOf("adult")
        )

        val result = observeVirtualAllChannelCount(
            parentalGate = flowOf(disabledGate),
            visibleCount = {
                flow {
                    countSubscriptions += 1
                    emit(12_345)
                }
            },
            parentalInvalidation = {
                flow {
                    invalidationSubscriptions += 1
                    emit(1)
                }
            },
            parentalPages = {
                parentalPageWalks += 1
            }
        ).first()

        assertEquals(12_345, result)
        assertEquals(1, countSubscriptions)
        assertEquals(0, invalidationSubscriptions)
        assertEquals(0, parentalPageWalks)
    }

    @Test
    fun enabledParentalGateAggregatesAcrossPagedSnapshot() = runTest {
        var countSubscriptions = 0
        var invalidationSubscriptions = 0
        var parentalPageWalks = 0
        val enabledGate = ParentalChannelGate(
            enabled = true,
            hideAdultChannels = true,
            blockedKeywords = listOf("adult", "18+")
        )

        val result = observeVirtualAllChannelCount(
            parentalGate = flowOf(enabledGate),
            visibleCount = {
                flow {
                    countSubscriptions += 1
                    emit(4)
                }
            },
            parentalInvalidation = {
                flow {
                    invalidationSubscriptions += 1
                    emit(4)
                }
            },
            parentalPages = { visitor ->
                parentalPageWalks += 1
                visitor(
                    listOf(
                        ParentalChannelGateRow("one", "News", "News", id = 10),
                        ParentalChannelGateRow("adult", "Adult Cinema", "18+", id = 11)
                    )
                )
                visitor(
                    listOf(
                        ParentalChannelGateRow("three", "Travel", "Travel", id = 12),
                        ParentalChannelGateRow("four", "Kids", "Family", id = 13)
                    )
                )
            }
        ).first()

        assertEquals(3, result)
        assertEquals(0, countSubscriptions)
        assertEquals(1, invalidationSubscriptions)
        assertEquals(1, parentalPageWalks)
    }

    @Test
    fun parentalInvalidationRetriggersPagedSnapshotCount() = runTest {
        var parentalPageWalks = 0
        val enabledGate = ParentalChannelGate(
            enabled = true,
            hideAdultChannels = true,
            blockedKeywords = listOf("adult")
        )

        val results = observeVirtualAllChannelCount(
            parentalGate = flowOf(enabledGate),
            visibleCount = { flowOf(0) },
            parentalInvalidation = { flowOf(1, 2) },
            parentalPages = { visitor ->
                parentalPageWalks += 1
                if (parentalPageWalks == 1) {
                    visitor(listOf(ParentalChannelGateRow("safe", "News", "News", id = 10)))
                } else {
                    visitor(
                        listOf(
                            ParentalChannelGateRow("safe", "News", "News", id = 10),
                            ParentalChannelGateRow("travel", "Travel", "Travel", id = 11)
                        )
                    )
                }
            }
        ).take(2).toList()

        assertEquals(listOf(1, 2), results)
        assertEquals(2, parentalPageWalks)
    }
}
