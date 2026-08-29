package com.iptv.tv.core.p2p

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.RouteInfo
import java.net.DatagramSocket
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

internal enum class AceLivePortMappingProtocol(val wireName: String) {
    PCP("pcp"),
    UPNP_IGD("upnp_igd"),
    NAT_PMP("nat_pmp")
}

internal data class AceLivePortMappingGateway(
    val localAddress: Inet4Address,
    val gatewayAddress: Inet4Address,
    val bindDatagramSocket: (DatagramSocket) -> Unit = {},
    val socketFactory: SocketFactory? = null
)

internal data class AceLivePortMappingRequest(
    val gateway: AceLivePortMappingGateway,
    val internalPort: Int,
    val requestedExternalPort: Int,
    val lifetimeSeconds: Int
) {
    init {
        require(internalPort in 1..65535)
        require(requestedExternalPort in 1..65535)
        require(lifetimeSeconds in 60..86_400)
    }
}

/** One successfully-created mapping. Protocol-specific renewal identity remains encapsulated here. */
internal interface AceLiveMappedPort {
    val protocol: AceLivePortMappingProtocol
    val internalPort: Int
    val externalPort: Int
    val lifetimeSeconds: Int

    /** Returns true only when the same externally advertised port remains mapped. */
    suspend fun renew(): Boolean

    /** Idempotent, bounded by the coordinator even if the gateway is no longer reachable. */
    suspend fun unmap()
}

internal interface AceLivePortMapper {
    val protocol: AceLivePortMappingProtocol

    suspend fun map(request: AceLivePortMappingRequest): AceLiveMappedPort?
}

internal fun interface AceLivePortMappingGatewayResolver {
    fun resolve(): AceLivePortMappingGateway?
}

internal data class AceLivePortMappingPolicy(
    val requestedLifetimeSeconds: Int = 3_600,
    val acquireBudgetMillis: Long = 5_000L,
    val mapperTimeoutMillis: Long = 3_000L,
    val shutdownTimeoutMillis: Long = 1_200L,
    val renewalFractionNumerator: Int = 1,
    val renewalFractionDenominator: Int = 2,
    val minRenewalDelayMillis: Long = 30_000L,
    val maxRenewalDelayMillis: Long = 30L * 60_000L
) {
    init {
        require(requestedLifetimeSeconds in 60..86_400)
        require(acquireBudgetMillis in 250L..15_000L)
        require(mapperTimeoutMillis in 100L..5_000L)
        require(shutdownTimeoutMillis in 100L..5_000L)
        require(renewalFractionNumerator > 0)
        require(renewalFractionDenominator >= renewalFractionNumerator)
        require(minRenewalDelayMillis in 1_000L..maxRenewalDelayMillis)
        require(maxRenewalDelayMillis <= 12L * 60L * 60_000L)
    }
}

internal sealed interface AceLivePortMappingEvent {
    data class Acquired(
        val protocol: AceLivePortMappingProtocol,
        val port: Int,
        val lifetimeSeconds: Int
    ) : AceLivePortMappingEvent

    data class Renewed(
        val protocol: AceLivePortMappingProtocol,
        val port: Int,
        val lifetimeSeconds: Int
    ) : AceLivePortMappingEvent

    data class RenewalFailed(
        val protocol: AceLivePortMappingProtocol,
        val port: Int
    ) : AceLivePortMappingEvent

    data object Unavailable : AceLivePortMappingEvent
}

/**
 * Best-effort NAT reachability for the app-owned Ace peer listener.
 *
 * Acquisition runs on the caller's background scope and never gates tracker/DHT discovery or media
 * startup. Only exact-port mappings are accepted because tracker and DHT announce the already-bound
 * TCP listener port. A gateway that allocates another external port is immediately released and the
 * next protocol is tried. Successful finite leases are renewed on a bounded schedule and explicitly
 * removed on runtime shutdown when the gateway still responds.
 */
