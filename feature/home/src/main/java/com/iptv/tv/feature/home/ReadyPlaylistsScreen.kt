package com.iptv.tv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

data class ReadyPlaylistPreset(
    val name: String,
    val url: String,
    val note: String = "Публичный тестовый источник"
)

val READY_PLAYLIST_PRESETS: List<ReadyPlaylistPreset> = listOf(
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
    onImportPlaylist: (url: String, name: String) -> Unit
) {
    LazyColumn(
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
                        onClick = { onImportPlaylist(preset.url, preset.name) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Импортировать")
                    }
                }
            }
        }
    }
}

