from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} matches, found {count}")
    file.write_text(text.replace(old, new))


replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StableProgrammeDialog.kt",
    """internal fun StableProgrammeDialog(
    channel: Channel?,
    programs: List<EpgProgram>,
    onDismiss: () -> Unit
) {""",
    """internal fun StableProgrammeDialog(
    channel: Channel?,
    programs: List<EpgProgram>,
    onPlayCatchUp: (EpgProgram) -> Unit,
    onDismiss: () -> Unit
) {"""
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StableProgrammeDialog.kt",
    """                                            program.description
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { description ->
                                                    Text(
                                                        description,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
""",
    """                                            program.description
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { description ->
                                                    Text(
                                                        description,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            if (
                                                StableCatchUpActionPolicy.isAvailable(
                                                    channel = channel,
                                                    program = program,
                                                    nowMs = nowMs
                                                )
                                            ) {
                                                OutlinedButton(
                                                    onClick = { onPlayCatchUp(program) },
                                                    modifier = Modifier.padding(top = 4.dp)
                                                ) {
                                                    Text("Архив")
                                                }
                                            }
"""
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerShell.kt",
    """    onError: (Long?, String) -> Unit,
    onPlaySelected: () -> Unit,
    onToggleFullscreen: () -> Unit,""",
    """    onError: (Long?, String) -> Unit,
    onPlaySelected: () -> Unit,
    onPlayCatchUp: (EpgProgram) -> Unit,
    onToggleFullscreen: () -> Unit,"""
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerShell.kt",
    """        StableProgrammeDialog(
            channel = selectedChannel,
            programs = programs,
            onDismiss = { programmeVisible = false }
        )""",
    """        StableProgrammeDialog(
            channel = selectedChannel,
            programs = programs,
            onPlayCatchUp = { program ->
                programmeVisible = false
                onPlayCatchUp(program)
            },
            onDismiss = { programmeVisible = false }
        )"""
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StableResponsiveCompactPlayer.kt",
    """    onError: (Long?, String) -> Unit,
    onPlaySelected: () -> Unit,
    onToggleFullscreen: () -> Unit,""",
    """    onError: (Long?, String) -> Unit,
    onPlaySelected: () -> Unit,
    onPlayCatchUp: (EpgProgram) -> Unit,
    onToggleFullscreen: () -> Unit,"""
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StableResponsiveCompactPlayer.kt",
    """                    onError = onError,
                    onPlaySelected = onPlaySelected,
                    onToggleFullscreen = onToggleFullscreen,""",
    """                    onError = onError,
                    onPlaySelected = onPlaySelected,
                    onPlayCatchUp = onPlayCatchUp,
                    onToggleFullscreen = onToggleFullscreen,""",
    expected=2
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerScreenReplacement.kt",
    """                            onPlaySelected = { viewModel.playSelected(context) },
                            onToggleFullscreen = viewModel::toggleInternalPlayerSize,""",
    """                            onPlaySelected = { viewModel.playSelected(context) },
                            onPlayCatchUp = { program -> viewModel.playCatchUpProgram(program, context) },
                            onToggleFullscreen = viewModel::toggleInternalPlayerSize,""",
    expected=2
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt",
    """    fun playSelected(context: Context) {
        playSelectedWith(playerType = _uiState.value.effectivePlayer, context = context)
    }

    fun playSelectedInternal() {""",
    """    fun playSelected(context: Context) {
        playSelectedWith(playerType = _uiState.value.effectivePlayer, context = context)
    }

    fun playCatchUpProgram(program: EpgProgram, context: Context) {
        val state = _uiState.value
        val channel = state.channels.firstOrNull { candidate -> candidate.id == state.selectedChannelId }
        if (channel == null) {
            _uiState.update {
                it.copy(
                    lastError = "Выберите канал для просмотра архива",
                    lastInfo = null
                )
            }
            return
        }

        val resolution = StableCatchUpActionPolicy.resolve(
            channel = channel,
            program = program,
            nowMs = System.currentTimeMillis()
        )
        val playbackUrl = resolution
            ?.takeIf { it.supported }
            ?.playbackUrl
            ?.takeIf { it.isNotBlank() }
        if (playbackUrl == null) {
            _uiState.update {
                it.copy(
                    lastError = "Архив недоступен для выбранной передачи",
                    lastInfo = null
                )
            }
            logAsync(
                status = "player_catchup_unsupported",
                message = "channelId=${channel.id}, start=${program.startEpochMs}, end=${program.endEpochMs}, reason=${resolution?.reason ?: "no_resolution"}",
                playlistId = channel.playlistId
            )
            return
        }

        val requestId = beginPrimaryPlaybackRequest()
        val playerType = state.effectivePlayer
        _uiState.update {
            it.copy(
                internalSession = null,
                isStartingPlayback = true,
                retryAttempt = 0,
                resolvedStreamUrl = null,
                lastError = null,
                lastInfo = "Подготовка архива: ${program.title}"
            )
        }
        primaryPlaybackJob = viewModelScope.launch {
            // Archive URLs are direct HTTP/HTTPS by resolver contract. Stop any previous P2P
            // producer before handing the resolved archive URL to the normal playback pipeline.
            engineRepository.stopTorrentStream()
            if (!isCurrentPrimaryPlaybackRequest(requestId)) {
                return@launch
            }

            val archiveChannel = channel.copy(streamUrl = playbackUrl)
            _uiState.update { it.copy(resolvedStreamUrl = playbackUrl) }
            safeLog(
                status = "player_catchup_start",
                message = "channelId=${channel.id}, start=${program.startEpochMs}, end=${program.endEpochMs}, player=$playerType",
                playlistId = channel.playlistId
            )

            when (playerType) {
                PlayerType.INTERNAL -> startInternalPlayback(
                    channel = archiveChannel,
                    infoMessage = "Запущен архив: ${program.title}",
                    requestId = requestId
                )
                PlayerType.VLC -> launchExternalVlcOrFallback(
                    context = context,
                    channel = archiveChannel,
                    requestId = requestId
                )
            }
        }
    }

    fun playSelectedInternal() {"""
)
