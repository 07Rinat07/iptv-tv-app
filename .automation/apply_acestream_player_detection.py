from pathlib import Path

path = Path("feature/player/src/main/java/com/iptv/tv/feature/player/PlayerViewModel.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    text = text.replace(old, new, 1)


replace_once(
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

replace_once(
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

replace_once(
    '''                value.startsWith("infohash:", ignoreCase = true) ||
                value.endsWith(".torrent", ignoreCase = true)
''',
    '''                value.startsWith("infohash:", ignoreCase = true) ||
                value.substringBefore('?').substringBefore('#').endsWith(".torrent", ignoreCase = true) ||
                value.substringBefore('?').substringBefore('#').endsWith(".acelive", ignoreCase = true)
''',
    "Ace query descriptor extensions",
)

path.write_text(text, encoding="utf-8")
