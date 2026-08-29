package com.iptv.tv.core.p2p

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

internal object AceLiveNetworkDefaults {
    /**
     * Independent public Mainline DHT routers used only to enter the distributed routing table.
     *
     * Keep more than one operator in this bounded list: field diagnostics showed cold/startup
     * lookups spending roughly half their KRPC requests on failed nodes while returning zero or one
     * swarm peer. The iterative walker already caps DNS resolutions, concurrent queries, total
     * queries and wall-clock time, so adding independent bootstrap roots improves the chance of
     * entering healthy routing-table regions without widening any startup budget.
     */
    val dhtBootstrapNodes = listOf(
        AceLiveDhtBootstrapNode("router.bittorrent.com", 6881),
        AceLiveDhtBootstrapNode("router.utorrent.com", 6881),
        AceLiveDhtBootstrapNode("dht.transmissionbt.com", 6881),
        AceLiveDhtBootstrapNode("dht.aelitis.com", 6881),
        AceLiveDhtBootstrapNode("dht.libtorrent.org", 25401)
    )
    const val publicTracker = "udp://t1.torrentstream.org:2710/announce"
}

/**
 * Owns the real TCP port advertised to trackers for the lifetime of one live runtime.
 *
 * Accepted sockets are offered to the runtime instead of being closed immediately. Returning
 * `true` transfers socket ownership to the callback; `false` (or an exception) closes the socket
 * here. The small kernel backlog is only a burst buffer: the TCP pool applies its own stricter
 * active/inbound caps before an accepted connection can participate in the swarm.
 */
internal class AceLiveAnnouncePortLease private constructor(
    private val onAcceptedSocket: (Socket) -> Boolean,
    enablePortMapping: Boolean
) : Closeable {
    constructor() : this(onAcceptedSocket = { false }, enablePortMapping = false)

    constructor(onAcceptedSocket: (Socket) -> Boolean) :
        this(onAcceptedSocket = onAcceptedSocket, enablePortMapping = true)

    private val closed = AtomicBoolean(false)
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), ACCEPT_BACKLOG)
    }

    val port: Int = socket.localPort

    // Only the callback-owning live runtime is eligible to advertise this listener through LSD.
    // Metadata-only temporary listeners use the no-arg constructor and are never registered.
    private val localServiceDiscoveryRegistration = if (enablePortMapping) {
        AceLiveLsdRuntimeRegistry.registerListener(port)
    } else {
        null
    }

    // Only the callback-owning live runtime reaches this constructor. Metadata probes use the
    // no-arg constructor and therefore never expose a useless temporary listener through NAT.
    private val portMappingLease = if (enablePortMapping) {
        AceLivePortMappingRuntime.start(port)
    } else {
        null
    }
    private val acceptThread = Thread(::acceptLoop, "ace-live-announce-port").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        runCatching { localServiceDiscoveryRegistration?.close() }
        runCatching { portMappingLease?.close() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val accepted = try {
                socket.accept()
            } catch (_: Throwable) {
                if (closed.get()) return
                continue
            }
            val transferred = runCatching { onAcceptedSocket(accepted) }.getOrDefault(false)
            if (!transferred) runCatching { accepted.close() }
        }
    }

    private companion object {
        const val ACCEPT_BACKLOG = 4
    }
}
