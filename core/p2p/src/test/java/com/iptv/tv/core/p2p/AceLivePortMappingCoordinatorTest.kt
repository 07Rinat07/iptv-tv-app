package com.iptv.tv.core.p2p

import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AceLivePortMappingCoordinatorTest {
    private val gateway = AceLivePortMappingGateway(
        localAddress = ipv4("192.168.1.20"),
        gatewayAddress = ipv4("192.168.1.1")
    )

    @Test
    fun `different external port is released before exact fallback is accepted`() = runTest {
        val wrong = FakeMappedPort(AceLivePortMappingProtocol.PCP, 41000, 41001)
        val exact = FakeMappedPort(AceLivePortMappingProtocol.UPNP_IGD, 41000, 41000)
        val events = mutableListOf<AceLivePortMappingEvent>()
        val coordinator = AceLivePortMappingCoordinator(
            gatewayResolver = AceLivePortMappingGatewayResolver { gateway },
            mappers = listOf(
                FakeMapper(AceLivePortMappingProtocol.PCP) { wrong },
                FakeMapper(AceLivePortMappingProtocol.UPNP_IGD) { exact }
            ),
            policy = testPolicy()
        )

        val session = coordinator.start(backgroundScope, 41000, events::add)
        runCurrent()

        assertEquals(1, wrong.unmapCalls.get())
        assertEquals(0, exact.unmapCalls.get())
        assertTrue(events.single() is AceLivePortMappingEvent.Acquired)
        assertEquals(AceLivePortMappingProtocol.UPNP_IGD, (events.single() as AceLivePortMappingEvent.Acquired).protocol)

        session.close()
        assertEquals(1, exact.unmapCalls.get())
    }

    @Test
    fun `finite lease renews on bounded schedule and unmaps once on close`() = runTest {
        val exact = FakeMappedPort(AceLivePortMappingProtocol.NAT_PMP, 42000, 42000)
        val events = mutableListOf<AceLivePortMappingEvent>()
        val coordinator = AceLivePortMappingCoordinator(
            gatewayResolver = AceLivePortMappingGatewayResolver { gateway },
            mappers = listOf(FakeMapper(AceLivePortMappingProtocol.NAT_PMP) { exact }),
            policy = testPolicy()
        )

        val session = coordinator.start(backgroundScope, 42000, events::add)
        runCurrent()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(1, exact.renewCalls.get())
        assertTrue(events.any { it is AceLivePortMappingEvent.Renewed })

        session.close()
        session.close()
        assertEquals(1, exact.unmapCalls.get())
    }

    @Test
    fun `mapping failure stays background and does not escape caller`() = runTest {
        val entered = AtomicInteger(0)
        val coordinator = AceLivePortMappingCoordinator(
            gatewayResolver = AceLivePortMappingGatewayResolver { gateway },
            mappers = listOf(
                object : AceLivePortMapper {
                    override val protocol = AceLivePortMappingProtocol.PCP
                    override suspend fun map(request: AceLivePortMappingRequest): AceLiveMappedPort? {
                        entered.incrementAndGet()
                        awaitCancellation()
                    }
                }
            ),
            policy = testPolicy()
        )

        val session = coordinator.start(backgroundScope, 43000)
        assertEquals(0, entered.get())
        runCurrent()
        assertEquals(1, entered.get())
        session.close()
    }

    @Test
    fun `missing active gateway reports unavailable without invoking mapper`() = runTest {
        val mapCalls = AtomicInteger(0)
        val events = mutableListOf<AceLivePortMappingEvent>()
        val coordinator = AceLivePortMappingCoordinator(
            gatewayResolver = AceLivePortMappingGatewayResolver { null },
            mappers = listOf(
                FakeMapper(AceLivePortMappingProtocol.PCP) {
                    mapCalls.incrementAndGet()
                    null
                }
            ),
            policy = testPolicy()
        )

        val session = coordinator.start(backgroundScope, 44000, events::add)
        runCurrent()

        assertEquals(0, mapCalls.get())
        assertEquals(listOf(AceLivePortMappingEvent.Unavailable), events)
        session.close()
    }

    private fun testPolicy() = AceLivePortMappingPolicy(
        requestedLifetimeSeconds = 60,
        acquireBudgetMillis = 1_000L,
        mapperTimeoutMillis = 500L,
        shutdownTimeoutMillis = 500L,
        minRenewalDelayMillis = 1_000L,
        maxRenewalDelayMillis = 1_000L
    )

    private class FakeMapper(
        override val protocol: AceLivePortMappingProtocol,
        private val block: suspend (AceLivePortMappingRequest) -> AceLiveMappedPort?
    ) : AceLivePortMapper {
        override suspend fun map(request: AceLivePortMappingRequest): AceLiveMappedPort? = block(request)
    }

    private class FakeMappedPort(
        override val protocol: AceLivePortMappingProtocol,
        override val internalPort: Int,
        override val externalPort: Int,
        override val lifetimeSeconds: Int = 60,
        private val renewResult: Boolean = true
    ) : AceLiveMappedPort {
        val renewCalls = AtomicInteger(0)
        val unmapCalls = AtomicInteger(0)

        override suspend fun renew(): Boolean {
            renewCalls.incrementAndGet()
            return renewResult
        }

        override suspend fun unmap() {
            unmapCalls.incrementAndGet()
        }
    }

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
