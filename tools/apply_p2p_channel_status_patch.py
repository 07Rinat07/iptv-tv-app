from pathlib import Path

PATH = Path("feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerScreenReplacement.kt")
text = PATH.read_text(encoding="utf-8")
original = text


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    text = text.replace(old, new, 1)


replace_once(
    '    var favoritesOnly by rememberSaveable { mutableStateOf(false) }\n'
    '    var hideUnavailable by rememberSaveable { mutableStateOf(true) }\n',
    '    var favoritesOnly by rememberSaveable { mutableStateOf(false) }\n',
    "remove hideUnavailable state",
)

replace_once(
    '        favoritesOnly,\n'
    '        hideUnavailable,\n'
    '        state.parentalControlEnabled,\n',
    '        favoritesOnly,\n'
    '        state.parentalControlEnabled,\n',
    "remove hideUnavailable remember key",
)

replace_once(
    '            .filter { !hideUnavailable || it.health != ChannelHealth.UNAVAILABLE }\n',
    '',
    "remove unavailable filter",
)

replace_once(
    '''    LaunchedEffect(state.favoriteChannelIds) {\n        optimisticFavoriteIds = state.favoriteChannelIds\n    }\n\n    val filteredChannels = remember(\n''',
    '''    LaunchedEffect(state.favoriteChannelIds) {\n        optimisticFavoriteIds = state.favoriteChannelIds\n    }\n\n    // Torrent TV availability is informational only. Never probe or hide the full catalog.\n    // We remember the last result only for channels the user actually tried.\n    LaunchedEffect(\n        state.selectedChannelId,\n        state.isStartingPlayback,\n        state.internalSession?.sessionId,\n        state.enginePeers,\n        state.engineSpeedKbps,\n        state.resolvedStreamUrl,\n        state.lastError\n    ) {\n        val channel = state.channels.firstOrNull { it.id == state.selectedChannelId }\n            ?: return@LaunchedEffect\n        if (PlayerP2pDescriptor.detect(channel.streamUrl) == null) return@LaunchedEffect\n\n        val activeSession = state.internalSession?.takeIf { it.channelId == channel.id }\n        val previous = P2pChannelAvailabilityUiCache.statuses[channel.id]\n        val p2pFailure = state.lastError?.takeIf { message ->\n            message.contains("Torrent TV", ignoreCase = true) ||\n                message.contains("P2P", ignoreCase = true) ||\n                message.contains("подготовить поток", ignoreCase = true)\n        }\n        val availability = when {\n            activeSession != null && !state.isStartingPlayback -> P2pChannelAvailabilityState.PLAYING\n            activeSession != null -> P2pChannelAvailabilityState.READY\n            state.isStartingPlayback -> P2pChannelAvailabilityState.SEARCHING\n            p2pFailure != null -> p2pAvailabilityFromResolveError(p2pFailure)\n            state.resolvedStreamUrl != null -> P2pChannelAvailabilityState.READY\n            else -> previous?.state ?: P2pChannelAvailabilityState.UNCHECKED\n        }\n        val peers = when (availability) {\n            P2pChannelAvailabilityState.SEARCHING,\n            P2pChannelAvailabilityState.NO_PEERS,\n            P2pChannelAvailabilityState.ERROR -> 0\n            else -> state.enginePeers.coerceAtLeast(0)\n        }\n        val speed = if (availability == P2pChannelAvailabilityState.PLAYING) {\n            state.engineSpeedKbps.coerceAtLeast(0)\n        } else {\n            0\n        }\n        P2pChannelAvailabilityUiCache.mark(\n            channelId = channel.id,\n            state = availability,\n            peers = peers,\n            speedKbps = speed\n        )\n    }\n\n    val filteredChannels = remember(\n''',
    "insert lazy P2P availability tracking",
)

replace_once(
    '                selectedSubGroup = state.selectedSubGroup,\n'
    '                hideUnavailable = hideUnavailable,\n'
    '                favoritesOnly = favoritesOnly,\n',
    '                selectedSubGroup = state.selectedSubGroup,\n'
    '                favoritesOnly = favoritesOnly,\n',
    "remove dialog hideUnavailable argument",
)

replace_once(
    '                onSelectSubGroup = viewModel::selectSubGroup,\n'
    '                onToggleUnavailable = { hideUnavailable = !hideUnavailable },\n'
    '                onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },\n',
    '                onSelectSubGroup = viewModel::selectSubGroup,\n'
    '                onToggleFavoritesOnly = { favoritesOnly = !favoritesOnly },\n',
    "remove dialog unavailable callback",
)

