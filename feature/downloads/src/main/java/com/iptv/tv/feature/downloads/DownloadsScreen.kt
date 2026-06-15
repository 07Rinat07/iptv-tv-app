package com.iptv.tv.feature.downloads

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.RecordingSchedule
import com.iptv.tv.core.model.RecordingStatus
import com.iptv.tv.core.model.RecordingTask
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
        }

        item {
            OutlinedTextField(
                value = state.sourceInput,
                onValueChange = viewModel::updateSourceInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("magnet:/acestream:/ace:/.torrent") },
                minLines = 2
            )
        }

        item {
            OutlinedTextField(
                value = state.maxConcurrentInput,
                onValueChange = viewModel::updateMaxConcurrentInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Параллельных задач") },
                singleLine = true
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::enqueue) {
                    Text("Добавить в очередь")
                }
                Button(onClick = viewModel::processQueueNow, enabled = !state.isBusy) {
                    Text(if (state.isBusy) "Обработка..." else "Обработать сейчас")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Запись эфира", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "MVP-захват прямых HTTP/HTTPS потоков в выбранную папку записей. Worker проверяет очередь каждые 15 минут.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = state.recordingChannelIdInput,
                        onValueChange = viewModel::updateRecordingChannelId,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Channel ID") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.recordingTitleInput,
                        onValueChange = viewModel::updateRecordingTitle,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название передачи/записи") },
                        singleLine = true
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.recordingDelayMinutesInput,
                            onValueChange = viewModel::updateRecordingDelayMinutes,
                            modifier = Modifier.weight(1f),
                            label = { Text("Старт через, мин") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = state.recordingDurationMinutesInput,
                            onValueChange = viewModel::updateRecordingDurationMinutes,
                            modifier = Modifier.weight(1f),
                            label = { Text("Длительность, мин") },
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::recordNow) {
                            Text("Записать сейчас")
                        }
                        Button(onClick = viewModel::scheduleRecording) {
                            Text("Запланировать")
                        }
                        Button(
                            onClick = viewModel::processRecordingsNow,
                            enabled = !state.isProcessingRecordings
                        ) {
                            Text(if (state.isProcessingRecordings) "Запись..." else "Обработать")
                        }
                    }
                }
            }
        }

        state.lastError?.let { error ->
            item {
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }
        state.lastInfo?.let { info ->
            item {
                Text(text = info)
            }
        }

        if (state.tasks.isEmpty()) {
            item {
                Text("Очередь загрузок пуста")
            }
        } else {
            items(state.tasks, key = { it.id }) { task ->
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Task #${task.id} | ${task.status}")
                        Text("Progress: ${task.progress}%")
                        Text("Source: ${task.source}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.pause(task.id) },
                                enabled = viewModel.canPause(task.status)
                            ) {
                                Text("Пауза")
                            }
                            Button(
                                onClick = { viewModel.resume(task.id) },
                                enabled = viewModel.canResume(task.status)
                            ) {
                                Text("Resume")
                            }
                            Button(
                                onClick = { viewModel.cancel(task.id) },
                                enabled = viewModel.canCancel(task.status)
                            ) {
                                Text("Отменить")
                            }
                            Button(onClick = { viewModel.remove(task.id) }) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Записи (${state.recordings.size})", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.cleanupRecordings(7) },
                            enabled = !state.isProcessingRecordings
                        ) {
                            Text("Очистить старше 7 дней")
                        }
                        Button(
                            onClick = { viewModel.cleanupRecordings(30) },
                            enabled = !state.isProcessingRecordings
                        ) {
                            Text("Очистить старше 30 дней")
                        }
                    }
                }
            }
        }
        if (state.recordings.isEmpty()) {
            item {
                Text("Очередь записей пуста")
            }
        } else {
            items(state.recordings, key = { it.id }) { recording ->
                RecordingCard(
                    recording = recording,
                    canCancel = viewModel.canCancelRecording(recording.status),
                    canDelete = viewModel.canDeleteRecording(recording.status),
                    onOpen = { openRecordingFile(context, recording) },
                    onCancel = { viewModel.cancelRecording(recording.id) },
                    onDelete = { viewModel.deleteRecording(recording.id) }
                )
            }
        }

        item {
            Text("Расписания записей (${state.schedules.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (state.schedules.isEmpty()) {
            item {
                Text("Расписаний пока нет")
            }
        } else {
            items(state.schedules, key = { it.id }) { schedule ->
                ScheduleCard(
                    schedule = schedule,
                    onToggleEnabled = { enabled -> viewModel.setScheduleEnabled(schedule.id, enabled) },
                    onDelete = { viewModel.deleteSchedule(schedule.id) }
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: RecordingTask,
    canCancel: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    val recordingPath = recording.filePath
    val canOpen = recording.status == RecordingStatus.COMPLETED &&
        !recordingPath.isNullOrBlank() &&
        File(recordingPath).exists()
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Recording #${recording.id} | ${recording.status}", style = MaterialTheme.typography.titleSmall)
            Text(recording.programTitle ?: recording.channelName)
            Text("Канал: ${recording.channelName} (id=${recording.channelId})")
            Text("Старт: ${recording.scheduledStartAt?.let(::formatEpoch) ?: "-"}")
            Text("Финиш: ${recording.scheduledEndAt?.let(::formatEpoch) ?: "-"}")
            Text("Фактически: ${recording.toActualRecordingWindowLabel()}")
            Text("Длительность: ${recording.toActualRecordingDurationLabel()}")
            Text("Файл: ${recording.filePath ?: "ещё не создан"}")
            Text("Размер: ${recording.filePath.toRecordingFileSizeLabel()}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, enabled = canOpen) {
                    Text("Открыть запись")
                }
                Button(onClick = onCancel, enabled = canCancel) {
                    Text("Отменить запись")
                }
                Button(onClick = onDelete, enabled = canDelete) {
                    Text("Удалить запись и файл")
                }
            }
        }
    }
}

