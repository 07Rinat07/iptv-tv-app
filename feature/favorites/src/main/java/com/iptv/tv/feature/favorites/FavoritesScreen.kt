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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvBringIntoViewOnFocus
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.FAVORITE_PLAYBACK_PLAYLIST_ID
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
    var query by rememberSaveable { mutableStateOf("") }
    var sortModeName by rememberSaveable { mutableStateOf(FavoritesSortMode.CURRENT_FIRST.name) }
    var expandedProgramChannelIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    val sortMode = FavoritesSortMode.entries.firstOrNull { it.name == sortModeName }
        ?: FavoritesSortMode.CURRENT_FIRST
    val locale = Locale.getDefault()
    val visibleChannels = remember(
        state.channels,
        state.selectedChannelId,
        query,
        sortMode,
        locale
    ) {
        filterAndSortFavorites(
            channels = state.channels,
            selectedChannelId = state.selectedChannelId,
            query = query,
            sortMode = sortMode,
            locale = locale
        )
    }

    LaunchedEffect(visibleChannels, state.selectedChannelId) {
        val selectedIsVisible = visibleChannels.any { it.id == state.selectedChannelId }
        if (!selectedIsVisible) {
            visibleChannels.firstOrNull()?.let { viewModel.selectChannel(it.id) }
        }
    }

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
                modifier = Modifier
                    .fillMaxWidth()
                    .tvBringIntoViewOnFocus(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val channelId = state.channels
                            .firstOrNull { it.id == state.selectedChannelId }
                            ?.id
                        if (channelId != null) {
                            onOpenPlayer?.invoke(FAVORITE_PLAYBACK_PLAYLIST_ID, channelId)
                        }
                    },
                    enabled = state.selectedChannelId != null && onOpenPlayer != null
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

        if (state.channels.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Поиск в избранном") },
                            supportingText = {
                                Text("Название, группа или tvg-id · найдено ${visibleChannels.size}")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FavoritesSortMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = mode == sortMode,
                                    onClick = { sortModeName = mode.name },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                    }
                }
            }
        }

        when {
            state.channels.isEmpty() -> {
                item { Text("Избранных каналов пока нет") }
            }

            visibleChannels.isEmpty() -> {
                item { Text("По заданному запросу избранные каналы не найдены") }
            }

            else -> {
                items(visibleChannels, key = { it.id }) { channel ->
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
                            EpgCompactLine(programs = programs)
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
                                            onOpenPlayer?.invoke(FAVORITE_PLAYBACK_PLAYLIST_ID, channel.id)
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
}

private enum class FavoritesSortMode(val label: String) {
    CURRENT_FIRST("Выбранный сначала"),
    NAME("По имени"),
    GROUP("По группе"),
    HEALTH("По доступности")
}

private fun filterAndSortFavorites(
    channels: List<Channel>,
    selectedChannelId: Long?,
    query: String,
    sortMode: FavoritesSortMode,
    locale: Locale
): List<Channel> {
    val normalizedQuery = query.trim().lowercase(locale)
    val filtered = if (normalizedQuery.isBlank()) {
        channels
    } else {
        channels.filter { channel ->
            channel.name.lowercase(locale).contains(normalizedQuery) ||
                channel.group.orEmpty().lowercase(locale).contains(normalizedQuery) ||
                channel.tvgId.orEmpty().lowercase(locale).contains(normalizedQuery)
        }
    }
    val nameComparator = compareBy<Channel> { it.name.lowercase(locale) }
    return when (sortMode) {
        FavoritesSortMode.CURRENT_FIRST -> filtered.sortedWith(
            compareBy<Channel> { if (it.id == selectedChannelId) 0 else 1 }
                .then(nameComparator)
        )

        FavoritesSortMode.NAME -> filtered.sortedWith(nameComparator)
        FavoritesSortMode.GROUP -> filtered.sortedWith(
            compareBy<Channel> { it.group.orEmpty().lowercase(locale) }
                .then(nameComparator)
        )

        FavoritesSortMode.HEALTH -> filtered.sortedWith(
            compareBy<Channel> { healthRank(it.health) }
                .then(nameComparator)
        )
    }
}

private fun healthRank(health: ChannelHealth): Int = when (health) {
    ChannelHealth.AVAILABLE -> 0
    ChannelHealth.UNSTABLE -> 1
    ChannelHealth.UNKNOWN -> 2
    ChannelHealth.UNAVAILABLE -> 3
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

private val FAVORITES_EPG_TIME_ZONE: TimeZone = TimeZone.getDefault()
