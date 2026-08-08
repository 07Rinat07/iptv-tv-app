package com.iptv.tv.feature.importer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvBringIntoViewOnFocus
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistProvider
import com.iptv.tv.core.model.ProviderAccountStatus
import com.iptv.tv.core.model.ProviderAuthType
import com.iptv.tv.core.model.ProviderDiagnosticKind
import com.iptv.tv.core.model.ProviderType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val TAG_IMPORTER_PLAYLIST_NAME = "importer_playlist_name"
const val TAG_IMPORTER_URL = "importer_url"
const val TAG_IMPORTER_IMPORT_URL = "importer_import_url"
const val TAG_IMPORTER_SAVE_M3U = "importer_save_m3u"
const val TAG_IMPORTER_XTREAM_URL = "importer_xtream_url"
const val TAG_IMPORTER_XTREAM_USERNAME = "importer_xtream_username"
const val TAG_IMPORTER_XTREAM_PASSWORD = "importer_xtream_password"
const val TAG_IMPORTER_IMPORT_XTREAM = "importer_import_xtream"
const val TAG_IMPORTER_SAVE_XTREAM = "importer_save_xtream"
const val TAG_IMPORTER_STALKER_URL = "importer_stalker_url"
const val TAG_IMPORTER_STALKER_MAC = "importer_stalker_mac"
const val TAG_IMPORTER_IMPORT_STALKER = "importer_import_stalker"
const val TAG_IMPORTER_SAVE_STALKER = "importer_save_stalker"
const val TAG_IMPORTER_HDHOMERUN_URL = "importer_hdhomerun_url"
const val TAG_IMPORTER_IMPORT_HDHOMERUN = "importer_import_hdhomerun"
const val TAG_IMPORTER_SAVE_HDHOMERUN = "importer_save_hdhomerun"
const val TAG_IMPORTER_TVHEADEND_URL = "importer_tvheadend_url"
const val TAG_IMPORTER_TVHEADEND_USERNAME = "importer_tvheadend_username"
const val TAG_IMPORTER_TVHEADEND_PASSWORD = "importer_tvheadend_password"
const val TAG_IMPORTER_IMPORT_TVHEADEND = "importer_import_tvheadend"
const val TAG_IMPORTER_SAVE_TVHEADEND = "importer_save_tvheadend"
const val TAG_IMPORTER_JELLYFIN_URL = "importer_jellyfin_url"
const val TAG_IMPORTER_JELLYFIN_API_KEY = "importer_jellyfin_api_key"
const val TAG_IMPORTER_IMPORT_JELLYFIN = "importer_import_jellyfin"
const val TAG_IMPORTER_SAVE_JELLYFIN = "importer_save_jellyfin"
const val TAG_IMPORTER_PLEX_URL = "importer_plex_url"
const val TAG_IMPORTER_PLEX_TOKEN = "importer_plex_token"
const val TAG_IMPORTER_IMPORT_PLEX = "importer_import_plex"
const val TAG_IMPORTER_SAVE_PLEX = "importer_save_plex"
const val TAG_IMPORTER_FILE_PATH = "importer_file_path"
const val TAG_IMPORTER_IMPORT_FILE = "importer_import_file"
const val TAG_IMPORTER_RAW_TEXT = "importer_raw_text"
const val TAG_IMPORTER_IMPORT_TEXT = "importer_import_text"
const val TAG_IMPORTER_VALIDATE = "importer_validate"
const val TAG_IMPORTER_PRIMARY = "importer_primary"
const val TAG_IMPORTER_LIST = "importer_list"
const val TAG_IMPORTER_REPORT = "importer_report"