private fun openRecordingFile(context: Context, recording: RecordingTask) {
    val path = recording.filePath
    if (recording.status != RecordingStatus.COMPLETED || path.isNullOrBlank()) {
        Toast.makeText(context, "Файл записи ещё не готов", Toast.LENGTH_SHORT).show()
        return
    }
    val file = File(path)
    if (!file.exists()) {
        Toast.makeText(context, "Файл записи не найден", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeForRecording(file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Открыть запись"))
    }.onFailure {
        Toast.makeText(context, "Не найдено приложение для открытия записи", Toast.LENGTH_SHORT).show()
    }
}

private fun mimeTypeForRecording(file: File): String {
    return when (file.extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "ts", "m2ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        else -> "video/*"
    }
}

private fun String?.toRecordingFileSizeLabel(): String {
    val file = this?.takeIf { it.isNotBlank() }?.let(::File) ?: return "-"
    if (!file.exists() || !file.isFile) return "-"
    val bytes = file.length()
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gib)
    val mib = bytes / (1024.0 * 1024.0)
    if (mib >= 1.0) return String.format(Locale.getDefault(), "%.1f MB", mib)
    val kib = bytes / 1024.0
    return String.format(Locale.getDefault(), "%.0f KB", kib)
}

private fun RecordingTask.toActualRecordingWindowLabel(): String {
    val start = startedAt?.let(::formatEpoch) ?: "-"
    val end = endedAt?.let(::formatEpoch) ?: "-"
    return "$start - $end"
}

private fun RecordingTask.toActualRecordingDurationLabel(): String {
    val start = startedAt ?: return "-"
    val end = endedAt ?: System.currentTimeMillis().takeIf { status == RecordingStatus.RECORDING } ?: return "-"
    val durationMs = (end - start).coerceAtLeast(0L)
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

@Composable
private fun ScheduleCard(
    schedule: RecordingSchedule,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Schedule #${schedule.id} | ${schedule.repeatMode} | ${if (schedule.enabled) "enabled" else "disabled"}")
            Text(schedule.programTitle ?: schedule.channelName)
            Text("Канал: ${schedule.channelName} (id=${schedule.channelId})")
            Text("${formatEpoch(schedule.startAt)} - ${formatEpoch(schedule.endAt)}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onToggleEnabled(!schedule.enabled) }) {
                    Text(if (schedule.enabled) "Выключить" else "Включить")
                }
                Button(onClick = onDelete) {
                    Text("Удалить расписание")
                }
            }
        }
    }
}

private fun formatEpoch(value: Long): String {
    return SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(value))
}
