package com.iptv.tv.core.p2p

import java.security.SecureRandom

internal fun interface AceDhtExternalAddressObserver {
    fun observe(observedHost: String, responderHost: String)
}

/**
 * Process-lifetime Mainline DHT identity coordinator.
 *
 * A random startup identity remains valid until at least three responders from distinct IPv4 /24
 * networks report the same globally-routable top-level KRPC `ip`. Consensus then creates a BEP-42
 * compatible identity. Existing lookup requests retain their captured node id; only a later lookup
 * obtains the rotated identity from [current]. The identity is deliberately not persisted.
 */
internal class AceLiveDhtClientIdentityManager(
    initialNodeId: AceLiveDhtNodeId,
    private val randomNodeIdBytes: () -> ByteArray
) : AceDhtExternalAddressObserver {
    private val lock = Any()
    private var processNodeId: AceLiveDhtNodeId = initialNodeId
    private var acceptedExternalHost: String? = null
    private val responderPrefixesByObservedHost = LinkedHashMap<String, LinkedHashSet<Int>>()

    fun current(): AceLiveDhtNodeId = synchronized(lock) { processNodeId }

    override fun observe(observedHost: String, responderHost: String) {
        if (!AceDhtNodeIdSecurity.isGloballyRoutableIpv4(observedHost)) return
        if (!AceDhtNodeIdSecurity.isGloballyRoutableIpv4(responderHost)) return
        val responderPrefix = AceDhtNodeIdSecurity.ipv4Prefix24(responderHost) ?: return

        synchronized(lock) {
            if (
                acceptedExternalHost == observedHost &&
                AceDhtNodeIdSecurity.isValidWriteTarget(processNodeId, observedHost)
            ) {
                return
            }

            val prefixes = responderPrefixesByObservedHost.getOrPut(observedHost) {
                while (responderPrefixesByObservedHost.size >= MAX_OBSERVED_HOSTS) {
                    responderPrefixesByObservedHost.remove(
                        responderPrefixesByObservedHost.keys.firstOrNull() ?: break
                    )
                }
                LinkedHashSet()
            }
            prefixes += responderPrefix
            if (prefixes.size < REQUIRED_DISTINCT_RESPONDER_PREFIXES) return

            val randomBytes = randomNodeIdBytes()
            require(randomBytes.size == AceLiveDhtNodeId.BYTES) {
                "DHT identity random source must return ${AceLiveDhtNodeId.BYTES} bytes"
            }
            val compatible = AceDhtNodeIdSecurity.createCompatibleNodeId(
                host = observedHost,
                randomBytes = randomBytes
            ) ?: return
            processNodeId = compatible
            acceptedExternalHost = observedHost
            responderPrefixesByObservedHost.clear()
        }
    }

    private companion object {
        const val REQUIRED_DISTINCT_RESPONDER_PREFIXES = 3
        const val MAX_OBSERVED_HOSTS = 8
    }
}

/** Shared process identity used by both Ace Live and metadata DHT requests. */
internal object AceLiveDhtClientIdentity : AceDhtExternalAddressObserver {
    private val secureRandom = SecureRandom()
    private val manager = AceLiveDhtClientIdentityManager(
        initialNodeId = AceLiveDhtNodeId.random(),
        randomNodeIdBytes = {
            ByteArray(AceLiveDhtNodeId.BYTES).also(secureRandom::nextBytes)
        }
    )

    fun current(): AceLiveDhtNodeId = manager.current()

    override fun observe(observedHost: String, responderHost: String) {
        manager.observe(observedHost, responderHost)
    }
}
