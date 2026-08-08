package com.iptv.tv.feature.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvBringIntoViewOnFocus
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.DownloadSourceType
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.DownloadTask
import com.iptv.tv.core.model.RecordingRepeatMode
import com.iptv.tv.core.model.RecordingSchedule
import com.iptv.tv.core.model.RecordingStatus
import com.iptv.tv.core.model.RecordingTask
import com.iptv.tv.core.model.TimeshiftBufferPlan
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
    TvScrollableLazyColumn(
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
                label = { Text("HTTP/HLS/magnet/acestream/.torrent") },
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
            Row(
                modifier = Modifier.tvBringIntoViewOnFocus(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                    Row(
                        modifier = Modifier.tvBringIntoViewOnFocus(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                    Text("Timeshift buffer", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.tvBringIntoViewOnFocus(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.timeshiftMinutesInput,
                            onValueChange = viewModel::updateTimeshiftMinutes,
                            modifier = Modifier.weight(1f),
                            label = { Text("Буфер, мин") },
                            singleLine = true
                        )
                        Button(onClick = viewModel::planTimeshiftBuffer) {
                            Text("Проверить")
                        }
                    }
                    state.lastTimeshiftPlan?.let { plan ->
                        TimeshiftPlanSummary(plan)
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
            item {
                Text(
                    text = state.tasks.toDownloadSourceSummaryLabel(),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(state.tasks, key = { it.id }) { task ->
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Задача #${task.id} | ${task.status.toDownloadStatusLabel()}")
                        Text("Тип: ${task.sourceType.toDownloadSourceTypeLabel()}")
                        Text("Progress: ${task.progress}%")
                        Text("Source: ${task.source}")
                        task.resolvedSource?.takeIf { it.isNotBlank() }?.let { resolvedSource ->
                            Text(
                                "Resolved: ${task.resolvedSourceType?.toDownloadSourceTypeLabel() ?: "Auto"} | $resolvedSource",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(
                            modifier = Modifier.tvBringIntoViewOnFocus(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                    Text(
                        text = state.recordings.toRecordingSummaryLabel(),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.tvBringIntoViewOnFocus(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                    context = context,
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
            Text(
                text = state.schedules.toScheduleSummaryLabel(),
                style = MaterialTheme.typography.bodySmall
            )
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
private fun TimeshiftPlanSummary(plan: TimeshiftBufferPlan) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = if (plan.supported) "Готово к timeshift" else "Timeshift заблокирован",
            color = if (plan.supported) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.bodyMedium
        )
        Text("Канал: ${plan.channelName} (id=${plan.channelId})")
        Text("Источник: ${plan.sourceType.toDownloadSourceTypeLabel()}")
        Text("Запрошено: ${plan.requestedDurationMinutes} мин | доступно: ${plan.maxDurationMinutes} мин")
        Text("Оценка буфера: ${plan.estimatedBytes.toStorageSizeLabel()}")
        Text("Доступно под запись: ${plan.availableBytes.toStorageSizeLabel()}")
        Text("Папка: ${plan.storageLocation.name} | ${plan.storagePath}")
        plan.reason?.let { Text("Причина: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun RecordingCard(
    context: Context,
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
        recordingPath.recordingExists(context)
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Запись #${recording.id} | ${recording.status.toRecordingStatusLabel()}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(recording.programTitle ?: recording.channelName)
            Text("Канал: ${recording.channelName} (id=${recording.channelId})")
            Text("Старт: ${recording.scheduledStartAt?.let(::formatEpoch) ?: "-"}")
            Text("Финиш: ${recording.scheduledEndAt?.let(::formatEpoch) ?: "-"}")
            Text("Фактически: ${recording.toActualRecordingWindowLabel()}")
            Text("Длительность: ${recording.toActualRecordingDurationLabel()}")
            if (recording.status == RecordingStatus.RECORDING || recording.progress > 0) {
                Text("Прогресс записи: ${recording.progress.coerceIn(0, 100)}%")
                LinearProgressIndicator(
                    progress = { recording.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text("Файл: ${recording.filePath.toRecordingPathLabel(context) ?: "ещё не создан"}")
            Text("Размер: ${recording.filePath.toRecordingFileSizeLabel(context)}")
            Row(
                modifier = Modifier.tvBringIntoViewOnFocus(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
    val uri = if (path.startsWith("content://", ignoreCase = true)) {
        Uri.parse(path)
    } else {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(context, "Файл записи не найден", Toast.LENGTH_SHORT).show()
            return
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeTypeForRecordingPath(context, path))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Открыть запись"))
    }.onFailure {
        Toast.makeText(context, "Не найдено приложение для открытия записи", Toast.LENGTH_SHORT).show()
    }
}

private fun mimeTypeForRecordingPath(context: Context, path: String): String {
    val extension = if (path.startsWith("content://", ignoreCase = true)) {
        DocumentFile.fromSingleUri(context, Uri.parse(path))
            ?.name
            ?.substringAfterLast('.', "")
            .orEmpty()
    } else {
        File(path).extension
    }
    return when (extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "ts", "m2ts" -> "video/mp2t"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "aac" -> "audio/aac"
        else -> "video/*"
    }
}

private fun String?.toRecordingFileSizeLabel(context: Context): String {
    val bytes = when {
        this.isNullOrBlank() -> return "-"
        startsWith("content://", ignoreCase = true) -> {
            DocumentFile.fromSingleUri(context, Uri.parse(this))
                ?.takeIf { it.exists() }
                ?.length()
                ?.takeIf { it >= 0L }
                ?: return "-"
        }

        else -> {
            val file = File(this)
            if (!file.exists() || !file.isFile) return "-"
            file.length()
        }
    }
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gib)
    val mib = bytes / (1024.0 * 1024.0)
    if (mib >= 1.0) return String.format(Locale.getDefault(), "%.1f MB", mib)
    val kib = bytes / 1024.0
    return String.format(Locale.getDefault(), "%.0f KB", kib)
}

private fun Long.toStorageSizeLabel(): String {
    if (this < 0L) return "неизвестно"
    val gib = this / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gib)
    val mib = this / (1024.0 * 1024.0)
    if (mib >= 1.0) return String.format(Locale.getDefault(), "%.1f MB", mib)
    val kib = this / 1024.0
    return String.format(Locale.getDefault(), "%.0f KB", kib)
}

private fun String?.toRecordingPathLabel(context: Context): String? {
    val value = this?.takeIf { it.isNotBlank() } ?: return null
    if (!value.startsWith("content://", ignoreCase = true)) return value
    val document = DocumentFile.fromSingleUri(context, Uri.parse(value))
    val name = document?.name?.takeIf { it.isNotBlank() } ?: "content:// запись"
    return "$name ($value)"
}

private fun String.recordingExists(context: Context): Boolean {
    return if (startsWith("content://", ignoreCase = true)) {
        DocumentFile.fromSingleUri(context, Uri.parse(this))?.exists() == true
    } else {
        File(this).exists()
    }
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

private fun List<RecordingTask>.toRecordingSummaryLabel(): String {
    if (isEmpty()) return "Нет активных или завершённых записей"
    val scheduled = count { it.status == RecordingStatus.SCHEDULED }
    val recording = count { it.status == RecordingStatus.RECORDING }
    val completed = count { it.status == RecordingStatus.COMPLETED }
    val failed = count { it.status == RecordingStatus.FAILED }
    val canceled = count { it.status == RecordingStatus.CANCELED }
    return "Очередь: $scheduled | пишется: $recording | готово: $completed | ошибки: $failed | отменено: $canceled"
}

private fun List<RecordingSchedule>.toScheduleSummaryLabel(): String {
    if (isEmpty()) return "Нет расписаний"
    val enabled = count { it.enabled }
    val disabled = size - enabled
    return "Включено: $enabled | выключено: $disabled"
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
            Text(
                "Расписание #${schedule.id} | ${schedule.repeatMode.toRecordingRepeatModeLabel()} | " +
                    if (schedule.enabled) "включено" else "выключено"
            )
            Text(schedule.programTitle ?: schedule.channelName)
            Text("Канал: ${schedule.channelName} (id=${schedule.channelId})")
            Text("${formatEpoch(schedule.startAt)} - ${formatEpoch(schedule.endAt)}")
            Text("Длительность: ${schedule.toScheduleDurationLabel()}")
            Text("Старт: ${schedule.toScheduleStartStatusLabel()}")
            Row(
                modifier = Modifier.tvBringIntoViewOnFocus(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

private fun RecordingSchedule.toScheduleDurationLabel(): String {
    return formatDuration((endAt - startAt).coerceAtLeast(0L))
}

private fun DownloadStatus.toDownloadStatusLabel(): String {
    return when (this) {
        DownloadStatus.QUEUED -> "в очереди"
        DownloadStatus.RUNNING -> "выполняется"
        DownloadStatus.PAUSED -> "пауза"
        DownloadStatus.COMPLETED -> "готово"
        DownloadStatus.FAILED -> "ошибка"
        DownloadStatus.CANCELED -> "отменено"
    }
}

private fun DownloadSourceType.toDownloadSourceTypeLabel(): String {
    return when (this) {
        DownloadSourceType.HTTP_STREAM -> "HTTP/HTTPS stream"
        DownloadSourceType.HLS_PLAYLIST -> "HLS playlist (.m3u8)"
        DownloadSourceType.TORRENT_FILE -> "Torrent file"
        DownloadSourceType.MAGNET -> "Magnet link"
        DownloadSourceType.ACESTREAM -> "Ace Stream"
        DownloadSourceType.LOCAL_FILE -> "Локальный файл"
        DownloadSourceType.CUSTOM -> "Пользовательский источник"
    }
}

private fun List<DownloadTask>.toDownloadSourceSummaryLabel(): String {
    if (isEmpty()) return "Нет задач"
    return groupBy { it.sourceType }
        .entries
        .sortedBy { it.key.name }
        .joinToString(" | ") { (type, tasks) ->
            "${type.toDownloadSourceTypeLabel()}: ${tasks.size}"
        }
}

private fun RecordingStatus.toRecordingStatusLabel(): String {
    return when (this) {
        RecordingStatus.SCHEDULED -> "запланирована"
        RecordingStatus.RECORDING -> "идёт запись"
        RecordingStatus.COMPLETED -> "готова"
        RecordingStatus.FAILED -> "ошибка"
        RecordingStatus.CANCELED -> "отменена"
    }
}

private fun RecordingRepeatMode.toRecordingRepeatModeLabel(): String {
    return when (this) {
        RecordingRepeatMode.ONCE -> "один раз"
        RecordingRepeatMode.DAILY -> "ежедневно"
        RecordingRepeatMode.WEEKLY -> "еженедельно"
        RecordingRepeatMode.SERIES -> "серия"
    }
}

private fun RecordingSchedule.toScheduleStartStatusLabel(): String {
    if (!enabled) return "выключено"
    val now = System.currentTimeMillis()
    return when {
        now < startAt -> "через ${formatDuration(startAt - now)}"
        now in startAt..endAt -> "идёт окно записи"
        else -> "время прошло"
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d ч %02d мин", hours, minutes)
    } else if (minutes > 0) {
        String.format(Locale.getDefault(), "%d мин", minutes)
    } else {
        String.format(Locale.getDefault(), "%d сек", seconds)
    }
}
