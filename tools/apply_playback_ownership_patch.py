from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


vm_path = Path("feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt")
vm = vm_path.read_text()

vm = replace_once(
    vm,
    """    val recoveryPolicy: BufferingRecoveryPolicy,\n    val bufferPlanSummary: String\n)\n""",
    """    val recoveryPolicy: BufferingRecoveryPolicy,\n    val bufferPlanSummary: String,\n    val requestId: Long = 0L\n)\n""",
    "session request id",
)

vm = replace_once(
    vm,
    """    private var primaryPlaybackJob: Job? = null\n    private var primaryPlaybackRequestId: Long = 0L\n""",
    """    private var primaryPlaybackJob: Job? = null\n    private var primaryRetryJob: Job? = null\n    private val primaryPlaybackOwnership = PrimaryPlaybackOwnership()\n""",
    "ownership fields",
)

vm = replace_once(
    vm,
    """    private fun beginPrimaryPlaybackRequest(): Long {\n        primaryPlaybackJob?.cancel()\n        primaryPlaybackRequestId += 1L\n        return primaryPlaybackRequestId\n    }\n\n    private fun invalidatePrimaryPlaybackRequest() {\n        primaryPlaybackJob?.cancel()\n        primaryPlaybackRequestId += 1L\n    }\n\n    private fun isCurrentPrimaryPlaybackRequest(requestId: Long): Boolean {\n        return requestId == primaryPlaybackRequestId\n    }\n""",
    """    private fun beginPrimaryPlaybackRequest(): Long {\n        primaryPlaybackJob?.cancel()\n        primaryRetryJob?.cancel()\n        return primaryPlaybackOwnership.beginRequest()\n    }\n\n    private fun invalidatePrimaryPlaybackRequest() {\n        primaryPlaybackJob?.cancel()\n        primaryRetryJob?.cancel()\n        primaryPlaybackOwnership.invalidateRequest()\n    }\n\n    private fun isCurrentPrimaryPlaybackRequest(requestId: Long): Boolean {\n        return primaryPlaybackOwnership.isCurrentRequest(requestId)\n    }\n""",
    "request lifecycle",
)

vm = replace_once(
    vm,
    """        val requestId = beginPrimaryPlaybackRequest()\n        primaryPlaybackJob = viewModelScope.launch {\n            _uiState.update { it.copy(isStartingPlayback = true, lastError = null) }\n            safeLog(\n""",
    """        val requestId = beginPrimaryPlaybackRequest()\n        // Remove the previous decoder surface synchronously. A slow P2P resolve must never leave\n        // the old channel visible under the newly selected channel name or able to emit callbacks.\n        _uiState.update {\n            it.copy(\n                internalSession = null,\n                isStartingPlayback = true,\n                retryAttempt = 0,\n                resolvedStreamUrl = null,\n                lastError = null,\n                lastInfo = null\n            )\n        }\n        primaryPlaybackJob = viewModelScope.launch {\n            safeLog(\n""",
    "synchronous surface detach",
)

vm = replace_once(
    vm,
    """        val state = _uiState.value\n        val currentSession = state.internalSession\n        if (sessionId != null && currentSession?.sessionId != sessionId) {\n""",
    """        val state = _uiState.value\n        val currentSession = state.internalSession\n        if (\n            currentSession != null &&\n            currentSession.requestId != 0L &&\n            !isCurrentPrimaryPlaybackRequest(currentSession.requestId)\n        ) {\n            logAsync(\n                status = \"player_ready_ignored\",\n                message = \"Ignored ready from invalidated request: requestId=${currentSession.requestId}, sessionId=${currentSession.sessionId}\",\n                playlistId = state.selectedPlaylistId\n            )\n            return\n        }\n        if (sessionId != null && currentSession?.sessionId != sessionId) {\n""",
    "ready ownership guard",
)

