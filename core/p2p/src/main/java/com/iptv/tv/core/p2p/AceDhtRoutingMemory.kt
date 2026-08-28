package com.iptv.tv.core.p2p

/**
 * Engine-owned memory of DHT nodes that answered a valid KRPC request.
 *
 * The memory deliberately stores routing contacts, not swarm peers. A contact is admitted only
 * after its response has passed transaction and packet decoding, is expired by a bounded TTL,
 * and is kept under LRU/network-diversity limits. Optional persistence lets useful routing state
 * survive process restarts without turning it into a source of truth: failed warm contacts are
 * forgotten immediately and bootstrap remains available on every lookup.
 */
class AceDhtRoutingMemory internal constructor(
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clockNanos: () -> Long = System::nanoTime,
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val persistence: AceDhtRoutingPersistence? = null
) {
    private val ttlMillis = ttlMillis
    private val ttlNanos = ttlMillis * NANOS_PER_MILLI
    private val lock = Any()
    private val contacts = LinkedHashMap<String, RememberedContact>()
    private var dirty = false

    init {
        require(maxNodes in 1..MAX_ALLOWED_NODES) {
            "DHT routing memory maxNodes must be in 1..$MAX_ALLOWED_NODES"
        }
        require(ttlMillis in 1..MAX_TTL_MILLIS) {
            "DHT routing memory TTL must be in 1..$MAX_TTL_MILLIS ms"
        }
        restorePersistedContacts()
    }

    internal fun recentContacts(limit: Int): List<AceLiveDhtNodeContact> {
        require(limit >= 0) { "DHT routing seed limit must be non-negative" }
        if (limit == 0) return emptyList()

        return synchronized(lock) {
            pruneExpiredLocked(clockNanos(), wallClockMillis())
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
        val nowEpochMillis = wallClockMillis()
        pruneExpiredLocked(nowNanos, nowEpochMillis)
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
        contacts[key] = RememberedContact(
            contact = contact,
            lastSeenNanos = nowNanos,
            lastSeenEpochMillis = nowEpochMillis
        )
        dirty = true
    }

    internal fun forget(contact: AceLiveDhtNodeContact) = synchronized(lock) {
        val key = endpointKey(contact.endpoint)
        val remembered = contacts[key] ?: return@synchronized
        if (remembered.contact.nodeId == contact.nodeId) {
            contacts.remove(key)
            dirty = true
        }
    }

    /** Flushes only when routing state changed; safe to call at the end of every bounded lookup. */
    internal fun flush() {
        val persistenceTarget = persistence ?: return
        val snapshot = synchronized(lock) {
            pruneExpiredLocked(clockNanos(), wallClockMillis())
            if (!dirty) return@synchronized null
            contacts.values.map { remembered ->
                AceDhtPersistedContact(
                    contact = remembered.contact,
                    lastSeenEpochMillis = remembered.lastSeenEpochMillis
                )
            }.also { dirty = false }
        } ?: return

        runCatching { persistenceTarget.save(snapshot) }
            .onFailure {
                synchronized(lock) { dirty = true }
            }
    }

    private fun restorePersistedContacts() {
        val persisted = runCatching { persistence?.load().orEmpty() }.getOrDefault(emptyList())
        if (persisted.isEmpty()) return
        val nowNanos = clockNanos()
        val nowEpochMillis = wallClockMillis()
        synchronized(lock) {
            persisted
                .asSequence()
                .filter { item ->
                    persistedAgeMillis(item.lastSeenEpochMillis, nowEpochMillis) < ttlMillis
                }
                .sortedBy(AceDhtPersistedContact::lastSeenEpochMillis)
                .toList()
                .takeLast(maxNodes)
                .forEach { item ->
                    val ageMillis = persistedAgeMillis(item.lastSeenEpochMillis, nowEpochMillis)
                    val ageNanos = ageMillis.coerceAtMost(Long.MAX_VALUE / NANOS_PER_MILLI) * NANOS_PER_MILLI
                    val restoredNanos = if (nowNanos >= ageNanos) nowNanos - ageNanos else 0L
                    insertRestoredLocked(
                        contact = item.contact,
                        lastSeenNanos = restoredNanos,
                        lastSeenEpochMillis = item.lastSeenEpochMillis
                    )
                }
            pruneExpiredLocked(nowNanos, nowEpochMillis)
            // Loaded state is already on disk. Subsequent network evidence decides whether to flush.
            dirty = false
        }
    }

    private fun insertRestoredLocked(
        contact: AceLiveDhtNodeContact,
        lastSeenNanos: Long,
        lastSeenEpochMillis: Long
    ) {
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
        contacts[key] = RememberedContact(
            contact = contact,
            lastSeenNanos = lastSeenNanos,
            lastSeenEpochMillis = lastSeenEpochMillis
        )
    }

    private fun pruneExpiredLocked(nowNanos: Long, nowEpochMillis: Long) {
        val iterator = contacts.entries.iterator()
        var removed = false
        while (iterator.hasNext()) {
            val remembered = iterator.next().value
            val monotonicAgeNanos = if (nowNanos >= remembered.lastSeenNanos) {
                nowNanos - remembered.lastSeenNanos
            } else {
                Long.MAX_VALUE
            }
            val wallAgeMillis = persistedAgeMillis(remembered.lastSeenEpochMillis, nowEpochMillis)
            if (monotonicAgeNanos >= ttlNanos || wallAgeMillis >= ttlMillis) {
                iterator.remove()
                removed = true
            }
        }
        if (removed) dirty = true
    }

    private fun persistedAgeMillis(lastSeenEpochMillis: Long, nowEpochMillis: Long): Long =
        if (nowEpochMillis >= lastSeenEpochMillis) {
            nowEpochMillis - lastSeenEpochMillis
        } else {
            Long.MAX_VALUE
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
        val lastSeenNanos: Long,
        val lastSeenEpochMillis: Long
    )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val DEFAULT_MAX_NODES = 128
        const val MAX_ALLOWED_NODES = 512
        const val MAX_CONTACTS_PER_NETWORK = 2
        const val DEFAULT_TTL_MILLIS = 6 * 60 * 60 * 1_000L
        const val MAX_TTL_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
