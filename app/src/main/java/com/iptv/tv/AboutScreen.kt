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
import com.iptv.tv.core.designsystem.theme.tvBringIntoViewOnFocus
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
                    Text("Справка: каталог и системные списки", style = MaterialTheme.typography.titleMedium)
                    Text("1. В «Мои плейлисты» выберите обычный список или системные «Избранное», «Все каналы», «Недавние» и нажмите «Открыть каталог».")
                    Text("2. Вверху каталога показан текущий путь: источник › плейлист › группа/подгруппа.")
                    Text("3. D-pad/Enter, мышь и тачпад открывают следующий уровень; канал открывается сразу в существующем плеере.")
                    Text("4. «Назад на один уровень», аппаратная Back и верхняя кнопка «Назад» возвращают ровно на один уровень иерархии.")
                    Text("5. После возврата из плеера приложение восстанавливает предыдущий канал/строку по стабильному идентификатору, если элемент ещё существует.")
                    Text("6. Избранный канал хранится независимо от исходного плейлиста: удаление списка не удаляет сохранённый favorite.")
                    Text("7. «Избранное» — виртуальный системный список: он обновляется автоматически, поэтому его нельзя удалить, вручную обновить или открыть в редакторе как обычный плейлист.")
                    Text("8. «Все каналы» объединяет исходные списки, а «Недавние» показывает последние доступные каналы без повторов; hidden/parental-blocked записи исключаются, очистка истории очищает «Недавние».")
                    Text("9. Если живой источник favorite исчез, приложение сохраняет playback snapshot/source variants для последующего воспроизведения или повторного связывания.")
                    Text("10. TXT/M3U8 export не записывает credential-bearing provider URL по умолчанию; M3U8 использует безопасный alternate variant, если он доступен.")
                    Text("11. «Сохранить RIPTV» создаёт полный versioned backup Favorites с logical identity/provenance/source variants; credential-bearing URLs в нём также редактируются.")
                    Text("12. «Импорт RIPTV» открывает системный выбор документа и сначала проверяет формат/версию backup; затем данные объединяются с текущим Избранным без полной замены библиотеки.")
                    Text("13. После импорта приложение показывает, сколько favorites/variants добавлено или объединено и сколько redacted/невосстановимых записей пропущено.")
                    Text("14. Для выбранного favorite нажмите «Источники», чтобы увидеть известные варианты с provenance, live/saved состоянием и health без показа stream URL или учетных данных.")
                    Text("15. Кнопка «Использовать» делает вариант предпочтительным; остальные source variants сохраняются и остаются доступными для последующего выбора.")
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
                modifier = Modifier
                    .fillMaxWidth()
                    .tvBringIntoViewOnFocus(),
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
