package com.iptv.tv.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TvScrollableLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scrollbarMinScreenWidthDp: Int = 600,
    scrollControlsMinScreenWidthDp: Int = 360,
    showPageControls: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val showControls = showPageControls && screenWidthDp >= scrollControlsMinScreenWidthDp
    val keyScrollScope = rememberCoroutineScope()

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val command = when (event.key) {
                        Key.PageUp, Key.ChannelUp -> TvListScrollCommand.PAGE_UP
                        Key.PageDown, Key.ChannelDown -> TvListScrollCommand.PAGE_DOWN
                        else -> null
                    } ?: return@onPreviewKeyEvent false
                    val target = calculateTvListScrollTarget(
                        command = command,
                        firstVisibleItemIndex = state.firstVisibleItemIndex,
                        visibleItemCount = state.layoutInfo.visibleItemsInfo.size,
                        totalItemCount = state.layoutInfo.totalItemsCount
                    ) ?: return@onPreviewKeyEvent false
                    keyScrollScope.launch { state.animateScrollToItem(target) }
                    true
                },
            state = state,
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            contentPadding = contentPadding,
            content = content
        )

        if (showControls) {
            TvLazyColumnNavigationRail(
                listState = state,
                showScrollbar = screenWidthDp >= scrollbarMinScreenWidthDp,
                modifier = Modifier.fillMaxHeight()
            )
        } else {
            TvLazyColumnScrollbar(
                listState = state,
                modifier = Modifier.fillMaxHeight(),
                minScreenWidthDp = scrollbarMinScreenWidthDp
            )
        }
    }
}

@Composable
private fun TvLazyColumnNavigationRail(
    listState: LazyListState,
    showScrollbar: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val totalItems by remember(listState) {
        derivedStateOf { listState.layoutInfo.totalItemsCount }
    }
    val visibleItems by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
    }
    val firstVisible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    val scrollable = totalItems > visibleItems

    if (!scrollable) return

    val canScrollUp = listState.canScrollBackward
    val canScrollDown = listState.canScrollForward
    val showHome = canScrollUp && firstVisible > 0

    Column(
        modifier = modifier.width(52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TvScrollButton(
            symbol = "⌂",
            description = "Вернуться в начало списка",
            enabled = showHome,
            onClick = { scope.launch { listState.animateScrollToItem(0) } }
        )
        TvScrollButton(
            symbol = "⇞",
            description = "Прокрутить на страницу вверх",
            enabled = canScrollUp,
            onClick = {
                val target = calculateTvListScrollTarget(
                    command = TvListScrollCommand.PAGE_UP,
                    firstVisibleItemIndex = firstVisible,
                    visibleItemCount = visibleItems,
                    totalItemCount = totalItems
                ) ?: return@TvScrollButton
                scope.launch { listState.animateScrollToItem(target) }
            }
        )

        if (showScrollbar) {
            TvLazyColumnScrollbar(
                listState = listState,
                modifier = Modifier.weight(1f),
                minScreenWidthDp = 0
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        TvScrollButton(
            symbol = "⇟",
            description = "Прокрутить на страницу вниз",
            enabled = canScrollDown,
            onClick = {
                val target = calculateTvListScrollTarget(
                    command = TvListScrollCommand.PAGE_DOWN,
                    firstVisibleItemIndex = firstVisible,
                    visibleItemCount = visibleItems,
                    totalItemCount = totalItems
                ) ?: return@TvScrollButton
                scope.launch { listState.animateScrollToItem(target) }
            }
        )
    }
}

@Composable
private fun TvScrollButton(
    symbol: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(text = symbol, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun TvLazyColumnScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    minScreenWidthDp: Int = 600,
    dragScrollMultiplier: Float = 6f
) {
    if (LocalConfiguration.current.screenWidthDp < minScreenWidthDp) return

    val scope = rememberCoroutineScope()
    val totalItems by remember(listState) {
        derivedStateOf { listState.layoutInfo.totalItemsCount }
    }
    val visibleItems by remember(listState) {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) }
    }
    val lastIndex = (totalItems - 1).coerceAtLeast(0)
    val firstVisible by remember(listState, lastIndex) {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, lastIndex) }
    }
    val scrollableItems = (totalItems - visibleItems).coerceAtLeast(1)
    val enabled = totalItems > visibleItems
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.width(28.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        val trackHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val thumbHeightFraction = if (totalItems <= 0) {
            1f
        } else {
            (visibleItems.toFloat() / totalItems.toFloat()).coerceIn(0.08f, 1f)
        }
        val thumbHeightPx = trackHeightPx * thumbHeightFraction
        val maxThumbOffsetPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val thumbOffsetPx = if (!enabled) {
            0f
        } else {
            maxThumbOffsetPx * (firstVisible.toFloat() / scrollableItems.toFloat())
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(12.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                    shape = MaterialTheme.shapes.small
                )
        )
        Box(
            modifier = Modifier
                .offset(y = with(density) { thumbOffsetPx.toDp() })
                .width(20.dp)
                .height(with(density) { thumbHeightPx.toDp().coerceAtLeast(44.dp) })
                .background(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = MaterialTheme.shapes.small
                )
                .pointerInput(enabled, totalItems) {
                    if (!enabled) return@pointerInput
                    detectVerticalDragGestures { _, dragAmount ->
                        scope.launch {
                            listState.scrollBy(dragAmount * dragScrollMultiplier)
                        }
                    }
                }
        )
    }
}
