package com.iptv.tv.feature.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
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

@Composable
internal fun StablePanelDialogReplacement(
    panel: StablePlayerPanel,
    playlists: List<Playlist>,
    selectedPlaylistId: Long?,
    groups: List<String>,
    selectedGroup: String?,
    subGroups: List<String>,
    selectedSubGroup: String?,
    favoritesOnly: Boolean,
    bufferSummary: String,
    epgStatus: String,
    scale: PlayerVideoScale,
    volume: Float,
    primaryLabel: String,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
    onSelectGroup: (String?) -> Unit,
    onSelectSubGroup: (String?) -> Unit,
    onToggleFavoritesOnly: () -> Unit,
    onCycleScale: () -> Unit,
    onOpenAppSettings: (() -> Unit)?
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (panel) {
                    StablePlayerPanel.PLAYLISTS -> "Плейлисты"
                    StablePlayerPanel.GROUPS -> "Группы и фильтры"
                    StablePlayerPanel.SETTINGS -> "Настройки плеера"
                    StablePlayerPanel.NONE -> "Плеер"
                }
            )
        },
        text = {
            when (panel) {
                StablePlayerPanel.PLAYLISTS -> ScrollableButtonList(
                    labels = playlists.map { "${it.name} · ${it.channelCount}" },
                    selectedIndex = playlists.indexOfFirst { it.id == selectedPlaylistId },
                    onClick = { index -> playlists.getOrNull(index)?.id?.let(onSelectPlaylist) }
                )

                StablePlayerPanel.GROUPS -> Column(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ScrollableButtonList(
                        labels = listOf("Все группы") + groups,
                        selectedIndex = if (selectedGroup == null) 0 else groups.indexOf(selectedGroup) + 1,
                        onClick = { index -> onSelectGroup(if (index == 0) null else groups.getOrNull(index - 1)) },
                        modifier = Modifier.weight(1f)
                    )
                    if (subGroups.isNotEmpty()) {
                        ScrollableButtonList(
                            labels = listOf("Все подкатегории") + subGroups,
                            selectedIndex = if (selectedSubGroup == null) 0 else subGroups.indexOf(selectedSubGroup) + 1,
                            onClick = { index ->
                                onSelectSubGroup(if (index == 0) null else subGroups.getOrNull(index - 1))
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(onClick = onToggleFavoritesOnly, modifier = Modifier.fillMaxWidth()) {
                        Text(if (favoritesOnly) "Показать все каналы" else "Только избранное")
                    }
                }

                StablePlayerPanel.SETTINGS -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Режим кадра: $scale")
                    OutlinedButton(onClick = onCycleScale, modifier = Modifier.fillMaxWidth()) {
                        Text("Сменить режим кадра")
                    }
                    VolumeControl(
                        volume = volume,
                        onVolumeChange = onVolumeChange,
                        onToggleMute = onToggleMute
                    )
                    Text(bufferSummary, style = MaterialTheme.typography.bodySmall)
                    Text(epgStatus, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Media3 автоматически выбирает дорожки. Для проблемных потоков выполняется " +
                            "проверка видеокодека и перезапуск декодера."
                    )
                    onOpenAppSettings?.let {
                        Button(onClick = it, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
                    }
                }

                StablePlayerPanel.NONE -> Unit
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Готово") } }
    )
}

@Composable
private fun ScrollableButtonList(
    labels: List<String>,
    selectedIndex: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = rememberLazyListState()
    LaunchedEffect(selectedIndex, labels.size) {
        if (selectedIndex in labels.indices) {
            state.animateScrollToItem(selectedIndex)
        }
    }
    Row(modifier.fillMaxWidth().heightIn(min = 180.dp, max = 520.dp)) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusGroup()
                .stablePagedListNavigation(state = state, itemCount = labels.size),
            state = state,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(labels.size, key = { it }) { index ->
                if (index == selectedIndex) {
                    Button(
                        onClick = { onClick(index) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .stableSelectedFocus(true)
                    ) {
                        Text(
                            labels[index],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { onClick(index) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            labels[index],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        VerticalScrollControls(state = state, itemCount = labels.size)
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