private const val PROVIDER_FILTER_ALL = "ALL"

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun ImporterScreen(
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "К плейлистам",
    viewModel: ImporterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.updateFilePath(it.toString())
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.importFromFile()
        } else {
            viewModel.onStoragePermissionDenied()
        }
    }

    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TAG_IMPORTER_LIST),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
        }

        item {
            OutlinedTextField(
                value = state.playlistName,
                onValueChange = viewModel::updatePlaylistName,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_IMPORTER_PLAYLIST_NAME),
                label = { Text("Имя плейлиста") },
                singleLine = true
            )
        }

        item {
            Text(text = "Импорт по URL", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.url,
                onValueChange = viewModel::updateUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_IMPORTER_URL),
                label = { Text("https://...") },
                singleLine = true
            )
            Row(modifier = Modifier.padding(top = 8.dp).tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::importFromUrl,
                    modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_URL),
                    enabled = !state.isLoading
                ) {
                    Text(if (state.isLoading) "Импорт..." else "Импорт URL")
                }
                Button(
                    onClick = viewModel::saveM3uProvider,
                    modifier = Modifier.testTag(TAG_IMPORTER_SAVE_M3U),
                    enabled = !state.isLoading
                ) {
                    Text("Сохранить источник")
                }
            }
        }

        item {
            Text(text = "Xtream Codes", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.xtreamBaseUrl,
                    onValueChange = viewModel::updateXtreamBaseUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_XTREAM_URL),
                    label = { Text("https://server.example:8080") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.xtreamUsername,
                        onValueChange = viewModel::updateXtreamUsername,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TAG_IMPORTER_XTREAM_USERNAME),
                        label = { Text("Логин") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.xtreamPassword,
                        onValueChange = viewModel::updateXtreamPassword,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TAG_IMPORTER_XTREAM_PASSWORD),
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                Row(modifier = Modifier.tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::importFromXtream,
                        modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_XTREAM),
                        enabled = !state.isLoading
                    ) {
                        Text(if (state.isLoading) "Импорт..." else "Импорт Xtream")
                    }
                    Button(
                        onClick = viewModel::saveXtreamProvider,
                        modifier = Modifier.testTag(TAG_IMPORTER_SAVE_XTREAM),
                        enabled = !state.isLoading
                    ) {
                        Text("Сохранить аккаунт")
                    }
                }
            }
        }

        item {
            Text(text = "Stalker Portal", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.stalkerPortalUrl,
                    onValueChange = viewModel::updateStalkerPortalUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_STALKER_URL),
                    label = { Text("http://portal.example/stalker_portal") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.stalkerMacAddress,
                    onValueChange = viewModel::updateStalkerMacAddress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_STALKER_MAC),
                    label = { Text("00:1A:79:00:00:00") },
                    singleLine = true
                )
                Row(modifier = Modifier.tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::importFromStalker,
                        modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_STALKER),
                        enabled = !state.isLoading
                    ) {
                        Text(if (state.isLoading) "Импорт..." else "Импорт Stalker")
                    }
                    Button(
                        onClick = viewModel::saveStalkerProvider,
                        modifier = Modifier.testTag(TAG_IMPORTER_SAVE_STALKER),
                        enabled = !state.isLoading
                    ) {
                        Text("Сохранить аккаунт")
                    }
                }
            }
        }

        item {
            Text(text = "HDHomeRun", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.hdHomeRunBaseUrl,
                    onValueChange = viewModel::updateHdHomeRunBaseUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_HDHOMERUN_URL),
                    label = { Text("http://hdhomerun.local или http://device/lineup.json") },
                    singleLine = true
                )
                Row(modifier = Modifier.tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::importFromHdHomeRun,
                        modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_HDHOMERUN),
                        enabled = !state.isLoading
                    ) {
                        Text(if (state.isLoading) "Импорт..." else "Импорт HDHomeRun")
                    }
                    Button(
                        onClick = viewModel::saveHdHomeRunProvider,
                        modifier = Modifier.testTag(TAG_IMPORTER_SAVE_HDHOMERUN),
                        enabled = !state.isLoading
                    ) {
                        Text("Сохранить устройство")
                    }
                }
            }
        }

        item {
            Text(text = "Tvheadend", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.tvheadendBaseUrl,
                    onValueChange = viewModel::updateTvheadendBaseUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_TVHEADEND_URL),
                    label = { Text("http://server:9981 или прямой playlist/channels.m3u") },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.tvheadendUsername,
                        onValueChange = viewModel::updateTvheadendUsername,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TAG_IMPORTER_TVHEADEND_USERNAME),
                        label = { Text("Логин") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.tvheadendPassword,
                        onValueChange = viewModel::updateTvheadendPassword,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(TAG_IMPORTER_TVHEADEND_PASSWORD),
                        label = { Text("Пароль") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                Row(modifier = Modifier.tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::importFromTvheadend,
                        modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_TVHEADEND),
                        enabled = !state.isLoading
                    ) {
                        Text(if (state.isLoading) "Импорт..." else "Импорт Tvheadend")
                    }
                    Button(
                        onClick = viewModel::saveTvheadendProvider,
                        modifier = Modifier.testTag(TAG_IMPORTER_SAVE_TVHEADEND),
                        enabled = !state.isLoading
                    ) {
                        Text("Сохранить сервер")
                    }
                }
            }
        }

        item {
            Text(text = "Jellyfin", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.jellyfinBaseUrl,
                    onValueChange = viewModel::updateJellyfinBaseUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_JELLYFIN_URL),
                    label = { Text("http://server:8096") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.jellyfinApiKey,
                    onValueChange = viewModel::updateJellyfinApiKey,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_JELLYFIN_API_KEY),
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(modifier = Modifier.tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::importFromJellyfin,
                        modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_JELLYFIN),
                        enabled = !state.isLoading
                    ) {
                        Text(if (state.isLoading) "Импорт..." else "Импорт Jellyfin")
                    }
                    Button(
                        onClick = viewModel::saveJellyfinProvider,
                        modifier = Modifier.testTag(TAG_IMPORTER_SAVE_JELLYFIN),
                        enabled = !state.isLoading
                    ) {
                        Text("Сохранить сервер")
                    }
                }
            }
        }

        item {
            Text(text = "Plex", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.plexBaseUrl,
                    onValueChange = viewModel::updatePlexBaseUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_PLEX_URL),
                    label = { Text("http://server:32400") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.plexToken,
                    onValueChange = viewModel::updatePlexToken,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_PLEX_TOKEN),
                    label = { Text("X-Plex-Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Row(modifier = Modifier.tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::importFromPlex,
                        modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_PLEX),
                        enabled = !state.isLoading
                    ) {
                        Text(if (state.isLoading) "Импорт..." else "Импорт Plex")
                    }
                    Button(
                        onClick = viewModel::savePlexProvider,
                        modifier = Modifier.testTag(TAG_IMPORTER_SAVE_PLEX),
                        enabled = !state.isLoading
                    ) {
                        Text("Сохранить сервер")
                    }
                }
            }
        }

        item {
            SavedProvidersSection(
                providers = state.savedProviders,
                providerAutoSyncEnabled = state.providerAutoSyncEnabled,
                providerAutoSyncIntervalHours = state.providerAutoSyncIntervalHours,
                providerStatuses = state.providerStatuses,
                providerSyncHistory = state.providerSyncHistory,
                syncingProviderId = state.syncingProviderId,
                checkingProviderId = state.checkingProviderId,
                onCheck = viewModel::checkProvider,
                onCheckVisible = viewModel::checkProviders,
                onSync = viewModel::syncProvider,
                onSyncVisible = viewModel::syncProviders,
                onDelete = viewModel::deleteProvider
            )
        }

        item {
            Text(text = "Импорт локального файла", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.filePathOrUri,
                onValueChange = viewModel::updateFilePath,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_IMPORTER_FILE_PATH),
                label = { Text("C:/.../list.m3u или content://...") },
                singleLine = true
            )
            Row(modifier = Modifier.padding(top = 8.dp).tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        openDocumentLauncher.launch(
                            arrayOf(
                                "application/vnd.apple.mpegurl",
                                "application/x-mpegURL",
                                "audio/x-mpegurl",
                                "audio/mpegurl",
                                "text/plain",
                                "*/*"
                            )
                        )
                    },
                    enabled = !state.isLoading
                ) {
                    Text("Выбрать файл")
                }
                Button(
                    onClick = {
                        val needLegacyPermission = state.filePathOrUri.requiresLegacyReadPermission()
                        if (needLegacyPermission) {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                viewModel.importFromFile()
                            } else {
                                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                        } else {
                            viewModel.importFromFile()
                        }
                    },
                    modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_FILE),
                    enabled = !state.isLoading
                ) {
                    Text(if (state.isLoading) "Импорт..." else "Импорт файла")
                }
            }
        }

        item {
            Text(text = "Импорт текстом", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.rawText,
                onValueChange = viewModel::updateRawText,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_IMPORTER_RAW_TEXT),
                label = { Text("#EXTM3U ...") },
                minLines = 8
            )
            Row(modifier = Modifier.padding(top = 8.dp).tvBringIntoViewOnFocus(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::importFromText,
                    modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_TEXT),
                    enabled = !state.isLoading
                ) {
                    Text(if (state.isLoading) "Импорт..." else "Импорт текста")
                }
                Button(
                    onClick = viewModel::validateLastImportedPlaylist,
                    modifier = Modifier.testTag(TAG_IMPORTER_VALIDATE),
                    enabled = !state.isLoading
                ) {
                    Text("Проверить")
                }
                onPrimaryAction?.let { action ->
                    Button(
                        onClick = action,
                        modifier = Modifier.testTag(TAG_IMPORTER_PRIMARY)
                    ) {
                        Text(primaryLabel)
                    }
                }
            }
        }

        state.lastError?.let { error ->
            item {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.providerMessage?.let { message ->
            item {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        state.lastImportReport?.let { report ->
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(TAG_IMPORTER_REPORT)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Импорт завершён", style = MaterialTheme.typography.titleMedium)
                        Text("Playlist ID: ${report.playlistId}")
                        Text("Parsed: ${report.totalParsed}, Imported: ${report.totalImported}, Duplicates: ${report.removedDuplicates}")
                        Text("Auto-check: ${report.autoChecked} | up=${report.available}, unstable=${report.unstable}, down=${report.unavailable}")
                        if (report.warnings.isNotEmpty()) {
                            Text("Warnings:")
                        }
                    }
                }
            }
            state.lastContentSummary?.let { summary ->
                item {
                    ImportContentSummaryCard(summary)
                }
            }
            if (report.warnings.isNotEmpty()) {
                items(report.warnings.take(20)) { warning ->
                    Text(text = warning, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        state.lastValidationReport?.let { validation ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Проверка завершена", style = MaterialTheme.typography.titleMedium)
                        Text("Checked: ${validation.totalChecked}")
                        Text("Available: ${validation.available}")
                        Text("Unstable: ${validation.unstable}")
                        Text("Unavailable: ${validation.unavailable}")
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SavedProvidersSection(
    providers: List<PlaylistProvider>,
    providerAutoSyncEnabled: Boolean,
    providerAutoSyncIntervalHours: Int,
    providerStatuses: Map<Long, ProviderAccountStatus>,
    providerSyncHistory: Map<Long, List<ProviderSyncHistoryItem>>,
    syncingProviderId: Long?,
    checkingProviderId: Long?,
    onCheck: (Long) -> Unit,
    onCheckVisible: (List<Long>) -> Unit,
    onSync: (Long) -> Unit,
    onSyncVisible: (List<Long>) -> Unit,
    onDelete: (Long) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTypeFilter by rememberSaveable { mutableStateOf(PROVIDER_FILTER_ALL) }
    var issueOnly by rememberSaveable { mutableStateOf(false) }
    var expandedProviderIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }

    val providerCards = remember(providers, providerStatuses, providerSyncHistory) {
        providers.map { provider ->
            SavedProviderCardModel(
                provider = provider,
                status = providerStatuses[provider.id],
                history = providerSyncHistory[provider.id].orEmpty()
            )
        }
    }
    val availableTypes = remember(providers) { providers.map { it.type }.distinct() }
    val filterState = SavedProvidersFilterState(
        query = query,
        selectedType = selectedTypeFilter.takeUnless { it == PROVIDER_FILTER_ALL }?.let(ProviderType::valueOf),
        issueOnly = issueOnly
    )
    val filteredProviders = remember(providerCards, filterState) {
        filterSavedProviders(providerCards, filterState)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сохранённые провайдеры", style = MaterialTheme.typography.titleMedium)
        if (providers.isEmpty()) {
            Text("Пока нет сохранённых M3U/Xtream/Stalker/HDHomeRun/Tvheadend/Jellyfin/Plex источников", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "Авто-sync: ${if (providerAutoSyncEnabled) "включён" else "выключен"}" +
                    if (providerAutoSyncEnabled) " | каждые $providerAutoSyncIntervalHours ч" else "",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск провайдера") },
                singleLine = true
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val allSelected = selectedTypeFilter == PROVIDER_FILTER_ALL
                if (allSelected) {
                    Button(onClick = { selectedTypeFilter = PROVIDER_FILTER_ALL }) { Text("Все") }
                } else {
                    androidx.compose.material3.OutlinedButton(onClick = { selectedTypeFilter = PROVIDER_FILTER_ALL }) { Text("Все") }
                }
                availableTypes.forEach { type ->
                    val selected = selectedTypeFilter == type.name
                    if (selected) {
                        Button(onClick = { selectedTypeFilter = type.name }) { Text(type.displayName()) }
                    } else {
                        androidx.compose.material3.OutlinedButton(onClick = { selectedTypeFilter = type.name }) { Text(type.displayName()) }
                    }
                }
                if (issueOnly) {
                    Button(onClick = { issueOnly = false }) { Text("Только с проблемами") }
                } else {
                    androidx.compose.material3.OutlinedButton(onClick = { issueOnly = true }) { Text("Только с проблемами") }
                }
            }
            Text(
                "Показано: ${filteredProviders.size} из ${providers.size}",
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val visibleProviderIds = bulkProviderIds(filteredProviders)
                Button(
                    onClick = { onCheckVisible(visibleProviderIds) },
                    enabled = visibleProviderIds.isNotEmpty() && syncingProviderId == null && checkingProviderId == null
                ) {
                    Text("Проверить видимые")
                }
                Button(
                    onClick = { onSyncVisible(visibleProviderIds) },
                    enabled = visibleProviderIds.isNotEmpty() && syncingProviderId == null && checkingProviderId == null
                ) {
                    Text("Синхронизировать видимые")
                }
            }
            filteredProviders.forEach { card ->
                ProviderAccountCard(
                    provider = card.provider,
                    isSyncing = syncingProviderId == card.provider.id,
                    isChecking = checkingProviderId == card.provider.id,
                    busyBlocked = syncingProviderId != null || checkingProviderId != null,
                    status = card.status,
                    history = card.history,
                    expanded = card.provider.id in expandedProviderIds,
                    onToggleExpanded = {
                        expandedProviderIds = if (card.provider.id in expandedProviderIds) {
                            expandedProviderIds - card.provider.id
                        } else {
                            expandedProviderIds + card.provider.id
                        }
                    },
                    onCheck = { onCheck(card.provider.id) },
                    onSync = { onSync(card.provider.id) },
                    onDelete = { onDelete(card.provider.id) }
                )
            }
        }
    }
}

@Composable
private fun ProviderAccountCard(
    provider: PlaylistProvider,
    isSyncing: Boolean,
    isChecking: Boolean,
    busyBlocked: Boolean,
    status: ProviderAccountStatus?,
    history: List<ProviderSyncHistoryItem>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onCheck: () -> Unit,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(provider.name, style = MaterialTheme.typography.titleSmall)
            Text("${provider.type.displayName()} | ${provider.baseUrl}", style = MaterialTheme.typography.bodySmall)
            provider.username?.takeIf { it.isNotBlank() }?.let {
                Text("Логин: $it", style = MaterialTheme.typography.bodySmall)
            }
            provider.macAddress?.takeIf { it.isNotBlank() }?.let {
                Text("MAC: $it", style = MaterialTheme.typography.bodySmall)
            }
            Text("Авторизация: ${provider.authType.displayName()}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Плейлист: ${provider.linkedPlaylistId ?: "не привязан"} | sync: ${provider.lastSyncedAt.formatProviderTime()}",
                style = MaterialTheme.typography.bodySmall
            )
            androidx.compose.material3.OutlinedButton(onClick = onToggleExpanded) {
                Text(if (expanded) "Свернуть детали" else "Показать детали")
            }
            status?.let {
                Text(
                    "Статус: ${if (it.ok) "OK" else "Ошибка"} | ${it.statusText} | ${it.checkedAt.formatProviderTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    "Диагностика: ${it.diagnosticKind.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                if (expanded) {
                    it.detail?.takeIf { detail -> detail.isNotBlank() }?.let { detail ->
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                    it.hint?.takeIf { hint -> hint.isNotBlank() }?.let { hint ->
                        Text("Подсказка: $hint", style = MaterialTheme.typography.bodySmall)
                    }
                    it.testedUrl?.takeIf { url -> url.isNotBlank() }?.let { url ->
                        Text("Endpoint: $url", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (history.isNotEmpty()) {
                Text("История sync", style = MaterialTheme.typography.titleSmall)
                history.take(if (expanded) history.size else 1).forEach { item ->
                    Text(
                        "${item.createdAt.formatProviderTime()} | ${item.summary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    if (expanded) {
                        item.toProviderSyncDetailLine()?.let { detailLine ->
                            Text(
                                detailLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCheck, enabled = !busyBlocked || isChecking) {
                    Text(if (isChecking) "Проверка..." else "Проверить")
                }
                Button(onClick = onSync, enabled = !busyBlocked || isSyncing) {
                    Text(if (isSyncing) "Синхронизация..." else "Синхронизировать")
                }
                Button(onClick = onDelete, enabled = !busyBlocked) {
                    Text("Удалить")
                }
            }
        }
    }
}

private fun ProviderType.displayName(): String {
    return when (this) {
        ProviderType.XTREAM -> "Xtream Codes"
        ProviderType.STALKER -> "Stalker Portal"
        ProviderType.M3U -> "M3U URL"
        ProviderType.JELLYFIN -> "Jellyfin"
        ProviderType.PLEX -> "Plex"
        ProviderType.TVHEADEND -> "Tvheadend"
        ProviderType.HDHOMERUN -> "HdHomeRun"
    }
}

private fun ProviderDiagnosticKind.displayName(): String {
    return when (this) {
        ProviderDiagnosticKind.OK -> "ok"
        ProviderDiagnosticKind.AUTH -> "auth"
        ProviderDiagnosticKind.NETWORK -> "network"
        ProviderDiagnosticKind.PARSER -> "parser"
        ProviderDiagnosticKind.EMPTY_PLAYLIST -> "empty playlist"
        ProviderDiagnosticKind.UNSUPPORTED -> "unsupported"
        ProviderDiagnosticKind.PROVIDER_ERROR -> "provider error"
    }
}

private fun ProviderAuthType.displayName(): String {
    return when (this) {
        ProviderAuthType.NONE -> "без авторизации"
        ProviderAuthType.USER_PASSWORD -> "логин / пароль"
        ProviderAuthType.TOKEN -> "token / API key"
        ProviderAuthType.MAC_ADDRESS -> "MAC адрес"
    }
}

internal data class SavedProvidersFilterState(
    val query: String = "",
    val selectedType: ProviderType? = null,
    val issueOnly: Boolean = false
)

internal data class SavedProviderCardModel(
    val provider: PlaylistProvider,
    val status: ProviderAccountStatus?,
    val history: List<ProviderSyncHistoryItem>
)

internal fun filterSavedProviders(
    providers: List<SavedProviderCardModel>,
    filterState: SavedProvidersFilterState
): List<SavedProviderCardModel> {
    val query = filterState.query.trim().lowercase()
    return providers
        .asSequence()
        .filter { item ->
            filterState.selectedType == null || item.provider.type == filterState.selectedType
        }
        .filter { item ->
            if (!filterState.issueOnly) {
                true
            } else {
                item.status?.ok == false || item.history.firstOrNull()?.isError == true
            }
        }
        .filter { item ->
            if (query.isBlank()) {
                true
            } else {
                listOfNotNull(
                    item.provider.name,
                    item.provider.baseUrl,
                    item.provider.username,
                    item.provider.macAddress,
                    item.provider.type.displayName()
                ).any { value -> value.lowercase().contains(query) }
            }
        }
        .sortedWith(compareByDescending<SavedProviderCardModel> { it.provider.lastSyncedAt ?: 0L }.thenByDescending { it.provider.createdAt })
        .toList()
}

internal fun bulkProviderIds(providers: List<SavedProviderCardModel>): List<Long> {
    return providers.map { it.provider.id }.distinct()
}

private fun ProviderSyncHistoryItem.toProviderSyncDetailLine(): String? {
    val parts = buildList {
        providerType?.let { add("type=$it") }
        playlistId?.let { add("playlistId=$it") }
        reason?.let { add("reason=$it") }
        detail?.takeIf { it.isNotBlank() }?.let { add("detail=${it.take(120)}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
}

private fun Long?.formatProviderTime(): String {
    val value = this ?: return "никогда"
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(value))
}

private fun Long.formatProviderTime(): String {
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(this))
}

@Composable
private fun ImportContentSummaryCard(summary: PlaylistContentSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Что сохранено", style = MaterialTheme.typography.titleMedium)
            Text("Источник: ${summary.sourceType} | ${summary.source}")
            Text("EPG: ${summary.epgSourceUrl?.takeIf { it.isNotBlank() } ?: "не задан"}")
            Text(
                "Каналы: всего=${summary.totalChannels}, видимых=${summary.visibleChannels}, " +
                    "скрытых=${summary.hiddenChannels}"
            )
            Text(
                "Лого: ${summary.channelsWithLogo}/${summary.totalChannels} | " +
                    "tvg-id: ${summary.channelsWithTvgId}/${summary.totalChannels} | групп=${summary.groupCount}"
            )
            Text(
                "Проверка: ok=${summary.availableChannels}, unstable=${summary.unstableChannels}, " +
                    "down=${summary.unavailableChannels}, unknown=${summary.unknownHealthChannels}"
            )
            if (summary.topGroups.isNotEmpty()) {
                Text("Группы: ${summary.topGroups.joinToString { "${it.first} (${it.second})" }}")
            }
            Text("Примеры каналов", style = MaterialTheme.typography.titleSmall)
            summary.channelPreviews.take(10).forEach { preview ->
                ChannelPreviewRow(preview)
            }
        }
    }
}

@Composable
private fun ChannelPreviewRow(preview: ChannelPreview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (!preview.logo.isNullOrBlank()) {
                AsyncImage(
                    model = preview.logo,
                    contentDescription = preview.name,
                    modifier = Modifier.size(38.dp)
                )
            } else {
                Text("—", style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(preview.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${preview.group ?: "Без группы"} | health=${preview.health} | hidden=${if (preview.isHidden) "да" else "нет"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun String.requiresLegacyReadPermission(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
    val value = trim().lowercase()
    if (value.isBlank()) return false
    if (value.startsWith("content://")) return false
    return value.startsWith("/") || value.startsWith("file://")
}
