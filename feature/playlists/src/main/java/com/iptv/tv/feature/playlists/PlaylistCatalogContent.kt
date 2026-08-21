package com.iptv.tv.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import kotlinx.coroutines.yield

const val TAG_PLAYLIST_CATALOG = "playlist_catalog"
const val TAG_PLAYLIST_CATALOG_BACK = "playlist_catalog_back"
private const val TAG_PLAYLIST_CATALOG_ENTRY_PREFIX = "playlist_catalog_entry_"
private const val CATALOG_STATIC_ITEM_COUNT = 2

fun playlistCatalogEntryTag(nodeId: CatalogNodeId): String =
    TAG_PLAYLIST_CATALOG_ENTRY_PREFIX + nodeId.value

/**
 * TV/D-pad presentation of one canonical hierarchy level.
 *
 * Focus is keyed by stable [CatalogNodeId], not by list position. Returning from Player therefore
 * scrolls to and requests focus for the same logical row even when the backing Room rows were
 * reloaded, reordered, or the row is outside the initial lazy-list viewport.
 */
@Composable
fun PlaylistCatalogContent(
    snapshot: PlaylistCatalogSnapshot,
    onBack: () -> Unit,
    onEntryFocused: (CatalogNodeId) -> Unit,
    onEnter: (CatalogNodeId) -> Unit,
    onOpenChannel: (Long) -> Unit
) {
    // Freeze the restore target for this hierarchy level. Normal D-pad movement updates the
    // checkpoint in ViewModel, but must not retrigger scroll/requestFocus on every row change.
    val restoreTargetId = remember(snapshot.currentNodeId) { snapshot.restoredFocusId }
    val restoredEntryIndex = snapshot.entries.indexOfFirst { entry -> entry.nodeId == restoreTargetId }
    val restoredFocusRequester = remember(snapshot.currentNodeId, restoreTargetId) { FocusRequester() }
    val listState = rememberLazyListState()

    LaunchedEffect(snapshot.currentNodeId, restoreTargetId, restoredEntryIndex) {
        if (restoreTargetId != null && restoredEntryIndex >= 0) {
            listState.scrollToItem(CATALOG_STATIC_ITEM_COUNT + restoredEntryIndex)
            // Give LazyColumn one composition turn after scrolling so the requester is attached.
            yield()
            runCatching { restoredFocusRequester.requestFocus() }
        }
    }

    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TAG_PLAYLIST_CATALOG),
        state = listState,
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
                val restoreModifier = if (entry.nodeId == restoreTargetId) {
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
