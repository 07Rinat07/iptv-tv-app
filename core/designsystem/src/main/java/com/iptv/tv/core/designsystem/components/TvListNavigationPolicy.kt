package com.iptv.tv.core.designsystem.components

internal enum class TvListScrollCommand {
    START,
    PAGE_UP,
    PAGE_DOWN,
    END
}

internal fun calculateTvListScrollTarget(
    command: TvListScrollCommand,
    firstVisibleItemIndex: Int,
    visibleItemCount: Int,
    totalItemCount: Int
): Int? {
    if (totalItemCount <= 0) return null

    val lastIndex = totalItemCount - 1
    val firstVisible = firstVisibleItemIndex.coerceIn(0, lastIndex)
    val pageSize = (visibleItemCount.coerceAtLeast(1) - 1).coerceAtLeast(1)

    return when (command) {
        TvListScrollCommand.START -> 0
        TvListScrollCommand.PAGE_UP -> (firstVisible - pageSize).coerceAtLeast(0)
        TvListScrollCommand.PAGE_DOWN -> (firstVisible + pageSize).coerceAtMost(lastIndex)
        TvListScrollCommand.END -> lastIndex
    }
}
