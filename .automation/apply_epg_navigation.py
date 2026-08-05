from pathlib import Path

path = Path("feature/epg/src/main/java/com/iptv/tv/feature/epg/EpgGuideScreen.kt")
text = path.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    text = text.replace(old, new, 1)


import_line = "import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn\n"
replace_once(
    import_line,
    "import com.iptv.tv.core.designsystem.components.TvHorizontalScrollControls\n" + import_line,
    "EPG controls import",
)

old_grid = """            item {
                EpgGridHeader(
                    windowStartMs = state.windowStartMs,
                    windowEndMs = state.windowEndMs,
                    scrollState = gridScrollState
                )
            }
"""
new_grid = """            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvHorizontalScrollControls(state = gridScrollState)
                    EpgGridHeader(
                        windowStartMs = state.windowStartMs,
                        windowEndMs = state.windowEndMs,
                        scrollState = gridScrollState
                    )
                }
            }
"""
replace_once(old_grid, new_grid, "EPG grid controls")

replace_once(
    'private val EPG_GUIDE_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Oral")',
    'private val EPG_GUIDE_TIME_ZONE: TimeZone = TimeZone.getDefault()',
    "EPG device time zone",
)

path.write_text(text, encoding="utf-8")
