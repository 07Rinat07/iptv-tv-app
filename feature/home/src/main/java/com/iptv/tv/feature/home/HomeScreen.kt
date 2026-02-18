package com.iptv.tv.feature.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenScanner: (() -> Unit)? = null,
    onOpenImporter: (() -> Unit)? = null,
    onOpenPlaylists: (() -> Unit)? = null,
    onOpenPlayer: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Открыть",
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Сценарий работы", style = MaterialTheme.typography.titleMedium)
                    Text("1) Сканер: найти и сохранить топ-10 плейлистов")
                    Text("2) Мои плейлисты: выбрать нужный список")
                    Text("3) Редактор: чистка, сортировка, экспорт")
                    Text("4) Плеер: встроенный, VLC или через Engine Stream")
                }
            }
        }

        item {
            Text("Быстрые действия", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                onOpenScanner?.let { action ->
                    HomeActionCard(
                        title = "Сканер репозиториев",
                        subtitle = "GitHub/GitLab/Bitbucket, автосохранение 10",
                        primary = true,
                        onClick = action
                    )
                }
                onOpenImporter?.let { action ->
                    HomeActionCard(
                        title = "Ручной импорт",
                        subtitle = "URL, текст или локальный .m3u/.m3u8",
                        onClick = action
                    )
                }
                onOpenPlaylists?.let { action ->
                    HomeActionCard(
                        title = "Мои плейлисты",
                        subtitle = "Обновление, выбор, переход в редактор",
                        onClick = action
                    )
                }
                onOpenPlayer?.let { action ->
                    HomeActionCard(
                        title = "Плеер",
                        subtitle = "Встроенный/External VLC, буфер и fallback",
                        onClick = action
                    )
                }
                onOpenSettings?.let { action ->
                    HomeActionCard(
                        title = "Настройки",
                        subtitle = "Плеер, буфер, сеть, Tor/Engine endpoint",
                        onClick = action
                    )
                }
                onOpenDiagnostics?.let { action ->
                    HomeActionCard(
                        title = "Диагностика",
                        subtitle = "Логи синхронизации, ошибки сканера и плеера",
                        onClick = action
                    )
                }
            }
        }

        onPrimaryAction?.let { action ->
            item {
                Button(onClick = action) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    primary: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(0.495f)
            .tvFocusOutline()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            if (primary) {
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть")
                }
            } else {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Text("Открыть")
                }
            }
        }
    }
}
