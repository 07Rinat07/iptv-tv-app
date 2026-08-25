package com.iptv.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
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
    val p2pLabel = channel
        ?.takeIf { PlayerP2pDescriptor.detect(it.streamUrl) != null }
        ?.let { p2pChannelAvailabilityLabel(P2pChannelAvailabilityUiCache.statuses[it.id]) }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.44f)
            .widthIn(max = 520.dp),
        shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = 0.80f),
        contentColor = Color.White,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (!channel?.logo.isNullOrBlank()) {
                AsyncImage(
                    model = channel?.logo,
                    contentDescription = channel?.name,
                    modifier = Modifier.size(36.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    channel?.name ?: "Канал",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (current != null) {
                    Text(
                        "Сейчас ${stableRange(current)} · ${current.title}",
                        color = Color.White.copy(alpha = 0.92f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    LinearProgressIndicator(
                        progress = { stableProgramProgress(current, nowMs) },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        "Программа не найдена",
                        color = Color.White.copy(alpha = 0.66f),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                next?.let {
                    Text(
                        "Далее ${stableTime(it.startEpochMs)} · ${it.title}",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (p2pLabel != null) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    ) {
                        Text(
                            p2pLabel,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
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

internal fun stableProgramProgress(program: EpgProgram, nowMs: Long): Float {
    val duration = (program.endEpochMs - program.startEpochMs).coerceAtLeast(1L)
    return ((nowMs - program.startEpochMs).toDouble() / duration.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
}

internal fun stableProgramRemainingMinutes(program: EpgProgram, nowMs: Long): Long =
    ((program.endEpochMs - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L

internal fun stableTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))

internal fun stableRange(program: EpgProgram): String =
    "${stableTime(program.startEpochMs)}–${stableTime(program.endEpochMs)}"
