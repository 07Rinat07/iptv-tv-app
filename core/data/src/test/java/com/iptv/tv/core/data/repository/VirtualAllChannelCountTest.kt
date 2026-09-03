package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.ParentalChannelGateRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    fun disabledParentalGateDoesNotSubscribeFullParentalRows() = runTest {
        var countSubscriptions = 0
        var parentalRowSubscriptions = 0
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
            parentalRows = {
                flow {
                    parentalRowSubscriptions += 1
                    emit(listOf(ParentalChannelGateRow("adult", "Adult", "18+")))
                }
            }
        ).first()

        assertEquals(12_345, result)
        assertEquals(1, countSubscriptions)
        assertEquals(0, parentalRowSubscriptions)
    }

    @Test
    fun enabledParentalGateSubscribesRowsInsteadOfScalarCount() = runTest {
        var countSubscriptions = 0
        var parentalRowSubscriptions = 0
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
                    emit(3)
                }
            },
            parentalRows = {
                flow {
                    parentalRowSubscriptions += 1
                    emit(
                        listOf(
                            ParentalChannelGateRow("one", "News", "News"),
                            ParentalChannelGateRow("adult", "Adult Cinema", "18+"),
                            ParentalChannelGateRow("three", "Travel", "Travel")
                        )
                    )
                }
            }
        ).first()

        assertEquals(2, result)
        assertEquals(0, countSubscriptions)
        assertEquals(1, parentalRowSubscriptions)
    }
}
