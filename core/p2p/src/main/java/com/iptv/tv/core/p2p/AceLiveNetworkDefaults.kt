package com.iptv.tv.core.p2p

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
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

/** Owns the real TCP port advertised to UDP trackers for the lifetime of one discovery session. */
internal class AceLiveAnnouncePortLease : Closeable {
    private val closed = AtomicBoolean(false)
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0), 4)
    }
    private val acceptThread = Thread(::acceptLoop, "ace-live-announce-port").apply {
        isDaemon = true
        start()
    }

    val port: Int = socket.localPort

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            try {
                socket.accept().use { }
            } catch (_: Throwable) {
                if (closed.get()) return
            }
        }
    }
}