internal class AceLivePortMappingCoordinator(
    private val gatewayResolver: AceLivePortMappingGatewayResolver,
    private val mappers: List<AceLivePortMapper>,
    private val policy: AceLivePortMappingPolicy = AceLivePortMappingPolicy()
) {
    init {
        require(mappers.isNotEmpty())
        require(mappers.map(AceLivePortMapper::protocol).distinct().size == mappers.size) {
            "Port mapping protocols must be unique"
        }
    }

    fun start(
        scope: CoroutineScope,
        internalPort: Int,
        observer: (AceLivePortMappingEvent) -> Unit = {}
    ): AceLivePortMappingSession {
        require(internalPort in 1..65535)
        return AceLivePortMappingSession(
            scope = scope,
            gatewayResolver = gatewayResolver,
            mappers = mappers,
            internalPort = internalPort,
            policy = policy,
            observer = observer
        )
    }

    companion object {
        fun default(
            applicationContext: Context,
            okHttpClient: OkHttpClient
        ): AceLivePortMappingCoordinator = AceLivePortMappingCoordinator(
            gatewayResolver = AndroidAceLivePortMappingGatewayResolver(applicationContext),
            mappers = listOf(
                AceLivePcpPortMapper(),
                AceLiveUpnpIgdPortMapper(okHttpClient),
                AceLiveNatPmpPortMapper()
            )
        )
    }
}

