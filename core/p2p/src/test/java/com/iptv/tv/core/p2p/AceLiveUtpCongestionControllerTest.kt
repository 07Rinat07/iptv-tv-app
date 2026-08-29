package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUtpCongestionControllerTest {
    @Test
    fun `derived policy stays within existing session safety caps`() {
        val derived = aceLiveUtpCongestionPolicy(
            AceLiveUtpSessionPolicy(maxPayloadBytes = 1_200, maxInFlightBytes = 64 * 1024)
        )

        assertEquals(150, derived.minimumWindowBytes)
        assertEquals(4_800, derived.initialWindowBytes)
        assertEquals(64 * 1024, derived.maximumWindowBytes)
    }

    @Test
    fun `tiny deterministic session policy keeps backwards compatible initial budget`() {
        val derived = aceLiveUtpCongestionPolicy(
            AceLiveUtpSessionPolicy(maxPayloadBytes = 4, maxInFlightBytes = 8)
        )

        assertEquals(4, derived.minimumWindowBytes)
        assertEquals(8, derived.initialWindowBytes)
        assertEquals(8, derived.maximumWindowBytes)
    }

    @Test
    fun `low queue delay grows congestion window with bounded gain`() {
        val controller = controller(initial = 1_200, maximum = 64_000)

        controller.onAcknowledgement(
            acknowledgedBytes = 1_200,
            delaySampleMicros = 5_000,
            nowMillis = 0
        )

        assertEquals(5_000L, controller.baseDelayMicros())
        assertEquals(0L, controller.queueDelayMicros())
        assertEquals(4_200, controller.congestionWindowBytes())
    }

    @Test
    fun `delay above target shrinks congestion window`() {
        val controller = controller(initial = 6_000, maximum = 64_000)
        controller.recordDelaySample(delayMicros = 10_000, nowMillis = 0)

        controller.onAcknowledgement(
            acknowledgedBytes = 3_000,
            delaySampleMicros = 210_000,
            nowMillis = 1_000
        )

        assertEquals(200_000L, controller.queueDelayMicros())
        assertTrue(controller.congestionWindowBytes() < 6_000)
    }

    @Test
    fun `two minute base delay window expires stale minimum`() {
        val controller = controller(initial = 1_200, maximum = 64_000)
        controller.recordDelaySample(delayMicros = 1_000, nowMillis = 0)

        controller.recordDelaySample(delayMicros = 7_000, nowMillis = 121_000)

        assertEquals(7_000L, controller.baseDelayMicros())
        assertEquals(0L, controller.queueDelayMicros())
    }

    @Test
    fun `packet loss halves window and timeout returns to minimum`() {
        val controller = controller(initial = 4_800, maximum = 64_000)

        controller.onPacketLoss()
        assertEquals(2_400, controller.congestionWindowBytes())

        controller.onTimeout()
        assertEquals(150, controller.congestionWindowBytes())
    }

    @Test
    fun `available budget never becomes negative`() {
        val controller = controller(initial = 1_200, maximum = 64_000)

        assertEquals(200, controller.availableWindowBytes(1_000))
        assertEquals(0, controller.availableWindowBytes(1_200))
        assertEquals(0, controller.availableWindowBytes(9_999))
    }

    private fun controller(
        initial: Int,
        maximum: Int
    ) = AceLiveUtpCongestionController(
        AceLiveUtpCongestionPolicy(
            minimumWindowBytes = 150,
            initialWindowBytes = initial,
            maximumWindowBytes = maximum
        )
    )
}
