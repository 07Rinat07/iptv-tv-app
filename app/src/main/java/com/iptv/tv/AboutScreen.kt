package com.iptv.tv

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

private const val AUTHOR_NAME = "Rinat Sarmuldin"
private const val AUTHOR_EMAIL = "ura07srr@gmail.com"

@Composable
@kotlin.OptIn(ExperimentalLayoutApi::class)
fun AboutScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "не определена" }
    }

    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("О приложении", style = MaterialTheme.typography.headlineSmall)
                    Text("Rinat IPTV", style = MaterialTheme.typography.titleMedium)
                    Text("IPTV-плеер и менеджер плейлистов для Android TV и TV Box.")
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Автор", style = MaterialTheme.typography.titleMedium)
                    Text(AUTHOR_NAME)
                    Text(AUTHOR_EMAIL)
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Система", style = MaterialTheme.typography.titleMedium)
                    Text("Версия приложения: $versionName")
                    Text("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    Text("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
                    Text("Процессоров: ${Runtime.getRuntime().availableProcessors()}")
                    Text("Heap: ${Runtime.getRuntime().maxMemory() / (1024L * 1024L)} МБ")
                }
            }
        }
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(onClick = onOpenDiagnostics) {
                    Text("Диагностика")
                }
                OutlinedButton(onClick = onOpenSettings) {
                    Text("Настройки")
                }
            }
        }
    }
}
