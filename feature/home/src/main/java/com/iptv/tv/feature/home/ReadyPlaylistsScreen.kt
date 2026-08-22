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
    val note: String = "Публичный IPTV-источник"
)

/**
 * Small user-curated ready catalog.
 *
 * Keep these entries as live URLs rather than APK snapshots so importing a ready playlist always
 * reflects the publisher's current M3U. Existing user-imported playlists are intentionally left
 * untouched when this catalog changes.
 */
val READY_PLAYLIST_PRESETS: List<ReadyPlaylistPreset> = listOf(
    ReadyPlaylistPreset(
        name = "Dimonovich FREE TV",
        url = "https://raw.githubusercontent.com/Dimonovich/TV/Dimonovich/FREE/TV",
        note = "Самообновляемый публичный M3U; EPG-адреса заданы в заголовке списка"
    ),
    ReadyPlaylistPreset(
        name = ".NET_2 TV",
        url = "https://dl.dropboxusercontent.com/scl/fi/ur595ef4cqmfst951kboh/.NET_2.m3u?rlkey=0cw1ficfrq0m6yg2udh16qn78&dl=0",
        note = "Крупный публичный M3U через прямую Dropbox-ссылку"
    ),
    ReadyPlaylistPreset(
        name = "IPTV.org.ua — Провайдеры",
        url = "https://iptv.org.ua/iptv/provayder.m3u",
        note = "Самообновляемый список каналов от разных провайдеров"
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
