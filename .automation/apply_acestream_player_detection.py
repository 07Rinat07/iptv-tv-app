from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


player_path = Path("feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt")
player_text = player_path.read_text(encoding="utf-8")

player_text = replace_once(
    player_text,
    '''    private fun isTorrentDescriptor(raw: String): Boolean {
        val normalized = raw.trim().lowercase()
        return normalized.startsWith("magnet:") ||
            normalized.startsWith("acestream://") ||
            normalized.startsWith("ace://") ||
            normalized.startsWith("infohash:") ||
            normalized.endsWith(".torrent") ||
            HASH40_REGEX.matches(normalized)
    }
''',
    '''    private fun isTorrentDescriptor(raw: String): Boolean {
        val normalized = raw.trim().lowercase()
        val path = normalized.substringBefore('?').substringBefore('#')
        return normalized.startsWith("magnet:") ||
            normalized.startsWith("acestream://") ||
            normalized.startsWith("ace://") ||
            normalized.startsWith("infohash:") ||
            path.endsWith(".torrent") ||
            path.endsWith(".acelive") ||
            HASH40_REGEX.matches(normalized)
    }
''',
    "Ace descriptor extensions",
)

player_text = replace_once(
    player_text,
    '''        if (trimmed.startsWith("infohash:", ignoreCase = true)) {
            val hash = trimmed.substringAfter(':').trim()
            return if (HASH40_REGEX.matches(hash)) "magnet:?xt=urn:btih:$hash" else trimmed
        }

        val lowered = trimmed.lowercase()
        if (lowered.startsWith("magnet:") || lowered.endsWith(".torrent")) {
            return trimmed
        }

        if (HASH40_REGEX.matches(trimmed)) {
            return "magnet:?xt=urn:btih:$trimmed"
        }
''',
    '''        if (trimmed.startsWith("infohash:", ignoreCase = true)) {
            val hash = trimmed.substringAfter(':').trim()
            return if (HASH40_REGEX.matches(hash)) "acestream://$hash" else trimmed
        }

        val lowered = trimmed.lowercase()
        val path = lowered.substringBefore('?').substringBefore('#')
        if (lowered.startsWith("magnet:") ||
            path.endsWith(".torrent") ||
            path.endsWith(".acelive")
        ) {
            return trimmed
        }

        if (HASH40_REGEX.matches(trimmed)) {
            return "acestream://$trimmed"
        }
''',
    "Ace descriptor normalization",
)

player_text = replace_once(
    player_text,
    '''                value.startsWith("infohash:", ignoreCase = true) ||
                value.endsWith(".torrent", ignoreCase = true)
''',
    '''                value.startsWith("infohash:", ignoreCase = true) ||
                value.substringBefore('?').substringBefore('#').endsWith(".torrent", ignoreCase = true) ||
                value.substringBefore('?').substringBefore('#').endsWith(".acelive", ignoreCase = true)
''',
    "Ace query descriptor extensions",
)

player_path.write_text(player_text, encoding="utf-8")

engine_path = Path("core/engine/src/main/java/com/iptv/tv/core/engine/data/EngineStreamClient.kt")
engine_text = engine_path.read_text(encoding="utf-8")
engine_text = replace_once(
    engine_text,
    '''@Singleton
class EngineStreamClient private constructor(
    private val api: EngineStreamApi,
    private val serviceBridge: AceStreamServiceBridge?
) {
    @Inject
    constructor(
        api: EngineStreamApi,
        serviceBridge: AceStreamServiceBridge
    ) : this(api = api, serviceBridge = serviceBridge as AceStreamServiceBridge?)

    internal constructor(api: EngineStreamApi) : this(api = api, serviceBridge = null)
''',
    '''@Singleton
class EngineStreamClient @Inject constructor(
    private val api: EngineStreamApi,
    private val serviceBridge: AceStreamServiceBridge? = null
) {
''',
    "EngineStreamClient constructor",
)
engine_path.write_text(engine_text, encoding="utf-8")
