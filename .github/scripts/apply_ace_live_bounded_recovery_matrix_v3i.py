from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, found {count}: {old[:180]!r}")
    target.write_text(text.replace(old, new, 1))


# Recovery must ignore live windows that are completely behind the authoritative cursor.
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveWindowScheduler.kt",
    '''    /**
     * Lowest piece that any unchoked peer currently advertises. Recovery may use this only after
     * explicitly deciding that [nextNeeded] has been evicted everywhere.
     */
    fun lowestAvailablePiece(): Long? = peers.values
        .asSequence()
        .filter { it.unchoked }
        .map { it.minPiece }
        .minOrNull()
''',
    '''    /**
     * Lowest piece strictly ahead of [cursor] that any unchoked peer can still serve.
     *
     * Peers whose advertised window is entirely behind the authoritative cursor are intentionally
     * ignored. Otherwise one lagging/stale peer can mask a useful future live window and prevent the
     * recovery layer from surfacing an evicted-gap cursor advance.
     */
    fun lowestAvailablePieceAfter(cursor: Long): Long? {
        require(cursor >= 0) { "cursor must be non-negative" }
        if (cursor == Long.MAX_VALUE) return null
        return peers.values
            .asSequence()
            .filter { it.unchoked }
            .filter { it.maxPiece > cursor }
            .map { peer -> maxOf(peer.minPiece, cursor + 1) }
            .minOrNull()
    }

    /** Lowest advertised piece across all requestable peers, retained for diagnostics/tests. */
    fun lowestAvailablePiece(): Long? = peers.values
        .asSequence()
        .filter { it.unchoked }
        .map { it.minPiece }
        .minOrNull()
''',
)

replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveRecoveryCoordinator.kt",
    '''            val lowestAvailable = scheduler.lowestAvailablePiece()
            if (lowestAvailable != null && lowestAvailable > nextNeeded) {
                val distance = lowestAvailable - nextNeeded
''',
    '''            val lowestAvailable = scheduler.lowestAvailablePieceAfter(nextNeeded)
            if (lowestAvailable != null) {
                val distance = lowestAvailable - nextNeeded
''',
)