vm = replace_once(
    vm,
    """        if (sessionId != null && session.sessionId != sessionId) {\n            logAsync(\n                status = \"player_error_ignored\",\n                message = \"Ignored stale error: sessionId=$sessionId, current=${session.sessionId}\",\n                playlistId = state.selectedPlaylistId\n            )\n            return\n        }\n        val sourceChannel = state.channels.firstOrNull { channel -> channel.id == session.channelId }\n""",
    """        if (sessionId != null && session.sessionId != sessionId) {\n            logAsync(\n                status = \"player_error_ignored\",\n                message = \"Ignored stale error: sessionId=$sessionId, current=${session.sessionId}\",\n                playlistId = state.selectedPlaylistId\n            )\n            return\n        }\n        if (session.requestId == 0L || !isCurrentPrimaryPlaybackRequest(session.requestId)) {\n            logAsync(\n                status = \"player_error_ignored\",\n                message = \"Ignored error from invalidated playback request: requestId=${session.requestId}, sessionId=${session.sessionId}\",\n                playlistId = state.selectedPlaylistId\n            )\n            return\n        }\n        val sourceChannel = state.channels.firstOrNull { channel -> channel.id == session.channelId }\n""",
    "error ownership guard",
)

vm = replace_once(
    vm,
    """        if (!errorKind.retryable || state.retryAttempt >= MAX_AUTO_RETRIES) {\n            viewModelScope.launch {\n""",
    """        if (!errorKind.retryable || state.retryAttempt >= MAX_AUTO_RETRIES) {\n            primaryRetryJob?.cancel()\n            viewModelScope.launch {\n""",
    "terminal retry cancellation",
)

old_retry = """        val nextAttempt = state.retryAttempt + 1\n        viewModelScope.launch {\n            diagnosticsRepository.addLog(\n                status = \"player_rebuffer\",\n                message = \"Retry $nextAttempt/$MAX_AUTO_RETRIES due to: kind=${errorKind.code}, msg=$message\",\n                playlistId = state.selectedPlaylistId\n            )\n            _uiState.update {\n                it.copy(\n                    retryAttempt = nextAttempt,\n                    isStartingPlayback = true,\n                    lastError = \"Ошибка потока (${errorKind.code}): ${errorKind.hint}. Повтор $nextAttempt/$MAX_AUTO_RETRIES\"\n                )\n            }\n            val delayMs = RETRY_DELAYS_MS.getOrElse(nextAttempt - 1) { 2_500L }\n            delay(delayMs)\n            val latestSession = _uiState.value.internalSession\n            if (latestSession == null || latestSession.sessionId != session.sessionId) {\n                return@launch\n            }\n            if (sourceChannel != null && isP2pPlayback) {\n                when (val resolved = resolvePlayableChannel(sourceChannel)) {\n                    is AppResult.Success -> {\n                        val current = _uiState.value.internalSession\n                        if (current == null || current.sessionId != session.sessionId) return@launch\n                        startInternalPlayback(\n                            channel = sourceChannel.copy(streamUrl = resolved.data),\n                            infoMessage = \"P2P-сессия переподключена\",\n                            retryAttempt = nextAttempt\n                        )\n                    }\n\n                    is AppResult.Error -> {\n                        val current = _uiState.value.internalSession\n                        if (current == null || current.sessionId != session.sessionId) return@launch\n                        diagnosticsRepository.addLog(\n                            status = \"player_p2p_restart_error\",\n                            message = \"P2P restart failed: ${resolved.message.take(MAX_LOG_MESSAGE)}\",\n                            playlistId = sourceChannel.playlistId\n                        )\n                        _uiState.update {\n                            it.copy(\n                                internalSession = null,\n                                isStartingPlayback = false,\n                                retryAttempt = 0,\n                                lastError = \"P2P-источник не публикует новые данные или сейчас не имеет доступных пиров\",\n                                lastInfo = null\n                            )\n                        }\n                    }\n\n                    AppResult.Loading -> Unit\n                }\n                return@launch\n            }\n            _uiState.update {\n                it.copy(\n                    internalSession = latestSession.copy(sessionId = latestSession.sessionId + 1),\n                    lastInfo = \"Повторное подключение...\",\n                    lastError = null\n                )\n            }\n        }\n"""

