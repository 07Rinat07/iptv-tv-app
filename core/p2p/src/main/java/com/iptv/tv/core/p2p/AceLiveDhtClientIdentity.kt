package com.iptv.tv.core.p2p

/**
 * Process-lifetime identity for outbound read-only Mainline DHT lookups.
 *
 * Routing memory and the short positive-result cache both survive individual lookup rounds. Using a
 * fresh node id for every startup/refill request made those related rounds appear to be unrelated
 * DHT clients while reusing the same routing state. Keep one immutable random id for the process
 * instead. It is deliberately not persisted across app restarts and does not change the existing
 * BEP-43 read-only behavior, query budgets, peer caps or timeout policy.
 */
internal object AceLiveDhtClientIdentity {
    private val processNodeId: AceLiveDhtNodeId = AceLiveDhtNodeId.random()

    fun current(): AceLiveDhtNodeId = processNodeId
}
