package com.iptv.tv.core.p2p

import java.io.Closeable
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class AceLiveLsdRequest(
    val swarmKey: AceLiveSwarmKey,
    val announcePort: Int
) {
    init {
        require(announcePort in 1..65535)
    }
}

/**
 * Bounded IPv4 BEP-14 listener/announcer. Returned LAN endpoints remain untrusted hints and still
 * pass through the normal TCP/Ace handshake, connection caps, backoff and reputation pipeline.
 */
internal class AceLiveLocalServiceDiscovery(
    private val lanResolver: AceLiveLsdLanResolver,
    private val multicastLeaseFactory: AceLiveLsdMulticastLeaseFactory =
        AceLiveLsdMulticastLeaseFactory { null },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val policy: AceLiveLsdPolicy = AceLiveLsdPolicy()
) {
    fun start(
        scope: CoroutineScope,
        request: AceLiveLsdRequest,
        onPeer: (AceLiveTcpPeerEndpoint) -> Unit
    ): Closeable {
        val session = Session()
        session.job.set(scope.launch { runSession(request, session, onPeer) })
        return session
    }

    private suspend fun runSession(
        request: AceLiveLsdRequest,
        session: Session,
        onPeer: (AceLiveTcpPeerEndpoint) -> Unit
    ) = withContext(ioDispatcher) {
        val binding = runCatching { lanResolver.resolve() }.getOrNull() ?: return@withContext
        val networkInterface = runCatching {
            NetworkInterface.getByInetAddress(binding.localAddress)
        }.getOrNull() ?: return@withContext
        val groupAddress = InetAddress.getByName(AceLiveLsdCodec.MULTICAST_ADDRESS) as Inet4Address
        val group = InetSocketAddress(groupAddress, AceLiveLsdCodec.MULTICAST_PORT)
        val cookie = randomCookie()
        val multicastLease = runCatching { multicastLeaseFactory.acquire() }.getOrNull()
        try {
            MulticastSocket(null).use { socket ->
                session.socket.set(socket)
                socket.reuseAddress = true
                binding.bindDatagramSocket(socket)
                socket.bind(InetSocketAddress(AceLiveLsdCodec.MULTICAST_PORT))
                socket.networkInterface = networkInterface
                socket.timeToLive = 1
                socket.soTimeout = policy.receiveTimeoutMillis
                socket.joinGroup(group, networkInterface)
                try {
                    val announce = AceLiveLsdCodec.encode(request.swarmKey, request.announcePort, cookie)
                    send(socket, group, announce)
                    var lastAnnounceAtMillis = System.currentTimeMillis()
                    val acceptedEndpoints = linkedSetOf<String>()
                    while (!session.closed.get()) {
                        currentCoroutineContext().ensureActive()
                        val nowMillis = System.currentTimeMillis()
                        if (nowMillis - lastAnnounceAtMillis >= policy.announceIntervalMillis) {
                            send(socket, group, announce)
                            lastAnnounceAtMillis = nowMillis
                        }
                        val packet = receive(socket) ?: continue
                        val source = packet.address as? Inet4Address ?: continue
                        if (!aceLiveLsdSameIpv4Prefix(binding.localAddress, source, binding.prefixLength)) {
                            continue
                        }
                        val decoded = AceLiveLsdCodec.decode(
                            packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        ) ?: continue
                        if (decoded.cookie == cookie || request.swarmKey.toHex() !in decoded.infoHashes) {
                            continue
                        }
                        if (source == binding.localAddress && decoded.port == request.announcePort) continue
                        val endpoint = AceLiveTcpPeerEndpoint(source.hostAddress, decoded.port)
                        val key = "${endpoint.host}:${endpoint.port}"
                        if (key !in acceptedEndpoints) {
                            if (acceptedEndpoints.size >= policy.maxUniquePeers) continue
                            acceptedEndpoints += key
                        }
                        runCatching { onPeer(endpoint) }
                    }
                } finally {
                    runCatching { socket.leaveGroup(group, networkInterface) }
                    session.socket.compareAndSet(socket, null)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // LSD is optional; multicast failure must never fail playback or public discovery.
        } finally {
            runCatching { multicastLease?.close() }
        }
    }

    private fun receive(socket: MulticastSocket): DatagramPacket? {
        val buffer = ByteArray(policy.maxDatagramBytes + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet.takeIf { it.length <= policy.maxDatagramBytes }
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    private fun send(socket: MulticastSocket, target: InetSocketAddress, bytes: ByteArray) {
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, target)) }
    }

    private fun randomCookie(): String {
        val bytes = ByteArray(COOKIE_BYTES).also(secureRandom::nextBytes)
        return buildString(COOKIE_BYTES * 2) {
            bytes.forEach { byte -> append("%02x".format(Locale.US, byte.toInt() and 0xff)) }
        }
    }

    private class Session : Closeable {
        val closed = AtomicBoolean(false)
        val socket = AtomicReference<MulticastSocket?>(null)
        val job = AtomicReference<Job?>(null)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            socket.getAndSet(null)?.close()
            job.getAndSet(null)?.cancel()
        }
    }

    private companion object {
        const val COOKIE_BYTES = 8
    }
}
