package com.iptv.tv.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
internal fun StableChannelBannerReplacement(
    channel: Channel?,
    programs: List<EpgProgram>,
    modifier: Modifier = Modifier
) {
    val nowMs = System.currentTimeMillis()
    val current = stableCurrentProgram(programs, nowMs)
    val next = stableNextProgram(programs, current, nowMs)
    Card(modifier = modifier.fillMaxWidth(0.62f)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(channel?.name ?: "Канал", fontWeight = FontWeight.Bold)
            Text(current?.let { "Сейчас ${stableRange(it)} · ${it.title}" } ?: "Сейчас: программа не найдена")
            Text(
                next?.let { "Далее ${stableRange(it)} · ${it.title}" } ?: "Далее: данных нет",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
internal fun VolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    dark: Boolean = false
) {
    val labelColor = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 460.dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)
        ) {
            OutlinedButton(
                onClick = onToggleMute,
                modifier = Modifier.size(42.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text(if (volume <= 0f) "🔇" else "🔊") }
            OutlinedButton(
                onClick = { onVolumeChange(volume - VOLUME_STEP) },
                modifier = Modifier.size(42.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("−") }
            if (!compact) {
                Slider(
                    value = volume,
                    onValueChange = { onVolumeChange(it.coerceIn(0f, 1f)) },
                    modifier = Modifier.weight(1f),
                    steps = 19,
                    valueRange = 0f..1f
                )
            } else {
                Text(
                    "${(volume * 100).toInt()}%",
                    modifier = Modifier.weight(1f),
                    color = labelColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
            OutlinedButton(
                onClick = { onVolumeChange(volume + VOLUME_STEP) },
                modifier = Modifier.size(42.dp),
                contentPadding = PaddingValues(0.dp)
            ) { Text("+") }
            if (!compact) {
                Text("${(volume * 100).toInt()}%", color = labelColor)
            }
        }
    }
}

@Composable
internal fun VerticalScrollControls(
    state: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val pageSize = state.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)

    Column(
        modifier = modifier.padding(start = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedButton(
            onClick = { scope.launch { state.animateScrollToItem(0) } },
            enabled = itemCount > 0 && state.canScrollBackward
        ) { Text("⌂") }
        OutlinedButton(
            onClick = {
                scope.launch {
                    state.animateScrollToItem(
                        StableChannelNavigation.pageTargetIndex(
                            currentIndex = state.firstVisibleItemIndex,
                            itemCount = itemCount,
                            pageSize = pageSize,
                            direction = -1
                        )
                    )
                }
            },
            enabled = state.canScrollBackward
        ) { Text("▲") }
        Spacer(Modifier.weight(1f))
        Text("${if (itemCount == 0) 0 else state.firstVisibleItemIndex + 1}/$itemCount")
        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = {
                scope.launch {
                    state.animateScrollToItem(
                        StableChannelNavigation.pageTargetIndex(
                            currentIndex = state.firstVisibleItemIndex,
                            itemCount = itemCount,
                            pageSize = pageSize,
                            direction = 1
                        )
                    )
                }
            },
            enabled = state.canScrollForward
        ) { Text("▼") }
    }
}

internal fun stableCurrentProgram(programs: List<EpgProgram>, nowMs: Long): EpgProgram? =
    programs.firstOrNull { nowMs >= it.startEpochMs && nowMs < it.endEpochMs }

internal fun stableNextProgram(
    programs: List<EpgProgram>,
    current: EpgProgram?,
    nowMs: Long
): EpgProgram? {
    val threshold = current?.endEpochMs ?: nowMs
    return programs.filter { it.startEpochMs >= threshold }.minByOrNull { it.startEpochMs }
}

internal fun stableTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

internal fun stableRange(program: EpgProgram): String =
    "${stableTime(program.startEpochMs)}–${stableTime(program.endEpochMs)}"
