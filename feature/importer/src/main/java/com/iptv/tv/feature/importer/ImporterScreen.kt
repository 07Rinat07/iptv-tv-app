package com.iptv.tv.feature.importer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel

const val TAG_IMPORTER_PLAYLIST_NAME = "importer_playlist_name"
const val TAG_IMPORTER_URL = "importer_url"
const val TAG_IMPORTER_IMPORT_URL = "importer_import_url"
const val TAG_IMPORTER_FILE_PATH = "importer_file_path"
const val TAG_IMPORTER_IMPORT_FILE = "importer_import_file"
const val TAG_IMPORTER_RAW_TEXT = "importer_raw_text"
const val TAG_IMPORTER_IMPORT_TEXT = "importer_import_text"
const val TAG_IMPORTER_VALIDATE = "importer_validate"
const val TAG_IMPORTER_PRIMARY = "importer_primary"
const val TAG_IMPORTER_LIST = "importer_list"
const val TAG_IMPORTER_REPORT = "importer_report"

@Composable
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TAG_IMPORTER_LIST)
            .focusable(),
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
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::importFromUrl,
                    modifier = Modifier.testTag(TAG_IMPORTER_IMPORT_URL),
                    enabled = !state.isLoading
                ) {
                    Text(if (state.isLoading) "Импорт..." else "Импорт URL")
                }
            }
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
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

private fun String.requiresLegacyReadPermission(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
    val value = trim().lowercase()
    if (value.isBlank()) return false
    if (value.startsWith("content://")) return false
    return value.startsWith("/") || value.startsWith("file://")
}
