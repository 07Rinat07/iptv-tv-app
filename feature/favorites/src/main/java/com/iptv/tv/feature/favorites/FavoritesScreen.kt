package com.iptv.tv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.EpgProgram
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoritesScreen(
    onOpenPlayer: ((Long, Long) -> Unit)? = null,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var expandedProgramChannelIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    fun toggleProgram(channelId: Long) {
        expandedProgramChannelIds = if (channelId in expandedProgramChannelIds) {
            expandedProgramChannelIds - channelId
        } else {
            expandedProgramChannelIds + channelId
        }
    }
    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text("Избранных каналов: ${state.channels.size}", style = MaterialTheme.typography.bodyLarge)
            Text(state.epgStatus, style = MaterialTheme.typography.bodySmall)
        }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val channel = state.channels.firstOrNull { it.id == state.selectedChannelId }
                        val playlistId = channel?.playlistId
                        val channelId = channel?.id
                        if (playlistId != null && channelId != null) {
                            onOpenPlayer?.invoke(playlistId, channelId)
                        }
                    },
                    enabled = state.selectedChannelId != null
                ) {
                    Text("Воспроизвести")
                }
                OutlinedButton(
                    onClick = viewModel::removeSelectedFromFavorites,
                    enabled = state.selectedChannelId != null
                ) {
                    Text("Удалить")
                }
                OutlinedButton(
                    onClick = viewModel::exportFavoritesTxt,
                    enabled = state.channels.isNotEmpty() && !state.isExporting
                ) {
                    Text("Сохранить TXT")
                }
                OutlinedButton(
                    onClick = viewModel::exportFavoritesM3u8,
                    enabled = state.channels.isNotEmpty() && !state.isExporting
                ) {
                    Text("Сохранить M3U8")
                }
                OutlinedButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Скрыть детали" else "Детали")
                }
            }
        }

        state.lastError?.let { error ->
            item { Text(text = error, color = MaterialTheme.colorScheme.error) }
        }
        state.lastInfo?.let { info ->
            item { Text(text = info) }
        }
        state.exportedFilePath?.let { path ->
            item {
                Text(
                    text = "Файл: $path",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (state.channels.isEmpty()) {
            item { Text("Избранных каналов пока нет") }
        } else {
            items(state.channels, key = { it.id }) { channel ->
                val selected = channel.id == state.selectedChannelId
                val programs = state.epgProgramsByChannel[channel.id].orEmpty()
                val programExpanded = channel.id in expandedProgramChannelIds
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${channel.group ?: "Без группы"} | ${channel.health}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        EpgCompactLine(programs = state.epgProgramsByChannel[channel.id].orEmpty())
                        if (programExpanded) {
                            EpgProgramList(programs = programs)
                        }
                        if (showDetails) {
                            Text(
                                "URL: ${channel.streamUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { viewModel.selectChannel(channel.id) }) {
                                Text(if (selected) "Выбрано" else "Выбрать")
                            }
                            OutlinedButton(
                                onClick = { toggleProgram(channel.id) },
                                enabled = programs.isNotEmpty()
                            ) {
                                Text(if (programExpanded) "Скрыть программу" else "Программа")
                            }
                            if (selected) {
                                OutlinedButton(
                                    onClick = {
                                        val playlistId = channel.playlistId
                                        val channelId = channel.id
                                        onOpenPlayer?.invoke(playlistId, channelId)
                                    },
                                    enabled = onOpenPlayer != null
                                ) {
                                    Text("Играть")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpgProgramList(programs: List<EpgProgram>) {
    if (programs.isEmpty()) {
        Text("Программа не найдена", style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        programs.take(6).forEach { program ->
            Text(
                text = "${formatEpgTime(program.startEpochMs)}-${formatEpgTime(program.endEpochMs)}  ${program.title}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EpgCompactLine(programs: List<EpgProgram>) {
    if (programs.isEmpty()) return
    val now = programs.firstOrNull()
    val next = programs.drop(1).firstOrNull()
    val text = buildString {
        now?.let {
            append("Сейчас: ")
            append(formatEpgTime(it.startEpochMs))
            append(" ")
            append(it.title)
        }
        next?.let {
            if (isNotEmpty()) append(" | ")
            append("Далее: ")
            append(formatEpgTime(it.startEpochMs))
            append(" ")
            append(it.title)
        }
    }
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

private fun formatEpgTime(epochMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = FAVORITES_EPG_TIME_ZONE
    }.format(Date(epochMs))
}

private val FAVORITES_EPG_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Oral")
