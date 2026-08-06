package com.iptv.tv.feature.player

import android.view.KeyEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch

internal enum class StableListRemoteAction {
    FIRST,
    PAGE_UP,
    PAGE_DOWN,
    LAST,
    NONE
}

internal fun stableListRemoteActionForKey(keyCode: Int): StableListRemoteAction = when (keyCode) {
    KeyEvent.KEYCODE_MOVE_HOME -> StableListRemoteAction.FIRST
    KeyEvent.KEYCODE_PAGE_UP -> StableListRemoteAction.PAGE_UP
    KeyEvent.KEYCODE_PAGE_DOWN -> StableListRemoteAction.PAGE_DOWN
    KeyEvent.KEYCODE_MOVE_END -> StableListRemoteAction.LAST
    else -> StableListRemoteAction.NONE
}

internal fun Modifier.stablePagedListNavigation(
    state: LazyListState,
    itemCount: Int
): Modifier = composed {
    val scope = rememberCoroutineScope()
    this.onPreviewKeyEvent { event ->
        val nativeEvent = event.nativeKeyEvent
        if (nativeEvent.action != KeyEvent.ACTION_DOWN || nativeEvent.repeatCount > 0) {
            return@onPreviewKeyEvent false
        }

        val targetIndex = when (stableListRemoteActionForKey(nativeEvent.keyCode)) {
            StableListRemoteAction.FIRST -> 0
            StableListRemoteAction.PAGE_UP -> StableChannelNavigation.pageTargetIndex(
                currentIndex = state.firstVisibleItemIndex,
                itemCount = itemCount,
                pageSize = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1),
                direction = -1
            )
            StableListRemoteAction.PAGE_DOWN -> StableChannelNavigation.pageTargetIndex(
                currentIndex = state.firstVisibleItemIndex,
                itemCount = itemCount,
                pageSize = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1),
                direction = 1
            )
            StableListRemoteAction.LAST -> (itemCount - 1).coerceAtLeast(0)
            StableListRemoteAction.NONE -> return@onPreviewKeyEvent false
        }

        if (itemCount > 0) {
            scope.launch { state.animateScrollToItem(targetIndex) }
        }
        true
    }
}

internal fun Modifier.stableSelectedFocus(selected: Boolean): Modifier = composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(selected) {
        if (selected) {
            runCatching { requester.requestFocus() }
        }
    }
    this.focusRequester(requester)
}
