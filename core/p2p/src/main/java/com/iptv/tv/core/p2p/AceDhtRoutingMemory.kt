package com.iptv.tv.core.p2p

/**
 * Small engine-owned memory of DHT nodes that answered a valid KRPC request.
 *
 * The memory deliberately stores routing contacts, not swarm peers. A contact is admitted only
 * after its response has passed transaction and packet decoding, is expired by a monotonic TTL,
 * and is bounded by LRU capacity. One [AceLiveEmbeddedEngine] instance owns one memory so useful
 * routing state survives channel runtimes without becoming an unbounded process-global cache.
 */
class AceDhtRoutingMemory internal constructor(
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private val ttlNanos = ttlMillis * NANOS_PER_MILLI
    private val lock = Any()
    private val contacts = LinkedHashMap<String, RememberedContact>()

    init {
        require(maxNodes in 1..MAX_ALLOWED_NODES) {
            "DHT routing memory maxNodes must be in 1..$MAX_ALLOWED_NODES"
        }
        require(ttlMillis in 1..MAX_TTL_MILLIS) {
            "DHT routing memory TTL must be in 1..$MAX_TTL_MILLIS ms"
        }
    }

    internal fun recentContacts(limit: Int): List<AceLiveDhtNodeContact> {
        require(limit >= 0) { "DHT routing seed limit must be non-negative" }
        if (limit == 0) return emptyList()

        return synchronized(lock) {
            pruneExpiredLocked(clockNanos())
            val selectedNodeIds = HashSet<AceLiveDhtNodeId>()
            val selectedNetworks = HashSet<String>()
            contacts.values
                .toList()
                .asReversed()
                .filter { remembered ->
                    selectedNodeIds.add(remembered.contact.nodeId) &&
                        selectedNetworks.add(
                            endpointDiversityKey(remembered.contact.endpoint)
                        )
                }
                .take(limit)
                .map(RememberedContact::contact)
        }
    }

    internal fun remember(contact: AceLiveDhtNodeContact) = synchronized(lock) {
        val nowNanos = clockNanos()
        pruneExpiredLocked(nowNanos)
        val key = endpointKey(contact.endpoint)
        contacts.remove(key)
        contacts.entries.removeAll { (_, remembered) ->
            remembered.contact.nodeId == contact.nodeId
        }
        val diversityKey = endpointDiversityKey(contact.endpoint)
        while (
            contacts.values.count { remembered ->
                endpointDiversityKey(remembered.contact.endpoint) == diversityKey
            } >= MAX_CONTACTS_PER_NETWORK
        ) {
            val oldestSameNetwork = contacts.entries.firstOrNull { (_, remembered) ->
                endpointDiversityKey(remembered.contact.endpoint) == diversityKey
            }?.key ?: break
            contacts.remove(oldestSameNetwork)
        }
        while (contacts.size >= maxNodes) {
            val eldestKey = contacts.keys.firstOrNull() ?: break
            contacts.remove(eldestKey)
        }
        contacts[key] = RememberedContact(contact = contact, lastSeenNanos = nowNanos)
    }

    internal fun forget(contact: AceLiveDhtNodeContact) = synchronized(lock) {
        val key = endpointKey(contact.endpoint)
        val remembered = contacts[key] ?: return@synchronized
        if (remembered.contact.nodeId == contact.nodeId) contacts.remove(key)
    }

    private fun pruneExpiredLocked(nowNanos: Long) {
        val iterator = contacts.entries.iterator()
        while (iterator.hasNext()) {
            val remembered = iterator.next().value
            val ageNanos = if (nowNanos >= remembered.lastSeenNanos) {
                nowNanos - remembered.lastSeenNanos
            } else {
                Long.MAX_VALUE
            }
            if (ageNanos >= ttlNanos) iterator.remove()
        }
    }

    private fun endpointKey(endpoint: AceLiveTcpPeerEndpoint): String =
        "${endpoint.host.lowercase()}:${endpoint.port}"

    private fun endpointDiversityKey(endpoint: AceLiveTcpPeerEndpoint): String {
        val octets = endpoint.host.split('.')
        return if (
            octets.size == 4 &&
            octets.all { octet -> octet.toIntOrNull()?.let { it in 0..255 } == true }
        ) {
            "${octets[0]}.${octets[1]}.${octets[2]}.0/24"
        } else {
            endpoint.host.lowercase()
        }
    }

    private data class RememberedContact(
        val contact: AceLiveDhtNodeContact,
        val lastSeenNanos: Long
    )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val DEFAULT_MAX_NODES = 32
        const val MAX_ALLOWED_NODES = 256
        const val MAX_CONTACTS_PER_NETWORK = 2
        const val DEFAULT_TTL_MILLIS = 5 * 60 * 1_000L
        const val MAX_TTL_MILLIS = 60 * 60 * 1_000L
    }
}
