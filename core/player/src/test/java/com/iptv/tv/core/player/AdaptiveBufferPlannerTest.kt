package com.iptv.tv.core.player

import com.iptv.tv.core.model.BufferProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBufferPlannerTest {

    @Test
    fun lowMemoryDevice_clampsHighProfileAndMemoryBudget() {
        val plan = adaptiveBufferPlan(
            profile = BufferProfile.HIGH,
            device = PlaybackDeviceProfile(
                cpuCount = 2,
                maxMemoryBytes = 192L * 1024L * 1024L
            )
        )

        assertEquals(PlaybackDeviceTier.LOW, plan.deviceTier)
        assertEquals(25_000, plan.config.maxBufferMs)
        assertEquals(8_000, plan.config.minBufferMs)
        assertEquals(24 * 1024 * 1024, plan.config.targetBufferBytes)
        assertEquals(1, plan.recoveryPolicy.maxRecoveryAttempts)
    }

    @Test
    fun additionalPane_alwaysUsesSmallBufferBudget() {
        val plan = adaptiveBufferPlan(
            profile = BufferProfile.HIGH,
            device = PlaybackDeviceProfile(
                cpuCount = 8,
                maxMemoryBytes = 1024L * 1024L * 1024L,
                activePaneCount = 2
            ),
            isAdditionalPane = true
        )

        assertEquals(15_000, plan.config.maxBufferMs)
        assertEquals(4_000, plan.config.minBufferMs)
        assertEquals(10 * 1024 * 1024, plan.config.targetBufferBytes)
        assertTrue(plan.summary.contains("дополнительное окно"))
    }

    @Test
    fun highTierStandardProfile_keepsRequestedTimeWindow() {
        val plan = adaptiveBufferPlan(
            profile = BufferProfile.STANDARD,
            device = PlaybackDeviceProfile(
                cpuCount = 8,
                maxMemoryBytes = 1024L * 1024L * 1024L
            )
        )

        assertEquals(PlaybackDeviceTier.HIGH, plan.deviceTier)
        assertEquals(6_000, plan.config.minBufferMs)
        assertEquals(45_000, plan.config.maxBufferMs)
        assertEquals(96 * 1024 * 1024, plan.config.targetBufferBytes)
    }

    @Test
    fun fourPaneMode_reducesPerPlayerBuffer() {
        val plan = adaptiveBufferPlan(
            profile = BufferProfile.HIGH,
            device = PlaybackDeviceProfile(
                cpuCount = 8,
                maxMemoryBytes = 1024L * 1024L * 1024L,
                activePaneCount = 4
            )
        )

        assertEquals(18_000, plan.config.maxBufferMs)
        assertEquals(6_000, plan.config.minBufferMs)
        assertEquals(12 * 1024 * 1024, plan.config.targetBufferBytes)
    }
}