internal class AceLivePortMappingSession(
    scope: CoroutineScope,
    private val gatewayResolver: AceLivePortMappingGatewayResolver,
    private val mappers: List<AceLivePortMapper>,
    private val internalPort: Int,
    private val policy: AceLivePortMappingPolicy,
    private val observer: (AceLivePortMappingEvent) -> Unit
) {
    private val closed = AtomicBoolean(false)
    private val activeMapping = AtomicReference<AceLiveMappedPort?>(null)
    private val job: Job = scope.launch { maintainMapping() }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancelAndJoin()
        activeMapping.getAndSet(null)?.let { mapping ->
            withTimeoutOrNull(policy.shutdownTimeoutMillis) {
                runCatching { mapping.unmap() }
            }
        }
    }

    private suspend fun maintainMapping() {
        val gateway = runCatching { gatewayResolver.resolve() }.getOrNull()
        if (gateway == null || closed.get()) {
            emit(AceLivePortMappingEvent.Unavailable)
            return
        }

        val request = AceLivePortMappingRequest(
            gateway = gateway,
            internalPort = internalPort,
            requestedExternalPort = internalPort,
            lifetimeSeconds = policy.requestedLifetimeSeconds
        )
        val acquired = acquireExactMapping(request)
        if (acquired == null || closed.get() || !currentCoroutineContext().isActive) {
            if (acquired != null) release(acquired)
            if (!closed.get()) emit(AceLivePortMappingEvent.Unavailable)
            return
        }

        activeMapping.set(acquired)
        emit(
            AceLivePortMappingEvent.Acquired(
                protocol = acquired.protocol,
                port = acquired.externalPort,
                lifetimeSeconds = acquired.lifetimeSeconds
            )
        )

        try {
            while (!closed.get() && currentCoroutineContext().isActive) {
                delay(renewalDelayMillis(acquired.lifetimeSeconds))
                val renewed = withTimeoutOrNull(policy.mapperTimeoutMillis) {
                    runCatching { acquired.renew() }.getOrDefault(false)
                } ?: false
                if (!renewed) {
                    emit(
                        AceLivePortMappingEvent.RenewalFailed(
                            protocol = acquired.protocol,
                            port = acquired.externalPort
                        )
                    )
                    activeMapping.compareAndSet(acquired, null)
                    release(acquired)
                    return
                }
                emit(
                    AceLivePortMappingEvent.Renewed(
                        protocol = acquired.protocol,
                        port = acquired.externalPort,
                        lifetimeSeconds = acquired.lifetimeSeconds
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private suspend fun acquireExactMapping(
        request: AceLivePortMappingRequest
    ): AceLiveMappedPort? {
        val deadlineNanos = System.nanoTime() + policy.acquireBudgetMillis * NANOS_PER_MILLI
        for (mapper in mappers) {
            if (closed.get() || !currentCoroutineContext().isActive) return null
            val remainingMillis = remainingMillis(deadlineNanos)
            if (remainingMillis <= 0L) break
            val timeoutMillis = min(policy.mapperTimeoutMillis, remainingMillis)
            val mapped = withTimeoutOrNull(timeoutMillis) {
                try {
                    mapper.map(request)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
            } ?: continue
            val exact = mapped.internalPort == request.internalPort &&
                mapped.externalPort == request.requestedExternalPort &&
                mapped.lifetimeSeconds > 0
            if (exact) return mapped
            release(mapped)
        }
        return null
    }

    private suspend fun release(mapping: AceLiveMappedPort) {
        withContext(NonCancellable) {
            withTimeoutOrNull(policy.shutdownTimeoutMillis) {
                runCatching { mapping.unmap() }
            }
        }
    }

    private fun renewalDelayMillis(lifetimeSeconds: Int): Long {
        val requested = lifetimeSeconds.toLong() * 1_000L * policy.renewalFractionNumerator /
            policy.renewalFractionDenominator
        return requested.coerceIn(
            policy.minRenewalDelayMillis,
            policy.maxRenewalDelayMillis
        )
    }

    private fun emit(event: AceLivePortMappingEvent) {
        runCatching { observer(event) }
    }

    private fun remainingMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0L)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Selects only a physical Wi-Fi/Ethernet LAN and pins protocol sockets to that Android Network. */
internal class AndroidAceLivePortMappingGatewayResolver(
    context: Context
) : AceLivePortMappingGatewayResolver {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun resolve(): AceLivePortMappingGateway? {
        val active = connectivityManager.activeNetwork
        val candidates = buildList {
            if (active != null) add(active)
            connectivityManager.allNetworks.forEach { network ->
                if (network != active) add(network)
            }
        }
        for (network in candidates) {
            if (!isSupportedPhysicalNetwork(network)) continue
            val properties = connectivityManager.getLinkProperties(network) ?: continue
            val selected = selectAceLivePortMappingGateway(
                linkAddresses = properties.linkAddresses,
                routes = properties.routes
            ) ?: continue
            return selected.copy(
                bindDatagramSocket = { socket -> network.bindSocket(socket) },
                socketFactory = network.socketFactory
            )
        }
        return null
    }

    private fun isSupportedPhysicalNetwork(network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}

internal fun selectAceLivePortMappingGateway(
    linkAddresses: List<LinkAddress>,
    routes: List<RouteInfo>
): AceLivePortMappingGateway? {
    val localAddress = linkAddresses.asSequence()
        .map(LinkAddress::getAddress)
        .filterIsInstance<Inet4Address>()
        .firstOrNull(::isUsableLocalIpv4)
        ?: return null
    val gatewayAddress = routes.asSequence()
        .filter(RouteInfo::isDefaultRoute)
        .mapNotNull(RouteInfo::getGateway)
        .filterIsInstance<Inet4Address>()
        .firstOrNull(::isUsableGatewayIpv4)
        ?: return null
    return AceLivePortMappingGateway(localAddress, gatewayAddress)
}

private fun isUsableLocalIpv4(address: Inet4Address): Boolean =
    isPrivateOrSharedIpv4(address) &&
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isMulticastAddress

private fun isUsableGatewayIpv4(address: Inet4Address): Boolean =
    isPrivateOrSharedIpv4(address) &&
        !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isMulticastAddress &&
        !address.address.all { octet -> octet.toInt() and 0xff == 255 }

private fun isPrivateOrSharedIpv4(address: Inet4Address): Boolean {
    if (address.isSiteLocalAddress) return true
    val bytes = address.address
    val first = bytes[0].toInt() and 0xff
    val second = bytes[1].toInt() and 0xff
    return first == 100 && second in 64..127
}