new_retry = """        val requestId = session.requestId\n        val nextAttempt = state.retryAttempt + 1\n        primaryRetryJob?.cancel()\n        primaryRetryJob = viewModelScope.launch {\n            diagnosticsRepository.addLog(\n                status = \"player_rebuffer\",\n                message = \"Retry $nextAttempt/$MAX_AUTO_RETRIES due to: kind=${errorKind.code}, msg=$message, requestId=$requestId, sessionId=${session.sessionId}\",\n                playlistId = state.selectedPlaylistId\n            )\n            if (\n                !primaryPlaybackOwnership.ownsSession(\n                    expectedRequestId = requestId,\n                    expectedSessionId = session.sessionId,\n                    currentRequestId = _uiState.value.internalSession?.requestId,\n                    currentSessionId = _uiState.value.internalSession?.sessionId\n                )\n            ) {\n                return@launch\n            }\n            _uiState.update {\n                it.copy(\n                    retryAttempt = nextAttempt,\n                    isStartingPlayback = true,\n                    lastError = \"Ошибка потока (${errorKind.code}): ${errorKind.hint}. Повтор $nextAttempt/$MAX_AUTO_RETRIES\"\n                )\n            }\n            val delayMs = RETRY_DELAYS_MS.getOrElse(nextAttempt - 1) { 2_500L }\n            delay(delayMs)\n\n            val latestSession = _uiState.value.internalSession\n            if (\n                !primaryPlaybackOwnership.ownsSession(\n                    expectedRequestId = requestId,\n                    expectedSessionId = session.sessionId,\n                    currentRequestId = latestSession?.requestId,\n                    currentSessionId = latestSession?.sessionId\n                )\n            ) {\n                safeLog(\n                    status = \"player_retry_superseded\",\n                    message = \"Retry suppressed after channel change: requestId=$requestId, sessionId=${session.sessionId}\",\n                    playlistId = state.selectedPlaylistId\n                )\n                return@launch\n            }\n\n            if (sourceChannel != null && isP2pPlayback) {\n                when (val resolved = resolvePlayableChannel(sourceChannel)) {\n                    is AppResult.Success -> {\n                        val current = _uiState.value.internalSession\n                        if (\n                            !primaryPlaybackOwnership.ownsSession(\n                                expectedRequestId = requestId,\n                                expectedSessionId = session.sessionId,\n                                currentRequestId = current?.requestId,\n                                currentSessionId = current?.sessionId\n                            )\n                        ) {\n                            safeLog(\n                                status = \"player_retry_superseded\",\n                                message = \"Resolved stale P2P retry discarded: requestId=$requestId, sessionId=${session.sessionId}\",\n                                playlistId = sourceChannel.playlistId\n                            )\n                            return@launch\n                        }\n                        startInternalPlayback(\n                            channel = sourceChannel.copy(streamUrl = resolved.data),\n                            infoMessage = \"P2P-сессия переподключена\",\n                            requestId = requestId,\n                            retryAttempt = nextAttempt\n                        )\n                    }\n\n                    is AppResult.Error -> {\n                        val current = _uiState.value.internalSession\n                        if (\n                            !primaryPlaybackOwnership.ownsSession(\n                                expectedRequestId = requestId,\n                                expectedSessionId = session.sessionId,\n                                currentRequestId = current?.requestId,\n                                currentSessionId = current?.sessionId\n                            )\n                        ) return@launch\n                        diagnosticsRepository.addLog(\n                            status = \"player_p2p_restart_error\",\n                            message = \"P2P restart failed: ${resolved.message.take(MAX_LOG_MESSAGE)}\",\n                            playlistId = sourceChannel.playlistId\n                        )\n                        _uiState.update { currentState ->\n                            val active = currentState.internalSession\n                            if (\n                                primaryPlaybackOwnership.ownsSession(\n                                    expectedRequestId = requestId,\n                                    expectedSessionId = session.sessionId,\n                                    currentRequestId = active?.requestId,\n                                    currentSessionId = active?.sessionId\n                                )\n                            ) {\n                                currentState.copy(\n                                    internalSession = null,\n                                    isStartingPlayback = false,\n                                    retryAttempt = 0,\n                                    lastError = \"P2P-источник не публикует новые данные или сейчас не имеет доступных пиров\",\n                                    lastInfo = null\n                                )\n                            } else {\n                                currentState\n                            }\n                        }\n                    }\n\n                    AppResult.Loading -> Unit\n                }\n                return@launch\n            }\n\n            val retrySessionId = primaryPlaybackOwnership.nextSessionId()\n            _uiState.update { currentState ->\n                val active = currentState.internalSession\n                if (\n                    primaryPlaybackOwnership.ownsSession(\n                        expectedRequestId = requestId,\n                        expectedSessionId = session.sessionId,\n                        currentRequestId = active?.requestId,\n                        currentSessionId = active?.sessionId\n                    )\n                ) {\n                    currentState.copy(\n                        internalSession = active?.copy(sessionId = retrySessionId),\n                        lastInfo = \"Повторное подключение...\",\n                        lastError = null\n                    )\n                } else {\n                    currentState\n                }\n            }\n        }\n"""
vm = replace_once(vm, old_retry, new_retry, "retry ownership")