replace_once(
    '    selectedSubGroup: String?,\n'
    '    hideUnavailable: Boolean,\n'
    '    favoritesOnly: Boolean,\n',
    '    selectedSubGroup: String?,\n'
    '    favoritesOnly: Boolean,\n',
    "remove dialog hideUnavailable parameter",
)

replace_once(
    '    onSelectGroup: (String?) -> Unit,\n'
    '    onSelectSubGroup: (String?) -> Unit,\n'
    '    onToggleUnavailable: () -> Unit,\n'
    '    onToggleFavoritesOnly: () -> Unit,\n',
    '    onSelectGroup: (String?) -> Unit,\n'
    '    onSelectSubGroup: (String?) -> Unit,\n'
    '    onToggleFavoritesOnly: () -> Unit,\n',
    "remove dialog unavailable parameter callback",
)

replace_once(
    '''                    OutlinedButton(onClick = onToggleUnavailable, modifier = Modifier.fillMaxWidth()) {\n                        Text(if (hideUnavailable) "Показывать недоступные" else "Скрывать недоступные")\n                    }\n''',
    '',
    "remove unavailable button",
)

replace_once(
    '''                val selected = channel.id == selectedChannelId\n                Surface(\n''',
    '''                val selected = channel.id == selectedChannelId\n                val p2pStatus = if (PlayerP2pDescriptor.detect(channel.streamUrl) != null) {\n                    p2pChannelAvailabilityLabel(P2pChannelAvailabilityUiCache.statuses[channel.id])\n                } else {\n                    null\n                }\n                Surface(\n''',
    "add P2P status to main list",
)

replace_once(
    '''                            Text(\n                                current?.let {\n                                    "${stableTime(it.startEpochMs)}–${stableTime(it.endEpochMs)} ${it.title}"\n                                } ?: "Программа не найдена",\n                                style = MaterialTheme.typography.bodySmall,\n                                maxLines = 2,\n                                overflow = TextOverflow.Ellipsis\n                            )\n''',
    '''                            Text(\n                                p2pStatus ?: current?.let {\n                                    "${stableTime(it.startEpochMs)}–${stableTime(it.endEpochMs)} ${it.title}"\n                                } ?: "Программа не найдена",\n                                style = MaterialTheme.typography.bodySmall,\n                                maxLines = 2,\n                                overflow = TextOverflow.Ellipsis\n                            )\n''',
    "render P2P status in main list",
)

replace_once(
    '''                val current = stableCurrentProgram(\n                    epgByChannel[channel.id].orEmpty(),\n                    System.currentTimeMillis()\n                )\n                Card(\n                    modifier = Modifier\n                        .width(184.dp)\n''',
    '''                val current = stableCurrentProgram(\n                    epgByChannel[channel.id].orEmpty(),\n                    System.currentTimeMillis()\n                )\n                val p2pStatus = if (PlayerP2pDescriptor.detect(channel.streamUrl) != null) {\n                    p2pChannelAvailabilityLabel(P2pChannelAvailabilityUiCache.statuses[channel.id])\n                } else {\n                    null\n                }\n                Card(\n                    modifier = Modifier\n                        .width(184.dp)\n''',
    "add P2P status to nearby list",
)

replace_once(
    '''                        Text(\n                            current?.let { "${stableTime(it.startEpochMs)} ${it.title}" }\n                                ?: "EPG нет",\n                            style = MaterialTheme.typography.bodySmall,\n                            maxLines = 2,\n                            overflow = TextOverflow.Ellipsis\n                        )\n''',
    '''                        Text(\n                            p2pStatus ?: current?.let { "${stableTime(it.startEpochMs)} ${it.title}" }\n                                ?: "EPG нет",\n                            style = MaterialTheme.typography.bodySmall,\n                            maxLines = 2,\n                            overflow = TextOverflow.Ellipsis\n                        )\n''',
    "render P2P status in nearby list",
)

if "hideUnavailable" in text or "onToggleUnavailable" in text:
    raise SystemExit("unavailable-filter UI still remains after patch")
if text == original:
    raise SystemExit("patch produced no changes")

PATH.write_text(text, encoding="utf-8")
print(f"patched {PATH}")
