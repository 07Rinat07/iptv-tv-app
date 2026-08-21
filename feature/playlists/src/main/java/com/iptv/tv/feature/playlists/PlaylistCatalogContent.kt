package com.iptv.tv.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.CatalogNodeId
import com.iptv.tv.core.model.CatalogNodeKind

const val TAG_PLAYLIST_CATALOG = "playlist_catalog"
const val TAG_PLAYLIST_CATALOG_BACK = "playlist_catalog_back"
private const val TAG_PLAYLIST_CATALOG_ENTRY_PREFIX = "playlist_catalog_entry_"

fun playlistCatalogEntryTag(nodeId: CatalogNodeId): String =
    TAG_PLAYLIST_CATALOG_ENTRY_PREFIX + nodeId.value

/**
 * TV/D-pad presentation of one canonical hierarchy level.
 *
 * Focus is keyed by stable [CatalogNodeId], not by list position. Returning from Player therefore
 * requests the same logical row even when the backing Room rows were reloaded or reordered.
 */
@Composable
fun PlaylistCatalogContent(
    snapshot: PlaylistCatalogSnapshot,
    onBack: () -> Unit,
    onEntryFocused: (CatalogNodeId) -> Unit,
    onEnter: (CatalogNodeId) -> Unit,
    onOpenChannel: (Long) -> Unit
) {
    val restoredFocusId = snapshot.restoredFocusId
    val restoredFocusRequester = remember(snapshot.currentNodeId, restoredFocusId) { FocusRequester() }

    LaunchedEffect(snapshot.currentNodeId, restoredFocusId) {
        if (restoredFocusId != null) {
            runCatching { restoredFocusRequester.requestFocus() }
        }
    }

    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TAG_PLAYLIST_CATALOG),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Каталог", style = MaterialTheme.typography.headlineMedium)
            Text(
                snapshot.breadcrumbs.joinToString("  ›  ") { breadcrumb -> breadcrumb.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(snapshot.currentTitle, style = MaterialTheme.typography.titleLarge)
        }

        item {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusOutline()
                    .testTag(TAG_PLAYLIST_CATALOG_BACK)
            ) {
                Text(if (snapshot.canGoBack) "Назад на один уровень" else "Закрыть каталог")
            }
        }

        if (snapshot.entries.isEmpty()) {
            item {
                Text("В этом разделе пока нет элементов")
            }
        } else {
            items(snapshot.entries, key = { entry -> entry.nodeId.value }) { entry ->
                val restoreModifier = if (entry.nodeId == restoredFocusId) {
                    Modifier.focusRequester(restoredFocusRequester)
                } else {
                    Modifier
                }
                OutlinedButton(
                    onClick = {
                        // Mouse/touch activation does not necessarily deliver a focus callback first.
                        // Persist the canonical row explicitly before either entering it or launching Player.
                        onEntryFocused(entry.nodeId)
                        if (entry.isChannel) {
                            entry.channelId?.let(onOpenChannel)
                        } else {
                            onEnter(entry.nodeId)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(restoreModifier)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) onEntryFocused(entry.nodeId)
                        }
                        .tvFocusOutline()
                        .testTag(playlistCatalogEntryTag(entry.nodeId))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            entry.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            entryKindLabel(entry.kind),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun entryKindLabel(kind: CatalogNodeKind): String = when (kind) {
    CatalogNodeKind.SOURCE -> "Источник"
    CatalogNodeKind.CATALOG -> "Каталог"
    CatalogNodeKind.SUBCATALOG -> "Подкаталог"
    CatalogNodeKind.PLAYLIST -> "Плейлист"
    CatalogNodeKind.GROUP -> "Группа"
    CatalogNodeKind.SUBGROUP -> "Подгруппа"
    CatalogNodeKind.CHANNEL -> "Канал — открыть в плеере"
    CatalogNodeKind.VIRTUAL_VIEW -> "Объединённый раздел"
}