vm = replace_once(
    vm,
    """    private fun startInternalPlayback(\n        channel: Channel,\n        infoMessage: String,\n        requestId: Long? = null,\n        retryAttempt: Int = 0\n    ) {\n        if (requestId != null && !isCurrentPrimaryPlaybackRequest(requestId)) {\n""",
    """    private fun startInternalPlayback(\n        channel: Channel,\n        infoMessage: String,\n        requestId: Long,\n        retryAttempt: Int = 0\n    ) {\n        if (!isCurrentPrimaryPlaybackRequest(requestId)) {\n""",
    "required request id",
)

vm = replace_once(
    vm,
    """        val preparedStream = parseKodiStyleStream(channel.streamUrl)\n        val nextSessionId = (state.internalSession?.sessionId ?: 0L) + 1L\n""",
    """        val preparedStream = parseKodiStyleStream(channel.streamUrl)\n        val nextSessionId = primaryPlaybackOwnership.nextSessionId()\n""",
    "unique primary session id",
)

vm = replace_once(
    vm,
    """                    bufferConfig = plan.config,\n                    recoveryPolicy = plan.recoveryPolicy,\n                    bufferPlanSummary = plan.summary\n                ),\n""",
    """                    bufferConfig = plan.config,\n                    recoveryPolicy = plan.recoveryPolicy,\n                    bufferPlanSummary = plan.summary,\n                    requestId = requestId\n                ),\n""",
    "primary session ownership field",
)

vm = replace_once(
    vm,
    """        val nextSessionId = (sessionForPane(state, paneIndex)?.sessionId ?: 0L) + 1L\n""",
    """        val nextSessionId = primaryPlaybackOwnership.nextSessionId()\n""",
    "global multiview session id",
)

vm_path.write_text(vm)

# Integration regression: schedule retry A, zap to B then C before its delay, and prove A cannot
# re-enter EngineRepository later.
test_path = Path("feature/player/src/test/java/com/iptv/tv/feature/player/PlayerViewModelMultiviewTest.kt")
test = test_path.read_text()
test = replace_once(
    test,
    """import kotlinx.coroutines.test.StandardTestDispatcher\nimport kotlinx.coroutines.test.TestDispatcher\nimport kotlinx.coroutines.test.advanceUntilIdle\n""",
    """import kotlinx.coroutines.test.StandardTestDispatcher\nimport kotlinx.coroutines.test.TestDispatcher\nimport kotlinx.coroutines.test.advanceTimeBy\nimport kotlinx.coroutines.test.advanceUntilIdle\nimport kotlinx.coroutines.test.runCurrent\n""",
    "test coroutine imports",
)
test = replace_once(
    test,
    """import org.junit.Assert.assertNotNull\nimport org.junit.Assert.assertNull\n""",
    """import org.junit.Assert.assertNotEquals\nimport org.junit.Assert.assertNotNull\nimport org.junit.Assert.assertNull\n""",
    "assertNotEquals import",
)

