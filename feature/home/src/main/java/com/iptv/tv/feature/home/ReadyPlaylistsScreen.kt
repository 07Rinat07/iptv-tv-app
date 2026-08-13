package com.iptv.tv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

data class ReadyPlaylistPreset(
    val name: String,
    val url: String,
    val note: String = "Публичный тестовый источник",
    val sourceKey: String = url,
    val embeddedM3u: String? = null
)

val READY_PLAYLIST_PRESETS: List<ReadyPlaylistPreset> = listOf(
    ReadyPlaylistPreset(
        name = "Ace Stream TV — Торрент ТВ (279 каналов)",
        url = "https://iptv.org.ua/iptv/provayder.m3u",
        note = "Встроенный Torrent TV-плейлист: только Ace Stream-каналы",
        sourceKey = ACE_STREAM_TORRENT_SOURCE_KEY,
        embeddedM3u = ACE_STREAM_TORRENT_M3U
    ),
    ReadyPlaylistPreset(
        name = "Freetv.m3u",
        url = "https://raw.githubusercontent.com/iprtl/m3u/live/Freetv.m3u"
    ),
    ReadyPlaylistPreset(
        name = "Плейлист ТВ",
        url = "https://raw.githubusercontent.com/Dimonovich/TV/Dimonovich/FREE/TV"
    ),
    ReadyPlaylistPreset(
        name = "Сборник ТВ",
        url = "https://raw.githubusercontent.com/Voxlist/voxlist/refs/heads/main/voxlist.m3u"
    ),
    ReadyPlaylistPreset(
        name = "smolnp.github.io ТВ",
        url = "https://smolnp.github.io/IPTVru//IPTVstable.m3u8"
    ),
    ReadyPlaylistPreset(
        name = "Страны мира (EU/TR/US/RU/KZ/BY/TH)",
        url = "https://iptv-org.github.io/iptv/index.country.m3u"
    ),
    ReadyPlaylistPreset(
        name = "TV ALL list ru",
        url = "https://raw.githubusercontent.com/naggdd/iptv/main/ru.m3u"
    )
)

@Composable
fun ReadyPlaylistsScreen(
    onOpenPlaylist: (playlistId: Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.pendingOpenPlaylistId) {
        val playlistId = state.pendingOpenPlaylistId ?: return@LaunchedEffect
        onOpenPlaylist(playlistId)
        viewModel.consumeOpenPlaylistRequest()
    }

    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Готовые плейлисты", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Раздел для теста и быстрого старта. Выберите плейлист и нажмите импорт.",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                "Найдено пресетов: ${READY_PLAYLIST_PRESETS.size}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (state.isImporting) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        state.lastError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        state.lastInfo?.let { info ->
            item { Text(info, color = MaterialTheme.colorScheme.primary) }
        }

        items(READY_PLAYLIST_PRESETS, key = { it.url }) { preset ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusOutline()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Text(preset.note, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = preset.url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Button(
                        onClick = { viewModel.watchReadyPlaylist(preset) },
                        enabled = !state.isImporting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (state.importingUrl == preset.url) {
                                "Импортируется…"
                            } else {
                                "Импортировать и смотреть"
                            }
                        )
                    }
                }
            }
        }
    }
}
