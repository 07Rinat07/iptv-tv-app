from pathlib import Path
import re


def replace_exact(path: str, old: str, new: str, expected: int = 1) -> None:
    target = Path(path)
    text = target.read_text()
    actual = text.count(old)
    if actual != expected:
        raise SystemExit(f"{path}: expected {expected} occurrences, found {actual}: {old[:120]!r}")
    target.write_text(text.replace(old, new))


replace_exact(
    "core/model/src/main/kotlin/com/iptv/tv/core/model/Models.kt",
    "    val upcoming: List<EpgProgram>\n)",
    "    val upcoming: List<EpgProgram>,\n    val schedule: List<EpgProgram> = emptyList()\n)",
)

replace_exact(
    "core/data/src/main/java/com/iptv/tv/core/data/repository/Repositories.kt",
    "                    upcoming = upcoming\n                )",
    "                    upcoming = upcoming,\n                    schedule = match.programs\n                )",
)

virtual_path = Path("core/data/src/main/java/com/iptv/tv/core/data/repository/VirtualRecentChannelsPlaylistRepository.kt")
virtual_text = virtual_path.read_text()
pattern = re.compile(
    r"    override suspend fun getChannelEpgNowNext\(channelId: Long\): AppResult<ChannelEpgInfo> \{.*?\n    \}\n\}\n\ninternal fun recentChannelsForVirtualView",
    re.S,
)
replacement = '''    override suspend fun getChannelEpgNowNext(channelId: Long): AppResult<ChannelEpgInfo> {
        val settings = epgSettingsRepository.currentSettings()
        val baseInfo = when (val result = delegate.getChannelEpgNowNext(channelId)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> return result
            AppResult.Loading -> return AppResult.Loading
        }
        if (settings.manualOffsetMinutes == 0) {
            return AppResult.Success(baseInfo)
        }

        val nowMs = System.currentTimeMillis()
        val correctedPrograms = EpgTimeCorrection.apply(
            baseInfo.schedule,
            settings.manualOffsetMinutes
        )
        return AppResult.Success(
            baseInfo.copy(
                now = EpgTimeCorrection.current(correctedPrograms, nowMs),
                next = EpgTimeCorrection.next(correctedPrograms, nowMs),
                upcoming = correctedPrograms
                    .asSequence()
                    .filter { program -> program.endEpochMs > nowMs }
                    .take(12)
                    .toList(),
                schedule = correctedPrograms
            )
        )
    }
}

internal fun recentChannelsForVirtualView'''
virtual_text, count = pattern.subn(replacement, virtual_text, count=1)
if count != 1:
    raise SystemExit(f"VirtualRecent getChannelEpgNowNext replacement count={count}")
virtual_text = virtual_text.replace(
    "\nprivate const val EPG_NOW_NEXT_LOOKBACK_MS = 6L * 60L * 60L * 1_000L\nprivate const val EPG_NOW_NEXT_LOOKAHEAD_MS = 18L * 60L * 60L * 1_000L",
    "",
)
virtual_path.write_text(virtual_text)

replace_exact(
    "feature/player/src/main/java/com/iptv/tv/feature/player/StablePlayerScreenReplacement.kt",
    '''    val selectedPrograms = selectedChannel
        ?.let { state.channelListEpgPrograms[it.id].orEmpty() }
        .orEmpty()''',
    '''    val selectedPrograms = selectedChannel
        ?.let { channel ->
            state.channelEpgInfo
                ?.takeIf { epgInfo -> epgInfo.channelId == channel.id }
                ?.schedule
                ?.takeIf(List<EpgProgram>::isNotEmpty)
                ?: state.channelListEpgPrograms[channel.id].orEmpty()
        }
        .orEmpty()''',
)
