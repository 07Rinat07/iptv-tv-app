package com.iptv.tv.feature.epg

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EpgGuideScreen(
    onOpenPlayer: (playlistId: Long, channelId: Long) -> Unit,
    onOpenPlayerSettings: (() -> Unit)? = null,
    viewModel: EpgGuideViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val gridScrollState = rememberScrollState()
    var isProgramListExpanded by rememberSaveable { mutableStateOf(false) }

    state.selectedProgram?.let { selected ->
        EpgProgramDetailDialog(
            selected = selected,
            isSchedulingRecording = state.isSchedulingRecording,
            onDismiss = viewModel::clearSelectedProgram,
            onOpenPlayer = {
                viewModel.clearSelectedProgram()
                onOpenPlayer(selected.row.channel.playlistId, selected.row.channel.id)
            },
            onRecord = viewModel::scheduleSelectedProgram
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(state.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Окно: ${formatWindow(state.windowStartMs, state.windowEndMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PlaylistPicker(
                        playlists = state.playlists,
                        selectedPlaylistId = state.selectedPlaylistId,
                        onSelected = viewModel::selectPlaylist
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        EpgPresetChip("Сейчас", EpgWindowPreset.NOW, state.selectedPreset, viewModel::selectPreset)
                        EpgPresetChip("Сегодня", EpgWindowPreset.TODAY, state.selectedPreset, viewModel::selectPreset)
                        EpgPresetChip("Завтра", EpgWindowPreset.TOMORROW, state.selectedPreset, viewModel::selectPreset)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::updateQuery,
                            label = { Text("Поиск по передачам") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = viewModel::applySearch) {
                            Text("Найти")
                        }
                        Button(onClick = viewModel::refresh) {
                            Text("Обновить")
                        }
                    }
                    Text(
                        text = state.error ?: state.status,
                        color = if (state.error == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (state.error != null && onOpenPlayerSettings != null) {
                        Button(onClick = onOpenPlayerSettings) {
                            Text("Открыть плеер и EPG мастер")
                        }
                    }
                    if (state.isLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        if (state.rows.isEmpty() && !state.isLoading) {
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Text(
                        text = "Нет программ для выбранного окна. Проверьте EPG URL плейлиста или выберите другой день.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        if (state.rows.isNotEmpty()) {
            item {
                EpgGridHeader(
                    windowStartMs = state.windowStartMs,
                    windowEndMs = state.windowEndMs,
                    scrollState = gridScrollState
                )
            }
            items(state.rows, key = { "grid-${it.channel.id}" }) { row ->
                EpgGridRow(
                    row = row,
                    windowStartMs = state.windowStartMs,
                    windowEndMs = state.windowEndMs,
                    scrollState = gridScrollState,
                    isSchedulingRecording = state.isSchedulingRecording,
                    onOpenPlayer = {
                        onOpenPlayer(row.channel.playlistId, row.channel.id)
                    },
                    onShowProgramDetails = { program -> viewModel.selectProgram(row, program) },
                    onRecordProgram = { program -> viewModel.scheduleRecording(row, program) }
                )
            }
            if (state.totalRowCount > state.rows.size) {
                item {
                    EpgRowsPrefetchItem(
                        loadedRowCount = state.rows.size,
                        totalRowCount = state.totalRowCount,
                        onLoadMore = viewModel::showMoreRows
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isProgramListExpanded) {
                                "Список передач открыт: удобно читать описания и быстро просматривать эфир."
                            } else {
                                "Список передач скрыт, чтобы не перегружать экран на больших EPG."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = { isProgramListExpanded = !isProgramListExpanded }) {
                            Text(if (isProgramListExpanded) "Скрыть список передач" else "Показать список передач")
                        }
                    }
                }
            }
        }

        if (isProgramListExpanded) {
            items(state.rows, key = { it.channel.id }) { row ->
                EpgChannelCard(
                    row = row,
                    isSchedulingRecording = state.isSchedulingRecording,
                    onOpenPlayer = {
                        onOpenPlayer(row.channel.playlistId, row.channel.id)
                    },
                    onShowProgramDetails = { program -> viewModel.selectProgram(row, program) },
                    onRecordProgram = { program -> viewModel.scheduleRecording(row, program) }
                )
            }
        }
    }
}

@Composable
private fun EpgRowsPrefetchItem(
    loadedRowCount: Int,
    totalRowCount: Int,
    onLoadMore: () -> Unit
) {
    LaunchedEffect(loadedRowCount, totalRowCount) {
        if (loadedRowCount < totalRowCount) {
            onLoadMore()
        }
    }
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Подгружаем ещё каналы EPG: $loadedRowCount из $totalRowCount",
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EpgGridHeader(
    windowStartMs: Long,
    windowEndMs: Long,
    scrollState: androidx.compose.foundation.ScrollState
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Канал",
                modifier = Modifier.width(CHANNEL_COLUMN_WIDTH),
                style = MaterialTheme.typography.titleSmall
            )
            Row(modifier = Modifier.horizontalScroll(scrollState)) {
                val hours = gridHourCount(windowStartMs, windowEndMs)
                repeat(hours) { index ->
                    val slotStart = windowStartMs + TimeUnit.HOURS.toMillis(index.toLong())
                    Text(
                        text = formatTime(slotStart),
                        modifier = Modifier.width(HOUR_WIDTH),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun EpgGridRow(
    row: EpgChannelRow,
    windowStartMs: Long,
    windowEndMs: Long,
    scrollState: androidx.compose.foundation.ScrollState,
    isSchedulingRecording: Boolean,
    onOpenPlayer: () -> Unit,
    onShowProgramDetails: (EpgProgram) -> Unit,
    onRecordProgram: (EpgProgram) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.width(CHANNEL_COLUMN_WIDTH),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = row.channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = row.channel.group ?: "Без группы",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Button(onClick = onOpenPlayer) {
                    Text("Смотреть")
                }
            }
            Row(modifier = Modifier.horizontalScroll(scrollState)) {
                var cursorMs = windowStartMs
                row.programs
                    .filter { it.endEpochMs > windowStartMs && it.startEpochMs < windowEndMs }
                    .sortedBy { it.startEpochMs }
                    .forEach { program ->
                        val clippedStart = max(program.startEpochMs, windowStartMs)
                        val clippedEnd = min(program.endEpochMs, windowEndMs)
                        if (clippedStart > cursorMs) {
                            Spacer(modifier = Modifier.width(widthForDuration(clippedStart - cursorMs)))
                        }
                        ProgramGridCard(
                            program = program,
                            width = widthForDuration(clippedEnd - clippedStart),
                            isSchedulingRecording = isSchedulingRecording,
                            onShowDetails = { onShowProgramDetails(program) },
                            onRecordProgram = { onRecordProgram(program) }
                        )
                        cursorMs = clippedEnd
                    }
                if (cursorMs < windowEndMs) {
                    Spacer(modifier = Modifier.width(widthForDuration(windowEndMs - cursorMs)))
                }
            }
        }
    }
}

@Composable
private fun ProgramGridCard(
    program: EpgProgram,
    width: androidx.compose.ui.unit.Dp,
    isSchedulingRecording: Boolean,
    onShowDetails: () -> Unit,
    onRecordProgram: () -> Unit
) {
    val isOnNow = program.isOnNow()
    Card(
        modifier = Modifier
            .width(width)
            .heightIn(min = 92.dp)
            .padding(end = 6.dp)
            .clickable(onClick = onShowDetails),
        colors = if (isOnNow) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isOnNow) {
                Text(
                    text = "Сейчас",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "${formatTime(program.startEpochMs)}-${formatTime(program.endEpochMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isOnNow) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (width.value >= 150f) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = onShowDetails) {
                        Text("Детали")
                    }
                    Button(
                        onClick = onRecordProgram,
                        enabled = !isSchedulingRecording && program.endEpochMs > System.currentTimeMillis()
                    ) {
                        Text(if (isSchedulingRecording) "..." else "Записать")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPicker(
    playlists: List<Playlist>,
    selectedPlaylistId: Long?,
    onSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = playlists.firstOrNull { it.id == selectedPlaylistId }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selected?.name ?: "Плейлист не выбран",
            onValueChange = {},
            readOnly = true,
            label = { Text("Плейлист") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            playlists.forEach { playlist ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (playlist.epgSourceUrl.isNullOrBlank()) {
                                "${playlist.name} (EPG не задан)"
                            } else {
                                playlist.name
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelected(playlist.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun EpgPresetChip(
    label: String,
    preset: EpgWindowPreset,
    selectedPreset: EpgWindowPreset,
    onSelected: (EpgWindowPreset) -> Unit
) {
    FilterChip(
        selected = preset == selectedPreset,
        onClick = { onSelected(preset) },
        label = { Text(label) }
    )
}

@Composable
private fun EpgChannelCard(
    row: EpgChannelRow,
    isSchedulingRecording: Boolean,
    onOpenPlayer: () -> Unit,
    onShowProgramDetails: (EpgProgram) -> Unit,
    onRecordProgram: (EpgProgram) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = row.channel.group ?: "Без группы",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(onClick = onOpenPlayer) {
                    Text("Смотреть")
                }
            }
            row.programs.take(8).forEach { program ->
                ProgramRow(
                    program = program,
                    isSchedulingRecording = isSchedulingRecording,
                    onShowDetails = { onShowProgramDetails(program) },
                    onRecordProgram = { onRecordProgram(program) }
                )
            }
            if (row.programs.size > 8) {
                Text(
                    text = "Еще передач: ${row.programs.size - 8}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProgramRow(
    program: EpgProgram,
    isSchedulingRecording: Boolean,
    onShowDetails: () -> Unit,
    onRecordProgram: () -> Unit
) {
    val isOnNow = program.isOnNow()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = buildString {
                    if (isOnNow) append("Сейчас | ")
                    append("${formatTime(program.startEpochMs)}-${formatTime(program.endEpochMs)}  ${program.title}")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isOnNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = onShowDetails
            ) {
                Text("Подробнее")
            }
            Button(
                onClick = onRecordProgram,
                enabled = !isSchedulingRecording && program.endEpochMs > System.currentTimeMillis()
            ) {
                Text(if (isSchedulingRecording) "..." else "Записать")
            }
        }
        val details = listOfNotNull(program.category, program.description).joinToString(" | ")
        if (details.isNotBlank()) {
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EpgProgramDetailDialog(
    selected: SelectedEpgProgram,
    isSchedulingRecording: Boolean,
    onDismiss: () -> Unit,
    onOpenPlayer: () -> Unit,
    onRecord: () -> Unit
) {
    val program = selected.program
    val channel = selected.row.channel
    val isOnNow = program.isOnNow()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = program.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isOnNow) {
                    Text(
                        text = "Сейчас в эфире",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(channel.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${formatWindow(program.startEpochMs, program.endEpochMs)} | ${channel.group ?: "Без группы"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                program.category?.takeIf { it.isNotBlank() }?.let {
                    Text("Категория: $it", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = program.description?.takeIf { it.isNotBlank() } ?: "Описание для передачи не найдено.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onOpenPlayer) {
                Text("Смотреть")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = onRecord,
                    enabled = !isSchedulingRecording && program.endEpochMs > System.currentTimeMillis()
                ) {
                    Text(if (isSchedulingRecording) "Запись..." else "Записать")
                }
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        }
    )
}

private fun formatTime(epochMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMs))
}

private fun formatWindow(startMs: Long, endMs: Long): String {
    if (startMs <= 0L || endMs <= 0L) return "-"
    val formatter = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    return "${formatter.format(Date(startMs))} - ${formatter.format(Date(endMs))}"
}

private fun gridHourCount(startMs: Long, endMs: Long): Int {
    val durationMs = (endMs - startMs).coerceAtLeast(TimeUnit.HOURS.toMillis(1))
    return ceil(durationMs / TimeUnit.HOURS.toMillis(1).toDouble()).toInt().coerceAtLeast(1)
}

private fun widthForDuration(durationMs: Long): androidx.compose.ui.unit.Dp {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs).coerceAtLeast(1)
    val width = (minutes / 60f) * HOUR_WIDTH.value
    return width.coerceAtLeast(16f).dp
}

private fun EpgProgram.isOnNow(nowMs: Long = System.currentTimeMillis()): Boolean {
    return startEpochMs <= nowMs && endEpochMs > nowMs
}

private val CHANNEL_COLUMN_WIDTH = 184.dp
private val HOUR_WIDTH = 220.dp
