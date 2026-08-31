from pathlib import Path


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    file = Path(path)
    text = file.read_text()
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: expected {expected} matches, found {count}")
    file.write_text(text.replace(old, new))

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerShell.kt",
    """    onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit,
    onError: (Long?, String) -> Unit,
    onToggleFullscreen: () -> Unit,""",
    """    onP2pBoundaryTelemetry: (P2pPlayerBoundaryTelemetry) -> Unit,
    onError: (Long?, String) -> Unit,
    onPlayCatchUp: (EpgProgram) -> Unit,
    onToggleFullscreen: () -> Unit,""",
    expected=1,
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerShell.kt",
    """        StableProgrammeDialog(
            channel = channel,
            programs = programs,
            onDismiss = { programmeVisible = false }
        )""",
    """        StableProgrammeDialog(
            channel = channel,
            programs = programs,
            onPlayCatchUp = { program ->
                programmeVisible = false
                onPlayCatchUp(program)
            },
            onDismiss = { programmeVisible = false }
        )""",
    expected=1,
)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerScreenReplacement.kt",
    """                onP2pBoundaryTelemetry = viewModel::onP2pPlayerBoundaryTelemetry,
                onError = { sessionId, message ->
                    viewModel.onInternalPlaybackError(message, context, sessionId)
                },
                onToggleFullscreen = { viewModel.setInternalPlayerExpanded(false) },""",
    """                onP2pBoundaryTelemetry = viewModel::onP2pPlayerBoundaryTelemetry,
                onError = { sessionId, message ->
                    viewModel.onInternalPlaybackError(message, context, sessionId)
                },
                onPlayCatchUp = { program -> viewModel.playCatchUpProgram(program, context) },
                onToggleFullscreen = { viewModel.setInternalPlayerExpanded(false) },""",
    expected=1,
)