# Extend pure recovery coverage with stale/future/choked/multi-window cases.
recovery_test = Path(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLiveRecoveryCoordinatorTest.kt"
)
text = recovery_test.read_text()
anchor = '''    @Test
    fun coveredCursorIsNeverSkippedEvenWhenStalled() {
'''
insert = '''    @Test
    fun laggingPeerBehindCursorDoesNotBlockFutureGapRecovery() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 90, max = 99, unchoked = true))
        coordinator.updatePeer(peer(id = 2, min = 105, max = 130, unchoked = true))
        coordinator.assign(100, 130, nowMillis = 0)

        val plan = coordinator.evaluate(100, nowMillis = 4_000)

        assertEquals(AceLiveCursorAdvance(fromPiece = 100, toPiece = 105), plan.cursorAdvance)
        assertFalse(plan.gapBeyondAdvanceLimit)
    }

    @Test
    fun nearestFutureWindowWinsWhenSeveralPeersAreAhead() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 90, max = 99, unchoked = true))
        coordinator.updatePeer(peer(id = 2, min = 112, max = 140, unchoked = true))
        coordinator.updatePeer(peer(id = 3, min = 105, max = 130, unchoked = true))

        val plan = coordinator.evaluate(100, nowMillis = 4_000)

        assertEquals(AceLiveCursorAdvance(fromPiece = 100, toPiece = 105), plan.cursorAdvance)
    }

    @Test
    fun chokedFutureWindowDoesNotAuthorizeCursorSkip() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 90, max = 99, unchoked = true))
        coordinator.updatePeer(peer(id = 2, min = 105, max = 130, unchoked = false))

        val plan = coordinator.evaluate(100, nowMillis = 4_000)

        assertNull(plan.cursorAdvance)
        assertFalse(plan.gapBeyondAdvanceLimit)
    }

    @Test
    fun onlyLaggingWindowsDoNotCreateForwardRecoveryAdvance() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 80, max = 90, unchoked = true))
        coordinator.updatePeer(peer(id = 2, min = 91, max = 99, unchoked = true))

        val plan = coordinator.evaluate(100, nowMillis = 4_000)

        assertNull(plan.cursorAdvance)
        assertFalse(plan.gapBeyondAdvanceLimit)
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("AceLiveRecoveryCoordinatorTest.kt: insertion anchor not found exactly once")
recovery_test.write_text(text.replace(anchor, insert + anchor, 1))

# Add session-level regression: stale lagging peer must not block explicit discontinuity to useful peer.
session_test = Path(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLivePeerSessionCoordinatorTest.kt"
)
text = session_test.read_text()
anchor = '''    @Test
    fun memoryBudgetCapsSchedulingHorizonToWholePieceCapacity() {
'''
insert = '''    @Test
    fun recoveryAdvanceIgnoresLaggingPeerAndTargetsNearestFutureWindow() {
        val session = recoverySession(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 90, max = 99, unchoked = true))
        session.onPeerWindow(peerWindow(id = 2, min = 105, max = 130, unchoked = true))

        assertTrue(session.schedule(head = 130, nowMillis = 0).isEmpty())
        val advance = requireNotNull(session.evaluateRecovery(nowMillis = 4_000).cursorAdvance)
        assertEquals(AceLiveCursorAdvance(fromPiece = 100, toPiece = 105), advance)

        val applied = session.applyRecoveryAdvance(advance, nowMillis = 4_000)

        assertEquals(105L, applied.nextNeededPiece)
        assertEquals(5L, requireNotNull(applied.outputDiscontinuity).skippedPieces)
        val outbound = session.schedule(head = 105, nowMillis = 4_000)
        assertTrue(outbound.isNotEmpty())
        assertTrue(outbound.all { it.request.peerId == 2L && it.request.piece == 105L })
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("AceLivePeerSessionCoordinatorTest.kt: insertion anchor not found exactly once")
session_test.write_text(text.replace(anchor, insert + anchor, 1))

# Document the completed V3h and current V3i recovery-hardening increment.
replace_once(
    "docs/ROADMAP.md",
    '''- PR #118 — bounded replacement деградировавших peers только при свежем sustained `CRITICAL`, с producing/baseline/cooldown guards; Android CI #517, real smoke и signed ARM TV APK прошли успешно.
''',
    '''- PR #118 — bounded replacement деградировавших peers только при свежем sustained `CRITICAL`, с producing/baseline/cooldown guards; Android CI #517, real smoke и signed ARM TV APK прошли успешно.
- PR #119 — stable-ready отменяет startup-only DHT probe/full-expansion, но сохраняет normal lightweight refill; Android CI #519 и real Torrent TV smoke без внешнего Ace Engine прошли успешно.
''',
)
replace_once(
    "docs/ROADMAP.md",
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3g (PR #112–#118) дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, bounded request depth, pressure-aware additive refill и консервативный replacement деградировавших peers. Текущий **V3h** закрывает startup discovery lifecycle: startup-specific DHT probe/full expansion прекращается после stable-ready, при этом normal lightweight refill остаётся активным. Recovery timing, startup/no-peer/stall bounds и wire protocol не меняются.
''',
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3h (PR #112–#119) дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, bounded request depth, pressure-aware additive refill, консервативный replacement деградировавших peers и корректное завершение startup-only discovery после stable-ready. Текущий **V3i** закрывает bounded recovery regression matrix: полностью lagging peer windows больше не должны маскировать ближайшее полезное future-window при evicted-gap recovery. Recovery timeout/cap, startup/no-peer/stall bounds и wire protocol не меняются.
''',
)

replace_once(
    "docs/ACE_LIVE_ADAPTIVE_STREAMING_CORE.md",
    '''Текущий V3h завершает startup discovery lifecycle. Startup-specific bounded DHT probe/full-expansion должен быть отменён сразу после stable `startup_buffer_ready`, даже если DHT walk уже выполняется; при этом cancellation не должна прекращать обычный long-running lightweight refill. Recovery timing, startup/no-peer/stall bounds, request-depth/refill/replacement policies и wire protocol этим PR не меняются.
''',
    '''PR #119 завершил V3h startup discovery lifecycle и уже находится в `main`: startup-specific bounded DHT probe/full-expansion отменяется после stable `startup_buffer_ready`, даже если startup-only work уже выполняется; cancellation не прекращает обычный long-running lightweight refill. Android CI #519 и real Torrent TV playback smoke без внешнего Ace Engine прошли успешно.

Текущий V3i фиксирует bounded recovery selection semantics. При evicted-gap recovery peer window, полностью находящееся позади authoritative cursor, не должно маскировать ближайшее requestable future-window. Recovery по-прежнему разрешён только после существующего request-timeout, учитывает только unchoked windows и остаётся ограничен `maxPieceAdvance`; timeout/cursor ownership/wire protocol этим инкрементом не расширяются.
''',
)

# The workflow/script are staging-only and must not remain in the final PR diff.
Path(".github/workflows/apply-ace-live-bounded-recovery-matrix-v3i.yml").unlink(missing_ok=True)
Path(".github/scripts/apply_ace_live_bounded_recovery_matrix_v3i.py").unlink(missing_ok=True)