integration_test = '''\n    @Test\n    fun lateP2pRetryFromA_cannotReplaceC_afterRapidABCZap() = runTest(dispatcher) {\n        val contentId = "50bc2f512793f1e745fb5bd5b5a6afca199c2d19"\n        val engineRepository = FakeEngineRepository()\n        val viewModel = createViewModel(\n            channels = listOf(\n                testChannel(\n                    id = 10L,\n                    name = "Torrent A",\n                    streamUrl = "http://127.0.0.1:6878/ace/getstream?id=$contentId"\n                ),\n                testChannel(id = 11L, name = "HTTP B", streamUrl = "https://example.com/b.m3u8"),\n                testChannel(id = 12L, name = "HTTP C", streamUrl = "https://example.com/c.m3u8")\n            ),\n            engineRepository = engineRepository\n        )\n        advanceUntilIdle()\n\n        viewModel.playChannelInternal(10L)\n        advanceUntilIdle()\n        val sessionA = requireNotNull(viewModel.uiState.value.internalSession)\n        assertEquals(1, engineRepository.resolveCount)\n\n        // Let the retry coroutine publish its delayed retry state, but do not advance through the\n        // 800 ms backoff yet. B and C must invalidate and cancel this pending work.\n        viewModel.onInternalPlaybackError(\n            message = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: Source error",\n            sessionId = sessionA.sessionId\n        )\n        runCurrent()\n\n        viewModel.playChannelInternal(11L)\n        runCurrent()\n        viewModel.playChannelInternal(12L)\n        runCurrent()\n\n        advanceTimeBy(5_000L)\n        advanceUntilIdle()\n\n        val current = requireNotNull(viewModel.uiState.value.internalSession)\n        assertEquals(12L, current.channelId)\n        assertNotEquals(sessionA.sessionId, current.sessionId)\n        assertNotEquals(sessionA.requestId, current.requestId)\n        assertEquals(1, engineRepository.resolveCount)\n        assertEquals(0, viewModel.uiState.value.retryAttempt)\n    }\n\n'''
test = replace_once(
    test,
    """    private fun createViewModel(\n""",
    integration_test + """    private fun createViewModel(\n""",
    "A B C late retry integration test",
)
test_path.write_text(test)

workflow_path = Path(".github/workflows/android.yml")
workflow = workflow_path.read_text()
workflow = replace_once(
    workflow,
    """            '^(app/src/androidTest/java/com/iptv/tv/(TorrentTvPlaybackSmokeTest|HiltTestRunner)\\.kt|app/src/main/java/com/iptv/tv/ApplicationEngineEntryPoint\\.kt|core/(p2p|engine)/|core/data/src/main/java/com/iptv/tv/core/data/repository/(HybridEngineRepositoryImpl|CoalescingEngineRepository)\\.kt)' \\\n""",
    """            '^(app/src/androidTest/java/com/iptv/tv/(TorrentTvPlaybackSmokeTest|HiltTestRunner)\\.kt|app/src/main/java/com/iptv/tv/ApplicationEngineEntryPoint\\.kt|core/(p2p|engine)/|core/data/src/main/java/com/iptv/tv/core/data/repository/(HybridEngineRepositoryImpl|CoalescingEngineRepository)\\.kt|feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel\\.kt)' \\\n""",
    "smoke detector PlayerViewModel path",
)
workflow_path.write_text(workflow)

print("Playback ownership patch applied successfully")
