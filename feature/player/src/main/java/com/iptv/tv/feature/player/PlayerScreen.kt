package com.iptv.tv.feature.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.iptv.tv.core.utils.FileLogger
import android.content.Context
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.player.toLoadControl
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val QUICK_SCROLL_STEP = 8
private const val QUICK_PAGE_STEP = 24
private const val IPTV_USER_AGENT = "myscanerIPTV/0.1 (Android TV; Media3)"

private data class PlayerTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,
    val selected: Boolean,
    val supported: Boolean
)

@Composable
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
fun PlayerScreen(
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Настройки",
    onFullscreenChanged: (Boolean) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    var hideUnavailable by rememberSaveable { mutableStateOf(true) }
    val filteredChannels = remember(
        state.channels,
        state.channelQuery,
        state.selectedGroup,
        state.selectedSubGroup,
        hideUnavailable,
        state.parentalControlEnabled,
        state.parentalHideAdultChannels,
        state.parentalBlockedKeywords
    ) {
        val query = state.channelQuery.trim().lowercase()
        val grouped = state.channels.filter { channel ->
            channelMatchesGroup(
                channel = channel,
                selectedGroup = state.selectedGroup,
                selectedSubGroup = state.selectedSubGroup
            )
        }
        val searched = if (query.isBlank()) {
            grouped
        } else {
            grouped.filter { channel ->
                channel.name.lowercase().contains(query) ||
                    channel.group?.lowercase()?.contains(query) == true ||
                    channel.streamUrl.lowercase().contains(query)
            }
        }
        val byHealth = if (hideUnavailable) {
            searched.filter { it.health != ChannelHealth.UNAVAILABLE }
        } else {
            searched
        }
        val byParental = if (state.parentalControlEnabled && state.parentalHideAdultChannels) {
            byHealth.filterNot { channel ->
                channel.matchesParentalKeywords(state.parentalBlockedKeywords)
            }
        } else {
            byHealth
        }
        byParental.sortedWith(
            compareBy<Channel> { healthPriority(it.health) }
                .thenBy { it.name.lowercase() }
        )
    }
    val parentalHiddenCount = remember(
        state.channels,
        state.parentalControlEnabled,
        state.parentalHideAdultChannels,
        state.parentalBlockedKeywords
    ) {
        if (state.parentalControlEnabled && state.parentalHideAdultChannels) {
            state.channels.count { it.matchesParentalKeywords(state.parentalBlockedKeywords) }
        } else {
            0
        }
    }
    val healthStats = remember(state.channels) {
        val available = state.channels.count { it.health == ChannelHealth.AVAILABLE }
        val unstable = state.channels.count { it.health == ChannelHealth.UNSTABLE }
        val unknown = state.channels.count { it.health == ChannelHealth.UNKNOWN }
        val unavailable = state.channels.count { it.health == ChannelHealth.UNAVAILABLE }
        Triple(available + unstable, unknown, unavailable)
    }
    var showTechnicalInfo by rememberSaveable { mutableStateOf(false) }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var showQuickChannels by rememberSaveable { mutableStateOf(true) }
    var showChannelCatalog by rememberSaveable { mutableStateOf(false) }
    var showActions by rememberSaveable { mutableStateOf(false) }
    var showStreamTools by rememberSaveable { mutableStateOf(false) }
    var showEpgWizard by rememberSaveable { mutableStateOf(false) }
    var showViewOptions by rememberSaveable { mutableStateOf(false) }
    var expandedEpgChannelIds by rememberSaveable { mutableStateOf(emptySet<Long>()) }
    var selectedMultiviewTargetPane by rememberSaveable { mutableIntStateOf(2) }
    val selectedChannelName = state.channels.firstOrNull { it.id == state.selectedChannelId }?.name
    val multiviewLabel = when (state.multiviewMode) {
        MultiviewMode.OFF -> "выкл"
        MultiviewMode.TWO_UP -> "2-up"
        MultiviewMode.FOUR_UP -> "4-up"
    }
    val configuredPaneIndices = when (state.multiviewMode) {
        MultiviewMode.OFF -> emptyList()
        MultiviewMode.TWO_UP -> listOf(2)
        MultiviewMode.FOUR_UP -> listOf(2, 3, 4)
    }
    val availablePaneTargets = if (state.multiviewSupportedPaneCount >= 4) {
        listOf(2, 3, 4)
    } else {
        listOf(2)
    }
    val multiviewSessions = listOf(
        state.secondaryInternalSession,
        state.tertiaryInternalSession,
        state.quaternaryInternalSession
    )
    val multiviewTargetChannelId = when (selectedMultiviewTargetPane) {
        2 -> state.secondaryInternalSession?.channelId
        3 -> state.tertiaryInternalSession?.channelId
        4 -> state.quaternaryInternalSession?.channelId
        else -> null
    }
    fun toggleChannelProgram(channelId: Long) {
        expandedEpgChannelIds = if (channelId in expandedEpgChannelIds) {
            expandedEpgChannelIds - channelId
        } else {
            expandedEpgChannelIds + channelId
        }
    }

    LaunchedEffect(configuredPaneIndices, availablePaneTargets) {
        val validTargets = configuredPaneIndices.ifEmpty { availablePaneTargets }
        if (validTargets.isNotEmpty() && selectedMultiviewTargetPane !in validTargets) {
            selectedMultiviewTargetPane = validTargets.first()
        }
    }

    LaunchedEffect(state.internalPlayerExpanded) {
        onFullscreenChanged(state.internalPlayerExpanded)
    }

    DisposableEffect(Unit) {
        onDispose { onFullscreenChanged(false) }
    }

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        if (state.internalPlayerExpanded) {
            FullscreenInternalPlayerOverlay(
                session = state.internalSession,
                selectedChannelName = selectedChannelName,
                scale = state.playerVideoScale,
                onReady = { sessionId -> viewModel.onInternalPlaybackReady(sessionId) },
                onError = { message ->
                    viewModel.onInternalPlaybackError(
                        message = message,
                        context = context,
                        sessionId = state.internalSession?.sessionId
                    )
                },
                onClose = { viewModel.setInternalPlayerExpanded(false) }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "Плеер", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = selectedChannelName?.let { "Выбран канал: $it" }
                                ?: "Выберите канал из списка",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { viewModel.playSelected(context) }, enabled = state.selectedChannelId != null && !state.isStartingPlayback) {
                                Text(if (state.isStartingPlayback) "Запуск..." else "Смотреть")
                            }
                            OutlinedButton(onClick = viewModel::stopInternalPlayback) {
                                Text("Стоп")
                            }
                            OutlinedButton(onClick = { showViewOptions = !showViewOptions }) {
                                Text(if (showViewOptions) "Скрыть настройки вида" else "Вид")
                            }
                        }
                        if (showViewOptions) {
                            PlayerViewOptions(
                                showQuickChannels = showQuickChannels,
                                showChannelCatalog = showChannelCatalog,
                                showPlaylists = showPlaylists,
                                showActions = showActions,
                                showStreamTools = showStreamTools,
                                showEpgWizard = showEpgWizard,
                                showTechnicalInfo = showTechnicalInfo,
                                hideUnavailable = hideUnavailable,
                                onToggleQuickChannels = { showQuickChannels = !showQuickChannels },
                                onToggleChannelCatalog = { showChannelCatalog = !showChannelCatalog },
                                onTogglePlaylists = { showPlaylists = !showPlaylists },
                                onToggleActions = { showActions = !showActions },
                                onToggleStreamTools = { showStreamTools = !showStreamTools },
                                onToggleEpgWizard = { showEpgWizard = !showEpgWizard },
                                onToggleTechnicalInfo = { showTechnicalInfo = !showTechnicalInfo },
                                onToggleUnavailable = { hideUnavailable = !hideUnavailable },
                                onExportLogs = { viewModel.exportLogs(context) }
                            )
                        }
                        if (showTechnicalInfo) {
                            Text(
                                "Каналы: рабочих=${healthStats.first}, неизвестно=${healthStats.second}, недоступно=${healthStats.third}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("Поток: ${state.selectedStreamKind}", style = MaterialTheme.typography.bodySmall)
                            Text(state.epgStatus, style = MaterialTheme.typography.bodySmall)
                            Text(state.channelListEpgStatus, style = MaterialTheme.typography.bodySmall)
                            if (state.parentalControlEnabled && state.parentalHideAdultChannels) {
                                Text("Скрыто родительским фильтром: $parentalHiddenCount", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (showTechnicalInfo) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Тех.информация", style = MaterialTheme.typography.titleSmall)
                            Text("Effective player: ${state.effectivePlayer} | default: ${state.defaultPlayer} | override: ${state.channelPlayerOverride ?: "default"}")
                            Text("Buffer: ${state.bufferProfile} | manual=${state.manualBuffer}")
                            Text("Engine: connected=${state.engineConnected}, peers=${state.enginePeers}, speed=${state.engineSpeedKbps} kbps")
                            Text("Engine endpoint: ${state.engineEndpoint} | Tor=${state.torEnabled}")
                            Text("Engine message: ${state.engineMessage}")
                            Text("Режим плеера: ${if (state.internalPlayerExpanded) "fullscreen" else "обычный"} | Масштаб: ${state.playerVideoScale}")
                            Text(
                                "Multiview: $multiviewLabel | максимум=${state.multiviewSupportedPaneCount}-up | " +
                                    "окна=${multiviewSessions.mapIndexed { index, session -> "${index + 2}=${session?.channelName ?: "-"}" }.joinToString()}"
                            )
                            Text("Multiview capability: ${state.multiviewCapabilitySummary}")
                            if (state.multiviewCapabilityWarnings.isNotEmpty()) {
                                Text("Multiview ограничения: ${state.multiviewCapabilityWarnings.joinToString("; ")}")
                            }
                            Text("Встроенный плеер: двойной клик по видео = fullscreen/обычный режим.")
                            Text("VLC: сначала запускается прямой fullscreen, затем fallback совместимости.")
                            val aceDescriptorLabel = state.selectedAceDescriptor?.let { descriptor ->
                                if (descriptor.length > 110) "${descriptor.take(110)}..." else descriptor
                            } ?: "не обнаружен"
                            Text("Ace-дескриптор: $aceDescriptorLabel")
                            state.channelEpgInfo?.let { epg ->
                                val nowText = epg.now?.let { "Сейчас: ${it.title}" } ?: "Сейчас: нет данных"
                                val nextText = epg.next?.let { "Далее: ${it.title}" } ?: "Далее: нет данных"
                                Text(nowText)
                                Text(nextText)
                                Text("EPG source: ${epg.epgSourceUrl ?: "-"}")
                                epg.upcoming.take(4).forEach { item ->
                                    Text("• ${formatEpgTime(item.startEpochMs)} - ${formatEpgTime(item.endEpochMs)} | ${item.title}")
                                }
                            }
                            state.resolvedStreamUrl?.let { resolved ->
                                Text("Подготовленный URL: $resolved")
                            }
                        }
                    }
                }
            }

        if (showActions) {
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Действия плеера", style = MaterialTheme.typography.titleSmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { viewModel.playSelected(context) }, enabled = !state.isStartingPlayback) {
                                Text(if (state.isStartingPlayback) "Запуск..." else "Воспроизвести (по настройке)")
                            }
                            Button(onClick = viewModel::playSelectedInternal, enabled = !state.isStartingPlayback) {
                                Text("Воспроизвести встроенным")
                            }
                            Button(onClick = { viewModel.playSelectedVlc(context) }, enabled = !state.isStartingPlayback) {
                                Text("Воспроизвести во VLC (fullscreen)")
                            }
                            Button(
                                onClick = { viewModel.playSelectedViaAce(context) },
                                enabled = !state.isStartingPlayback && state.selectedChannelAceCapable
                            ) {
                                Text("Воспроизвести через Ace Engine")
                            }
                            Button(onClick = viewModel::checkEngineNow, enabled = !state.isStartingPlayback) {
                                Text("Проверить Ace Engine")
                            }
                            Button(onClick = viewModel::stopInternalPlayback) {
                                Text("Остановить встроенный")
                            }
                            Button(onClick = viewModel::toggleMultiview) {
                                Text(if (state.multiviewEnabled) "Multiview: выкл" else "Multiview: 2-up")
                            }
                            Button(onClick = viewModel::enableTwoUpMultiview) {
                                Text("Multiview: 2-up")
                            }
                            Button(
                                onClick = viewModel::enableFourUpMultiview,
                                enabled = state.multiviewSupportedPaneCount >= 4
                            ) {
                                Text("Multiview: 4-up")
                            }
                            Text(
                                state.multiviewCapabilitySummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            configuredPaneIndices.forEach { paneIndex ->
                                Button(
                                    onClick = { viewModel.stopPane(paneIndex) },
                                    enabled = when (paneIndex) {
                                        2 -> state.secondaryInternalSession != null
                                        3 -> state.tertiaryInternalSession != null
                                        4 -> state.quaternaryInternalSession != null
                                        else -> false
                                    }
                                ) {
                                    Text("Остановить окно $paneIndex")
                                }
                            }
                            Button(onClick = { viewModel.setInternalPlayerExpanded(false) }) {
                                Text("Обычный экран")
                            }
                            Button(onClick = { viewModel.setInternalPlayerExpanded(true) }) {
                                Text("Fullscreen")
                            }
                            Button(onClick = viewModel::cycleVideoScale) {
                                Text("Кадр: ${state.playerVideoScale}")
                            }
                            Button(onClick = { viewModel.setChannelOverride(PlayerType.INTERNAL) }) {
                                Text("Override: Internal")
                            }
                            Button(onClick = { viewModel.setChannelOverride(PlayerType.VLC) }) {
                                Text("Override: VLC")
                            }
                            Button(onClick = viewModel::clearChannelOverride) {
                                Text("Override: Default")
                            }
                            Button(onClick = viewModel::toggleSelectedFavorite, enabled = state.selectedChannelId != null) {
                                val isFavorite =
                                    state.selectedChannelId?.let { id -> state.favoriteChannelIds.contains(id) } == true
                                Text(if (isFavorite) "Убрать из избранного" else "В избранное")
                            }
                            Button(onClick = { viewModel.installVlc(context) }) {
                                Text("Установить VLC")
                            }
                        }
                    }
                }
            }
        }

        if (showStreamTools) {
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Тест потока URL", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = state.testStreamUrl,
                            onValueChange = viewModel::updateTestStreamUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("URL для проверки (http/https или с |headers)") },
                            singleLine = true
                        )
                        Button(onClick = viewModel::fillTestStreamFromSelected) {
                            Text("Взять URL выбранного канала")
                        }
                        Button(
                            onClick = viewModel::probeTestStream,
                            enabled = !state.isTestingStream
                        ) {
                            Text(if (state.isTestingStream) "Проверка..." else "Проверить URL потока")
                        }
                        state.testStreamResult?.let { probe ->
                            Text("Результат теста: $probe")
                        }
                    }
                }
            }
        }

        if (showEpgWizard) {
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("EPG мастер", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Настройка EPG для выбранного плейлиста: URL -> проверить -> сохранить.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = state.epgWizardUrl,
                            onValueChange = viewModel::updateEpgWizardUrl,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("EPG URL (http/https)") },
                            singleLine = true
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = viewModel::fillEpgWizardFromSelectedPlaylist,
                                enabled = !state.isSavingEpgWizard
                            ) {
                                Text("Подставить из плейлиста")
                            }
                            Button(
                                onClick = viewModel::saveEpgWizardAndTest,
                                enabled = !state.isSavingEpgWizard
                            ) {
                                Text(if (state.isSavingEpgWizard) "Сохраняем..." else "Проверить и сохранить")
                            }
                        }
                        Text(state.epgWizardStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            val session = state.internalSession
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        when {
                            session != null -> "Сейчас играет: ${session.channelName}"
                            selectedChannelName != null -> "Выбран канал: $selectedChannelName"
                            else -> "Встроенный плеер готов к запуску"
                        }
                    )
                    if (state.multiviewEnabled) {
                        MultiviewPanel(
                            multiviewMode = state.multiviewMode,
                            primarySession = session,
                            additionalSessions = multiviewSessions,
                            selectedChannelName = selectedChannelName,
                            targetPaneIndex = selectedMultiviewTargetPane,
                            targetPaneIndices = configuredPaneIndices,
                            primaryExpanded = state.internalPlayerExpanded,
                            scale = state.playerVideoScale,
                            onPrimaryReady = { sessionId -> viewModel.onInternalPlaybackReady(sessionId) },
                            onPrimaryError = { sessionId, message ->
                                viewModel.onInternalPlaybackError(
                                    message = message,
                                    context = context,
                                    sessionId = sessionId
                                )
                            },
                            onPaneReady = viewModel::onAdditionalPlaybackReady,
                            onPaneError = viewModel::onAdditionalPlaybackError,
                            onTargetPaneSelected = { paneIndex -> selectedMultiviewTargetPane = paneIndex },
                            onTogglePrimaryExpanded = viewModel::toggleInternalPlayerSize,
                            onStopPane = viewModel::stopPane
                        )
                        if (showQuickChannels) {
                            ChannelQuickPanel(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                                channels = filteredChannels,
                                selectedChannelId = multiviewTargetChannelId,
                                favoriteChannelIds = state.favoriteChannelIds,
                                epgProgramsByChannel = state.channelListEpgPrograms,
                                expandedEpgChannelIds = expandedEpgChannelIds,
                                title = "Быстрый выбор для окна $selectedMultiviewTargetPane",
                                onToggleProgram = ::toggleChannelProgram,
                                onToggleFavorite = viewModel::toggleChannelFavorite,
                                onSelect = { channelId ->
                                    viewModel.playChannelInPane(channelId, selectedMultiviewTargetPane)
                                }
                            )
                        }
                    } else {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val twoPane = maxWidth >= 760.dp && showQuickChannels
                            if (twoPane) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 320.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1.15f)) {
                                        if (session != null && !state.internalPlayerExpanded) {
                                            InternalPlayerHost(
                                                session = session,
                                                onReady = { viewModel.onInternalPlaybackReady(session.sessionId) },
                                                onError = { message ->
                                                    viewModel.onInternalPlaybackError(
                                                        message = message,
                                                        context = context,
                                                        sessionId = session.sessionId
                                                    )
                                                },
                                                scale = state.playerVideoScale,
                                                expanded = false,
                                                onToggleExpanded = viewModel::toggleInternalPlayerSize,
                                                forceFullWidth = true
                                            )
                                        } else {
                                            InternalPlayerPlaceholder(
                                                expanded = false,
                                                onToggleExpanded = viewModel::toggleInternalPlayerSize,
                                                forceFullWidth = true,
                                                selectedChannelName = selectedChannelName
                                            )
                                        }
                                    }
                                    ChannelQuickPanel(
                                        modifier = Modifier
                                            .weight(0.85f)
                                            .fillMaxHeight(),
                                        channels = filteredChannels,
                                        selectedChannelId = state.selectedChannelId,
                                        favoriteChannelIds = state.favoriteChannelIds,
                                        epgProgramsByChannel = state.channelListEpgPrograms,
                                        expandedEpgChannelIds = expandedEpgChannelIds,
                                        onToggleProgram = ::toggleChannelProgram,
                                        onToggleFavorite = viewModel::toggleChannelFavorite,
                                        onSelect = viewModel::playChannelInternal
                                    )
                                }
                            } else {
                                if (session != null && !state.internalPlayerExpanded) {
                                    InternalPlayerHost(
                                        session = session,
                                        onReady = { viewModel.onInternalPlaybackReady(session.sessionId) },
                                        onError = { message ->
                                            viewModel.onInternalPlaybackError(
                                                message = message,
                                                context = context,
                                                sessionId = session.sessionId
                                            )
                                        },
                                        scale = state.playerVideoScale,
                                        expanded = false,
                                        onToggleExpanded = viewModel::toggleInternalPlayerSize
                                    )
                                } else {
                                    InternalPlayerPlaceholder(
                                        expanded = false,
                                        onToggleExpanded = viewModel::toggleInternalPlayerSize,
                                        selectedChannelName = selectedChannelName
                                    )
                                }
                                if (showQuickChannels) {
                                    ChannelQuickPanel(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp),
                                        channels = filteredChannels,
                                        selectedChannelId = state.selectedChannelId,
                                        favoriteChannelIds = state.favoriteChannelIds,
                                        epgProgramsByChannel = state.channelListEpgPrograms,
                                        expandedEpgChannelIds = expandedEpgChannelIds,
                                        onToggleProgram = ::toggleChannelProgram,
                                        onToggleFavorite = viewModel::toggleChannelFavorite,
                                        onSelect = viewModel::playChannelInternal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        state.lastError?.let { error ->
            item {
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }
        state.lastInfo?.let { info ->
            item {
                Text(text = info)
            }
        }

        if (showPlaylists) {
            item {
                PlaylistScrollPanel(
                    playlists = state.playlists,
                    selectedPlaylistId = state.selectedPlaylistId,
                    onSelectPlaylist = viewModel::selectPlaylist
                )
            }
        }

        if (showChannelCatalog) {
            item {
                Text("Каналы (${filteredChannels.size}/${state.channels.size})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Подсказка: выбор канала сразу запускает воспроизведение во встроенном плеере.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item {
                OutlinedTextField(
                    value = state.channelQuery,
                    onValueChange = viewModel::updateChannelQuery,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск каналов") },
                    singleLine = true
                )
            }
            if (state.availableGroups.isNotEmpty()) {
                item {
                    Text("Группы каналов (${state.availableGroups.size})", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.selectedGroup == null) {
                            Button(onClick = { viewModel.selectGroup(null) }) {
                                Text("Все группы")
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.selectGroup(null) }) {
                                Text("Все группы")
                            }
                        }
                        state.availableGroups.forEach { group ->
                            if (state.selectedGroup == group) {
                                Button(onClick = { viewModel.selectGroup(group) }) {
                                    Text(group)
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.selectGroup(group) }) {
                                    Text(group)
                                }
                            }
                        }
                    }
                }
            }
            if (state.selectedGroup != null && state.availableSubGroups.isNotEmpty()) {
                item {
                    Text("Подгруппы (${state.availableSubGroups.size})", style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.selectedSubGroup == null) {
                            Button(onClick = { viewModel.selectSubGroup(null) }) {
                                Text("Все подгруппы")
                            }
                        } else {
                            OutlinedButton(onClick = { viewModel.selectSubGroup(null) }) {
                                Text("Все подгруппы")
                            }
                        }
                        state.availableSubGroups.forEach { subGroup ->
                            if (state.selectedSubGroup == subGroup) {
                                Button(onClick = { viewModel.selectSubGroup(subGroup) }) {
                                    Text(subGroup)
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.selectSubGroup(subGroup) }) {
                                    Text(subGroup)
                                }
                            }
                        }
                    }
                }
            }
            if (filteredChannels.isEmpty()) {
                item {
                    Text("Нет каналов по текущему фильтру")
                }
            } else {
                item {
                    ChannelCatalogScrollPanel(
                        channels = filteredChannels,
                        selectedChannelId = state.selectedChannelId,
                        favoriteChannelIds = state.favoriteChannelIds,
                        epgProgramsByChannel = state.channelListEpgPrograms,
                        expandedEpgChannelIds = expandedEpgChannelIds,
                        availablePaneTargets = availablePaneTargets,
                        showTechnicalInfo = showTechnicalInfo,
                        onToggleProgram = ::toggleChannelProgram,
                        onToggleFavorite = viewModel::toggleChannelFavorite,
                        onPlayChannel = viewModel::playChannelInternal,
                        onPlayInPane = viewModel::playChannelInPane
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
}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerViewOptions(
    showQuickChannels: Boolean,
    showChannelCatalog: Boolean,
    showPlaylists: Boolean,
    showActions: Boolean,
    showStreamTools: Boolean,
    showEpgWizard: Boolean,
    showTechnicalInfo: Boolean,
    hideUnavailable: Boolean,
    onToggleQuickChannels: () -> Unit,
    onToggleChannelCatalog: () -> Unit,
    onTogglePlaylists: () -> Unit,
    onToggleActions: () -> Unit,
    onToggleStreamTools: () -> Unit,
    onToggleEpgWizard: () -> Unit,
    onToggleTechnicalInfo: () -> Unit,
    onToggleUnavailable: () -> Unit,
    onExportLogs: () -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CompactToggleButton(showQuickChannels, "Быстрый список", onToggleQuickChannels)
        CompactToggleButton(showChannelCatalog, "Каталог", onToggleChannelCatalog)
        CompactToggleButton(showPlaylists, "Плейлисты", onTogglePlaylists)
        CompactToggleButton(showActions, "Действия", onToggleActions)
        CompactToggleButton(showStreamTools, "Тест потока", onToggleStreamTools)
        CompactToggleButton(showEpgWizard, "EPG", onToggleEpgWizard)
        CompactToggleButton(showTechnicalInfo, "Техинфо", onToggleTechnicalInfo)
        CompactToggleButton(!hideUnavailable, "Недоступные", onToggleUnavailable)
        OutlinedButton(onClick = onExportLogs) {
            Text("Логи")
        }
    }
}

@Composable
private fun CompactToggleButton(
    selected: Boolean,
    text: String,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
private fun MultiviewPanel(
    multiviewMode: MultiviewMode,
    primarySession: InternalPlaybackSession?,
    additionalSessions: List<InternalPlaybackSession?>,
    selectedChannelName: String?,
    targetPaneIndex: Int,
    targetPaneIndices: List<Int>,
    primaryExpanded: Boolean,
    scale: PlayerVideoScale,
    onPrimaryReady: (Long) -> Unit,
    onPrimaryError: (Long, String) -> Unit,
    onPaneReady: (Int) -> Unit,
    onPaneError: (Int, String) -> Unit,
    onTargetPaneSelected: (Int) -> Unit,
    onTogglePrimaryExpanded: () -> Unit,
    onStopPane: (Int) -> Unit
) {
    val paneSessions = buildList {
        add(1 to primarySession.takeUnless { primaryExpanded })
        when (multiviewMode) {
            MultiviewMode.OFF -> Unit
            MultiviewMode.TWO_UP -> add(2 to additionalSessions.getOrNull(0))
            MultiviewMode.FOUR_UP -> {
                add(2 to additionalSessions.getOrNull(0))
                add(3 to additionalSessions.getOrNull(1))
                add(4 to additionalSessions.getOrNull(2))
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Multiview ${if (multiviewMode == MultiviewMode.FOUR_UP) "4-up" else "2-up"}", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                paneSessions
                    .filter { it.first > 1 }
                    .forEach { (paneIndex, session) ->
                        OutlinedButton(onClick = { onStopPane(paneIndex) }, enabled = session != null) {
                            Text("Окно $paneIndex: стоп")
                        }
                    }
            }
        }
        if (targetPaneIndices.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Быстрый выбор канала: целевое окно $targetPaneIndex",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    targetPaneIndices.forEach { paneIndex ->
                        if (paneIndex == targetPaneIndex) {
                            Button(onClick = { onTargetPaneSelected(paneIndex) }) {
                                Text("Окно $paneIndex")
                            }
                        } else {
                            OutlinedButton(onClick = { onTargetPaneSelected(paneIndex) }) {
                                Text("Окно $paneIndex")
                            }
                        }
                    }
                }
            }
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val wideTwoUp = maxWidth >= 760.dp
            val wideFourUp = maxWidth >= 960.dp
            when {
                multiviewMode == MultiviewMode.FOUR_UP && wideFourUp -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        paneSessions.chunked(2).forEach { rowSessions ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                rowSessions.forEach { (paneIndex, session) ->
                                    val realPrimarySession = primarySession.takeIf { paneIndex == 1 }
                                    MultiviewPane(
                                        title = "Окно $paneIndex",
                                        session = session,
                                        selectedChannelName = if (paneIndex == 1) {
                                            selectedChannelName
                                        } else {
                                            session?.channelName
                                        },
                                        scale = scale,
                                        onReady = if (paneIndex == 1) {
                                            { realPrimarySession?.sessionId?.let(onPrimaryReady) }
                                        } else {
                                            { onPaneReady(paneIndex) }
                                        },
                                        onError = if (paneIndex == 1) {
                                            { message -> realPrimarySession?.sessionId?.let { onPrimaryError(it, message) } }
                                        } else {
                                            { message -> onPaneError(paneIndex, message) }
                                        },
                                        onToggleExpanded = if (paneIndex == 1) onTogglePrimaryExpanded else ({}),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (rowSessions.size == 1) {
                                    SpacerPane(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                multiviewMode == MultiviewMode.TWO_UP && wideTwoUp -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        paneSessions.forEach { (paneIndex, session) ->
                            val realPrimarySession = primarySession.takeIf { paneIndex == 1 }
                            MultiviewPane(
                                title = "Окно $paneIndex",
                                session = session,
                                selectedChannelName = if (paneIndex == 1) {
                                    selectedChannelName
                                } else {
                                    session?.channelName
                                },
                                scale = scale,
                                onReady = if (paneIndex == 1) {
                                    { realPrimarySession?.sessionId?.let(onPrimaryReady) }
                                } else {
                                    { onPaneReady(paneIndex) }
                                },
                                onError = if (paneIndex == 1) {
                                    { message -> realPrimarySession?.sessionId?.let { onPrimaryError(it, message) } }
                                } else {
                                    { message -> onPaneError(paneIndex, message) }
                                },
                                onToggleExpanded = if (paneIndex == 1) onTogglePrimaryExpanded else ({}),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        paneSessions.forEach { (paneIndex, session) ->
                            val realPrimarySession = primarySession.takeIf { paneIndex == 1 }
                            MultiviewPane(
                                title = "Окно $paneIndex",
                                session = session,
                                selectedChannelName = if (paneIndex == 1) {
                                    selectedChannelName
                                } else {
                                    session?.channelName
                                },
                                scale = scale,
                                onReady = if (paneIndex == 1) {
                                    { realPrimarySession?.sessionId?.let(onPrimaryReady) }
                                } else {
                                    { onPaneReady(paneIndex) }
                                },
                                onError = if (paneIndex == 1) {
                                    { message -> realPrimarySession?.sessionId?.let { onPrimaryError(it, message) } }
                                } else {
                                    { message -> onPaneError(paneIndex, message) }
                                },
                                onToggleExpanded = if (paneIndex == 1) onTogglePrimaryExpanded else ({}),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpacerPane(modifier: Modifier = Modifier) {
    Box(modifier = modifier)
}

@Composable
@UnstableApi
private fun MultiviewPane(
    title: String,
    session: InternalPlaybackSession?,
    selectedChannelName: String?,
    scale: PlayerVideoScale,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (session != null) "$title: ${session.channelName}" else "$title: канал не выбран",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (session != null) {
            InternalPlayerHost(
                session = session,
                onReady = onReady,
                onError = onError,
                scale = scale,
                expanded = false,
                onToggleExpanded = onToggleExpanded,
                forceFullWidth = true
            )
        } else {
            InternalPlayerPlaceholder(
                expanded = false,
                onToggleExpanded = onToggleExpanded,
                forceFullWidth = true,
                selectedChannelName = selectedChannelName
            )
        }
    }
}

@Composable
@UnstableApi
private fun FullscreenInternalPlayerOverlay(
    session: InternalPlaybackSession?,
    selectedChannelName: String?,
    scale: PlayerVideoScale,
    onReady: (Long) -> Unit,
    onError: (String) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (session != null) {
            InternalPlayerHost(
                session = session,
                onReady = { onReady(session.sessionId) },
                onError = onError,
                scale = scale,
                expanded = true,
                onToggleExpanded = onClose,
                forceFullWidth = true,
                fullscreenMode = true
            )
        } else {
            InternalPlayerPlaceholder(
                expanded = true,
                onToggleExpanded = onClose,
                forceFullWidth = true,
                selectedChannelName = selectedChannelName,
                fullscreenMode = true
            )
        }
        var localTime by remember { mutableStateOf(formatPlayerLocalTime(System.currentTimeMillis())) }
        LaunchedEffect(Unit) {
            while (isActive) {
                localTime = formatPlayerLocalTime(System.currentTimeMillis())
                delay(1_000L)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selectedChannelName ?: "Встроенный плеер",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                localTime,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelQuickPanel(
    modifier: Modifier = Modifier,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteChannelIds: Set<Long>,
    epgProgramsByChannel: Map<Long, List<EpgProgram>>,
    expandedEpgChannelIds: Set<Long>,
    title: String = "Список каналов",
    onToggleProgram: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onSelect: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun scrollQuick(delta: Int) {
        if (channels.isEmpty()) return
        val current = listState.firstVisibleItemIndex
        val last = (channels.lastIndex).coerceAtLeast(0)
        val target = wrappedIndex(current + delta, last)
        scope.launch {
            listState.animateScrollToItem(target)
        }
    }

    fun scrollToStart() {
        if (channels.isEmpty()) return
        scope.launch { listState.animateScrollToItem(0) }
    }

    fun scrollToEnd() {
        if (channels.isEmpty()) return
        scope.launch { listState.animateScrollToItem(channels.lastIndex.coerceAtLeast(0)) }
    }

    LaunchedEffect(selectedChannelId, channels) {
        val index = channels.indexOfFirst { it.id == selectedChannelId }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    Card(
        modifier = modifier
            .tvFocusOutline()
            .onPreviewKeyEvent { event ->
                handleQuickListKeyEvent(
                    event = event,
                    listState = listState,
                    scope = scope,
                    canScroll = channels.isNotEmpty()
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "$title · ${channels.size}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = ::scrollToStart, enabled = channels.isNotEmpty()) {
                    Text("В начало")
                }
                OutlinedButton(onClick = { scrollQuick(-QUICK_PAGE_STEP) }, enabled = channels.isNotEmpty()) {
                    Text("Pg -")
                }
                OutlinedButton(onClick = { scrollQuick(-QUICK_SCROLL_STEP) }, enabled = channels.isNotEmpty()) {
                    Text("▲")
                }
                OutlinedButton(onClick = { scrollQuick(QUICK_SCROLL_STEP) }, enabled = channels.isNotEmpty()) {
                    Text("▼")
                }
                OutlinedButton(onClick = { scrollQuick(QUICK_PAGE_STEP) }, enabled = channels.isNotEmpty()) {
                    Text("Pg +")
                }
                OutlinedButton(onClick = ::scrollToEnd, enabled = channels.isNotEmpty()) {
                    Text("В конец")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 320.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(channels, key = { it.id }) { channel ->
                        val programs = epgProgramsByChannel[channel.id].orEmpty()
                        val programExpanded = channel.id in expandedEpgChannelIds
                        val isFavorite = channel.id in favoriteChannelIds
                        Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                            val mark = if (channel.id == selectedChannelId) "● " else ""
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "$mark${channel.name}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                ChannelEpgCompactLine(programs = programs)
                                if (programExpanded) {
                                    ChannelProgramList(programs = programs)
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(onClick = { onSelect(channel.id) }) {
                                        Text(if (channel.id == selectedChannelId) "Играет" else "Играть")
                                    }
                                    OutlinedButton(
                                        onClick = { onToggleProgram(channel.id) },
                                        enabled = programs.isNotEmpty()
                                    ) {
                                        Text(if (programExpanded) "Скрыть программу" else "Программа")
                                    }
                                    OutlinedButton(onClick = { onToggleFavorite(channel.id) }) {
                                        Text(if (isFavorite) "Убрать из избранного" else "В избранное")
                                    }
                                }
                            }
                        }
                    }
                }
                VerticalListScrollControls(
                    listState = listState,
                    itemCount = channels.size,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun PlaylistScrollPanel(
    playlists: List<Playlist>,
    selectedPlaylistId: Long?,
    onSelectPlaylist: (Long) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedPlaylistId, playlists) {
        val index = playlists.indexOfFirst { it.id == selectedPlaylistId }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Плейлисты (${playlists.size})", style = MaterialTheme.typography.titleMedium)
            if (playlists.isEmpty()) {
                Text("Плейлисты не найдены")
                return@Column
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 360.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(playlists, key = { it.id }) { playlist ->
                        Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("${playlist.channelCount} каналов", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { onSelectPlaylist(playlist.id) }) {
                                    Text(
                                        if (playlist.id == selectedPlaylistId) {
                                            "Текущий плейлист"
                                        } else {
                                            "Открыть плейлист"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                VerticalListScrollControls(
                    listState = listState,
                    itemCount = playlists.size,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelCatalogScrollPanel(
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteChannelIds: Set<Long>,
    epgProgramsByChannel: Map<Long, List<EpgProgram>>,
    expandedEpgChannelIds: Set<Long>,
    availablePaneTargets: List<Int>,
    showTechnicalInfo: Boolean,
    onToggleProgram: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayChannel: (Long) -> Unit,
    onPlayInPane: (Long, Int) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selectedChannelId, channels) {
        val index = channels.indexOfFirst { it.id == selectedChannelId }
        if (index >= 0) {
            listState.scrollToItem(index)
        }
    }

    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Каталог каналов · ${channels.size}", style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 620.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(channels, key = { it.id }) { channel ->
                        ChannelCatalogRow(
                            channel = channel,
                            selected = channel.id == selectedChannelId,
                            epgPrograms = epgProgramsByChannel[channel.id].orEmpty(),
                            programExpanded = channel.id in expandedEpgChannelIds,
                            isFavorite = channel.id in favoriteChannelIds,
                            availablePaneTargets = availablePaneTargets,
                            showTechnicalInfo = showTechnicalInfo,
                            onToggleProgram = onToggleProgram,
                            onToggleFavorite = onToggleFavorite,
                            onPlayChannel = onPlayChannel,
                            onPlayInPane = onPlayInPane
                        )
                    }
                }
                VerticalListScrollControls(
                    listState = listState,
                    itemCount = channels.size,
                    modifier = Modifier.fillMaxHeight()
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelCatalogRow(
    channel: Channel,
    selected: Boolean,
    epgPrograms: List<EpgProgram>,
    programExpanded: Boolean,
    isFavorite: Boolean,
    availablePaneTargets: List<Int>,
    showTechnicalInfo: Boolean,
    onToggleProgram: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onPlayChannel: (Long) -> Unit,
    onPlayInPane: (Long, Int) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                ChannelLogo(
                    logoUrl = channel.logo,
                    modifier = Modifier.size(54.dp)
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        channel.group ?: "Без группы",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    ChannelEpgCompactLine(programs = epgPrograms)
                    if (programExpanded) {
                        ChannelProgramList(programs = epgPrograms)
                    }
                    if (showTechnicalInfo) {
                        Text("URL: ${channel.streamUrl}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onPlayChannel(channel.id) }) {
                    Text(if (selected) "Играет" else "Выбрать и играть")
                }
                OutlinedButton(
                    onClick = { onToggleProgram(channel.id) },
                    enabled = epgPrograms.isNotEmpty()
                ) {
                    Text(if (programExpanded) "Скрыть программу" else "Программа")
                }
                OutlinedButton(onClick = { onToggleFavorite(channel.id) }) {
                    Text(if (isFavorite) "Убрать из избранного" else "В избранное")
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    availablePaneTargets.forEach { paneIndex ->
                        OutlinedButton(onClick = { onPlayInPane(channel.id, paneIndex) }) {
                            Text("В окно $paneIndex")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelEpgCompactLine(programs: List<EpgProgram>) {
    if (programs.isEmpty()) return
    val now = programs.firstOrNull()
    val next = programs.drop(1).firstOrNull()
    val text = buildString {
        now?.let { program ->
            append("Сейчас: ")
            append(formatEpgTime(program.startEpochMs))
            append(" ")
            append(program.title)
        }
        next?.let { program ->
            if (isNotEmpty()) append(" | ")
            append("Далее: ")
            append(formatEpgTime(program.startEpochMs))
            append(" ")
            append(program.title)
        }
    }
    if (text.isBlank()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ChannelProgramList(programs: List<EpgProgram>) {
    if (programs.isEmpty()) {
        Text(
            text = "Программа не найдена",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        programs.take(6).forEach { program ->
            Text(
                text = "${formatEpgTime(program.startEpochMs)}-${formatEpgTime(program.endEpochMs)}  ${program.title}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun VerticalListScrollControls(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
    lineStep: Int = QUICK_SCROLL_STEP,
    pageStep: Int = QUICK_PAGE_STEP
) {
    val scope = rememberCoroutineScope()
    val lastIndex = (itemCount - 1).coerceAtLeast(0)
    val firstVisible by remember(listState, lastIndex) {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, lastIndex) }
    }
    val enabled = itemCount > 1

    fun scrollTo(index: Int) {
        if (!enabled) return
        scope.launch {
            listState.animateScrollToItem(index.coerceIn(0, lastIndex))
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedButton(onClick = { scrollTo(0) }, enabled = enabled) {
            Text("В начало")
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { scrollTo(firstVisible - pageStep) }, enabled = enabled) {
                Text("Pg -")
            }
            OutlinedButton(onClick = { scrollTo(firstVisible - lineStep) }, enabled = enabled) {
                Text("▲")
            }
            Text("${firstVisible + 1}/$itemCount", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { scrollTo(firstVisible + lineStep) }, enabled = enabled) {
                Text("▼")
            }
            OutlinedButton(onClick = { scrollTo(firstVisible + pageStep) }, enabled = enabled) {
                Text("Pg +")
            }
        }
        OutlinedButton(onClick = { scrollTo(lastIndex) }, enabled = enabled) {
            Text("В конец")
        }
    }
}

private fun wrappedIndex(rawIndex: Int, lastIndex: Int): Int {
    if (lastIndex <= 0) return 0
    return when {
        rawIndex < 0 -> lastIndex
        rawIndex > lastIndex -> 0
        else -> rawIndex
    }
}

private fun Channel.matchesParentalKeywords(keywords: List<String>): Boolean {
    if (keywords.isEmpty()) return false
    val haystack = listOf(name, group.orEmpty())
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return keywords.any { keyword ->
        val normalized = keyword.trim().lowercase(Locale.ROOT)
        normalized.isNotBlank() && haystack.contains(normalized)
    }
}

private fun handleQuickListKeyEvent(
    event: KeyEvent,
    listState: LazyListState,
    scope: CoroutineScope,
    canScroll: Boolean
): Boolean {
    if (!canScroll || event.type != KeyEventType.KeyDown) return false
    val lastIndex = (listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
    if (lastIndex == 0) return false
    val current = listState.firstVisibleItemIndex
    fun moveTo(index: Int): Boolean {
        val target = wrappedIndex(index, lastIndex)
        scope.launch { listState.animateScrollToItem(target) }
        return true
    }
    return when (event.key) {
        Key.PageUp -> moveTo(current - QUICK_PAGE_STEP)
        Key.PageDown -> moveTo(current + QUICK_PAGE_STEP)
        Key.MoveHome -> moveTo(0)
        Key.MoveEnd -> moveTo(lastIndex)
        else -> false
    }
}

@Composable
private fun ChannelLogo(
    logoUrl: String?,
    modifier: Modifier = Modifier
) {
    val normalized = logoUrl?.trim().orEmpty()
    if (normalized.isBlank()) {
        Box(
            modifier = modifier
                .clip(MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            Text("TV", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    AsyncImage(
        model = normalized,
        contentDescription = "Логотип канала",
        modifier = modifier.clip(MaterialTheme.shapes.small),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun InternalPlayerPlaceholder(
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    forceFullWidth: Boolean = false,
    selectedChannelName: String?,
    fullscreenMode: Boolean = false
) {
    Box(
        modifier = Modifier.playerViewportModifier(
            expanded = expanded,
            forceFullWidth = forceFullWidth,
            fullscreenMode = fullscreenMode
        )
            .tvFocusOutline()
            .pointerInput(expanded) {
                detectTapGestures(onTap = { onToggleExpanded() }, onDoubleTap = { onToggleExpanded() })
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Встроенный плеер")
            if (selectedChannelName == null) {
                Text("Выберите канал из списка справа")
            } else {
                Text("Выбран: $selectedChannelName")
                Text("Канал запускается сразу при выборе")
            }
        }
        OutlinedButton(
            onClick = onToggleExpanded,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Text(if (expanded) "Свернуть" else "Развернуть")
        }
    }
}

private fun Modifier.playerViewportModifier(
    expanded: Boolean,
    forceFullWidth: Boolean,
    fullscreenMode: Boolean = false
): Modifier {
    return if (fullscreenMode) {
        fillMaxSize()
    } else if (forceFullWidth) {
        fillMaxWidth()
            .aspectRatio(if (expanded) 16f / 9f else 4f / 3f)
    } else if (expanded) {
        fillMaxWidth()
            .aspectRatio(16f / 9f)
    } else {
        fillMaxWidth(0.56f)
            .aspectRatio(4f / 3f)
    }
}

@UnstableApi
@Composable
private fun InternalPlayerHost(
    session: InternalPlaybackSession,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    scale: PlayerVideoScale,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    forceFullWidth: Boolean = false,
    fullscreenMode: Boolean = false
) {
    val context = LocalContext.current
    val playerBuildResult = remember(session.sessionId, session.requestHeaders) {
        runCatching {
            val requestHeaders = compatibleHttpHeaders(session.requestHeaders)
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setUserAgent(requestHeaders["User-Agent"] ?: IPTV_USER_AGENT)
                .setDefaultRequestProperties(requestHeaders.filterKeys { it != "User-Agent" })
            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            val trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setExceedVideoConstraintsIfNecessary(true)
                        .setAllowVideoMixedMimeTypeAdaptiveness(true)
                )
            }
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

            ExoPlayer.Builder(context, renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(session.bufferConfig.toLoadControl())
                .build()
        }
    }
    val exoPlayer = playerBuildResult.getOrNull()
    val initError = playerBuildResult.exceptionOrNull()
    var currentTracks by remember(session.sessionId) { mutableStateOf(Tracks.EMPTY) }
    var readyReported by remember(session.sessionId) { mutableStateOf(false) }
    var inlineTrackPanelVisible by remember(session.sessionId) { mutableStateOf(false) }
    var fullscreenTrackPanelVisible by remember(session.sessionId) { mutableStateOf(false) }
    val trackPreferenceStore = remember(context) { PlayerTrackPreferenceStore(context) }
    var savedTrackPreferences by remember { mutableStateOf(trackPreferenceStore.loadAll()) }

    if (exoPlayer == null) {
        DisposableEffect(session.sessionId, initError?.message) {
            onError("Player init failed: ${initError?.message ?: "unknown"}")
            onDispose {}
        }
        Text(
            text = "Не удалось инициализировать встроенный плеер. Попробуйте VLC.",
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    DisposableEffect(session.sessionId) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !readyReported) {
                    readyReported = true
                    onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    return
                }
                val msg = formatPlaybackException(error)
                onError(msg)
                try {
                    FileLogger.write(context, "ERROR", "Player", msg, error)
                } catch (ignored: Exception) {}
            }

            override fun onTracksChanged(tracks: Tracks) {
                currentTracks = tracks
            }
        }

        exoPlayer.addListener(listener)
        val startResult = runCatching {
            val mediaItem = MediaItem.Builder()
                .setUri(session.streamUrl)
                .apply {
                    inferMediaMimeType(session.streamUrl)?.let { mimeType ->
                        setMimeType(mimeType)
                    }
                }
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
        startResult.exceptionOrNull()?.let { throwable ->
            val m = throwable.message ?: throwable.javaClass.simpleName
            onError(m)
            try { FileLogger.write(context, "ERROR", "PlayerInit", m, throwable) } catch (ignored: Exception) {}
        }

        onDispose {
            runCatching {
                exoPlayer.playWhenReady = false
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                exoPlayer.clearVideoSurface()
            }
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Один мягкий retry при долгой буферизации. Без лимита старые ТВ-боксы могут зависать
    // на повторяющихся stop/prepare во время плохого потока.
    DisposableEffect(session.sessionId, exoPlayer) {
        val recoveryJob = kotlinx.coroutines.Job()
        val recoveryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate + recoveryJob)
        var bufferingSince = 0L
        var recoveryAttempted = false

        val stateListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) {
                    bufferingSince = System.currentTimeMillis()
                } else {
                    bufferingSince = 0L
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // пробуем небольшую очистку при ошибке
                bufferingSince = 0L
            }
        }

        exoPlayer.addListener(stateListener)

        recoveryScope.launch {
            try {
                while (isActive) {
                    kotlinx.coroutines.delay(3000)
                    val since = bufferingSince
                    if (since != 0L) {
                        val elapsed = System.currentTimeMillis() - since
                        if (elapsed > 12_000 && !recoveryAttempted) {
                            recoveryAttempted = true
                            bufferingSince = 0L
                            runCatching {
                                exoPlayer.playWhenReady = false
                                exoPlayer.stop()
                                exoPlayer.prepare()
                                exoPlayer.playWhenReady = true
                            }
                        } else if (elapsed > 24_000 && recoveryAttempted) {
                            onError("Buffering timeout after recovery attempt")
                            break
                        }
                    }
                }
            } finally {
                // no-op
            }
        }

        onDispose {
            recoveryJob.cancel()
            exoPlayer.removeListener(stateListener)
        }
    }

    val viewportModifier = Modifier.playerViewportModifier(
        expanded = expanded,
        forceFullWidth = forceFullWidth,
        fullscreenMode = fullscreenMode
    )

    LaunchedEffect(exoPlayer, currentTracks, savedTrackPreferences) {
        val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@LaunchedEffect
        applySavedTrackPreferences(
            tracks = currentTracks,
            selector = selector,
            preferences = savedTrackPreferences
        )
    }

    Box(
        modifier = viewportModifier.pointerInput(session.sessionId) {
            detectTapGestures(onDoubleTap = { onToggleExpanded() })
        }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = true
                    controllerAutoShow = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    resizeMode = when (scale) {
                        PlayerVideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PlayerVideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        PlayerVideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                    player = exoPlayer
                }
            },
            update = { view ->
                view.resizeMode = when (scale) {
                    PlayerVideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PlayerVideoScale.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    PlayerVideoScale.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
                view.player = exoPlayer
            },
            onRelease = { view ->
                view.player = null
            }
        )
        OutlinedButton(
            onClick = onToggleExpanded,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomEnd)
                .padding(8.dp)
        ) {
            Text(if (expanded) "Свернуть" else "Развернуть")
        }
        if (!fullscreenMode) {
            PlayerLocalTimeBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            )
        }
        if (fullscreenMode) {
            OutlinedButton(
                onClick = { fullscreenTrackPanelVisible = !fullscreenTrackPanelVisible },
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Text(if (fullscreenTrackPanelVisible) "Скрыть дорожки" else "Дорожки")
            }
        }
        if (fullscreenMode && fullscreenTrackPanelVisible) {
            TrackSelectionPanel(
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.TopStart)
                    .padding(8.dp)
                    .fillMaxWidth(0.72f)
                    .heightIn(max = 260.dp),
                tracks = currentTracks,
                savedPreferences = savedTrackPreferences,
                onSelectAuto = { trackType ->
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setTrackTypeDisabled(trackType, false)
                            .clearOverridesOfType(trackType)
                    )
                    savedTrackPreferences = trackPreferenceStore.saveAuto(trackType)
                },
                onDisable = { trackType ->
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    selector.setParameters(
                        selector.buildUponParameters()
                            .clearOverridesOfType(trackType)
                            .setTrackTypeDisabled(trackType, true)
                    )
                    savedTrackPreferences = trackPreferenceStore.saveDisabled(trackType)
                },
                onSelectTrack = { trackType, group, trackIndex ->
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    val format = group.getTrackFormat(trackIndex)
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setTrackTypeDisabled(trackType, false)
                            .clearOverridesOfType(trackType)
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                    )
                    savedTrackPreferences = trackPreferenceStore.saveSelected(
                        trackType = trackType,
                        language = format.language,
                        label = format.label
                    )
                },
                onClearSavedPreferences = {
                    val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                    selector.setParameters(
                        selector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    )
                    savedTrackPreferences = trackPreferenceStore.clearAll()
                }
            )
        }
    }

    if (!fullscreenMode && (currentTracks.groups.isNotEmpty() || savedTrackPreferences.isNotEmpty())) {
        OutlinedButton(
            onClick = { inlineTrackPanelVisible = !inlineTrackPanelVisible },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(if (inlineTrackPanelVisible) "Скрыть дорожки" else "Дорожки")
        }
    }

    if (!fullscreenMode && inlineTrackPanelVisible) {
        TrackSelectionPanel(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            tracks = currentTracks,
            savedPreferences = savedTrackPreferences,
            onSelectAuto = { trackType ->
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                selector.setParameters(
                    selector.buildUponParameters()
                        .setTrackTypeDisabled(trackType, false)
                        .clearOverridesOfType(trackType)
                )
                savedTrackPreferences = trackPreferenceStore.saveAuto(trackType)
            },
            onDisable = { trackType ->
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                selector.setParameters(
                    selector.buildUponParameters()
                        .clearOverridesOfType(trackType)
                        .setTrackTypeDisabled(trackType, true)
                )
                savedTrackPreferences = trackPreferenceStore.saveDisabled(trackType)
            },
            onSelectTrack = { trackType, group, trackIndex ->
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                val format = group.getTrackFormat(trackIndex)
                selector.setParameters(
                    selector.buildUponParameters()
                        .setTrackTypeDisabled(trackType, false)
                        .clearOverridesOfType(trackType)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
                )
                savedTrackPreferences = trackPreferenceStore.saveSelected(
                    trackType = trackType,
                    language = format.language,
                    label = format.label
                )
            },
            onClearSavedPreferences = {
                val selector = exoPlayer.trackSelector as? DefaultTrackSelector ?: return@TrackSelectionPanel
                selector.setParameters(
                    selector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                )
                savedTrackPreferences = trackPreferenceStore.clearAll()
            }
        )
    }
}

@Composable
private fun PlayerLocalTimeBadge(modifier: Modifier = Modifier) {
    var localTime by remember { mutableStateOf(formatPlayerLocalTime(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (isActive) {
            localTime = formatPlayerLocalTime(System.currentTimeMillis())
            delay(1_000L)
        }
    }
    Text(
        text = localTime,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        style = MaterialTheme.typography.titleSmall,
        color = Color.White,
        maxLines = 1
    )
}

@OptIn(ExperimentalLayoutApi::class)
@UnstableApi
@Composable
private fun TrackSelectionPanel(
    modifier: Modifier = Modifier,
    tracks: Tracks,
    savedPreferences: Map<Int, PlayerTrackPreference>,
    onSelectAuto: (Int) -> Unit,
    onDisable: (Int) -> Unit,
    onSelectTrack: (Int, Tracks.Group, Int) -> Unit,
    onClearSavedPreferences: () -> Unit
) {
    val groups = tracks.groups
    val videoOptions = remember(groups) { trackOptions(groups, C.TRACK_TYPE_VIDEO) }
    val audioOptions = remember(groups) { trackOptions(groups, C.TRACK_TYPE_AUDIO) }
    val textOptions = remember(groups) { trackOptions(groups, C.TRACK_TYPE_TEXT) }

    if (videoOptions.isEmpty() && audioOptions.isEmpty() && textOptions.isEmpty()) {
        Text("Дорожки: поток ещё не отдал список аудио/субтитров/видео", style = MaterialTheme.typography.bodySmall)
        return
    }

    Card(modifier = modifier.heightIn(max = 260.dp).tvFocusOutline()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Дорожки плеера", style = MaterialTheme.typography.titleSmall)
            }
            if (savedPreferences.isNotEmpty()) {
                item {
                    Text(
                        text = "Сохранённые предпочтения применяются автоматически",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                item {
                    OutlinedButton(onClick = onClearSavedPreferences) {
                        Text("Сбросить предпочтения")
                    }
                }
            }
            item {
                TrackTypeRow(
                    title = "Видео",
                    trackType = C.TRACK_TYPE_VIDEO,
                    options = videoOptions,
                    groups = groups,
                    onSelectAuto = onSelectAuto,
                    onDisable = onDisable,
                    onSelectTrack = onSelectTrack
                )
            }
            item {
                TrackTypeRow(
                    title = "Аудио",
                    trackType = C.TRACK_TYPE_AUDIO,
                    options = audioOptions,
                    groups = groups,
                    onSelectAuto = onSelectAuto,
                    onDisable = onDisable,
                    onSelectTrack = onSelectTrack
                )
            }
            item {
                TrackTypeRow(
                    title = "Субтитры",
                    trackType = C.TRACK_TYPE_TEXT,
                    options = textOptions,
                    groups = groups,
                    onSelectAuto = onSelectAuto,
                    onDisable = onDisable,
                    onSelectTrack = onSelectTrack
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackTypeRow(
    title: String,
    trackType: Int,
    options: List<PlayerTrackOption>,
    groups: List<Tracks.Group>,
    onSelectAuto: (Int) -> Unit,
    onDisable: (Int) -> Unit,
    onSelectTrack: (Int, Tracks.Group, Int) -> Unit
) {
    if (options.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { onSelectAuto(trackType) }) {
                Text("Авто")
            }
            OutlinedButton(onClick = { onDisable(trackType) }) {
                Text("Выкл")
            }
            options.forEach { option ->
                val group = groups.getOrNull(option.groupIndex) ?: return@forEach
                val buttonText = if (option.selected) {
                    "✓ ${option.label}"
                } else {
                    option.label
                }
                if (option.selected) {
                    Button(
                        onClick = { onSelectTrack(trackType, group, option.trackIndex) },
                        enabled = option.supported
                    ) {
                        Text(buttonText)
                    }
                } else {
                    OutlinedButton(
                        onClick = { onSelectTrack(trackType, group, option.trackIndex) },
                        enabled = option.supported
                    ) {
                        Text(buttonText)
                    }
                }
            }
        }
    }
}

@UnstableApi
private fun trackOptions(groups: List<Tracks.Group>, trackType: Int): List<PlayerTrackOption> {
    return groups.flatMapIndexed { groupIndex, group ->
        if (group.type != trackType) {
            emptyList()
        } else {
            (0 until group.length).map { trackIndex ->
                PlayerTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    label = formatTrackLabel(group.getTrackFormat(trackIndex), trackType, trackIndex),
                    selected = group.isTrackSelected(trackIndex),
                    supported = group.isTrackSupported(trackIndex)
                )
            }
        }
    }
}

@UnstableApi
private fun applySavedTrackPreferences(
    tracks: Tracks,
    selector: DefaultTrackSelector,
    preferences: Map<Int, PlayerTrackPreference>
) {
    if (tracks.groups.isEmpty() || preferences.isEmpty()) return

    val builder = selector.buildUponParameters()
    var changed = false
    preferences.forEach { (trackType, preference) ->
        when (preference.mode) {
            PlayerTrackPreferenceMode.AUTO -> {
                builder.setTrackTypeDisabled(trackType, false)
                    .clearOverridesOfType(trackType)
                changed = true
            }
            PlayerTrackPreferenceMode.DISABLED -> {
                builder.clearOverridesOfType(trackType)
                    .setTrackTypeDisabled(trackType, true)
                changed = true
            }
            PlayerTrackPreferenceMode.SELECTED -> {
                val candidate = PlayerTrackPreferenceMatcher.select(
                    preference = preference,
                    candidates = trackPreferenceCandidates(tracks.groups, trackType)
                ) ?: return@forEach
                val group = tracks.groups.getOrNull(candidate.groupIndex) ?: return@forEach
                builder.setTrackTypeDisabled(trackType, false)
                    .clearOverridesOfType(trackType)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, candidate.trackIndex))
                changed = true
            }
        }
    }
    if (changed) {
        selector.setParameters(builder)
    }
}

@UnstableApi
private fun trackPreferenceCandidates(
    groups: List<Tracks.Group>,
    trackType: Int
): List<PlayerTrackPreferenceCandidate> {
    return groups.flatMapIndexed { groupIndex, group ->
        if (group.type != trackType) {
            emptyList()
        } else {
            (0 until group.length).map { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                PlayerTrackPreferenceCandidate(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    language = format.language,
                    label = format.label,
                    supported = group.isTrackSupported(trackIndex)
                )
            }
        }
    }
}

@UnstableApi
private fun formatTrackLabel(format: Format, trackType: Int, trackIndex: Int): String {
    val language = format.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
    val label = format.label?.takeIf { it.isNotBlank() }
    val codec = format.codecs?.takeIf { it.isNotBlank() }
    val details = when (trackType) {
        C.TRACK_TYPE_VIDEO -> buildList {
            if (format.width > 0 && format.height > 0) add("${format.width}x${format.height}")
            if (format.bitrate > 0) add("${format.bitrate / 1000} kbps")
            codec?.let { add(it) }
        }
        C.TRACK_TYPE_AUDIO -> buildList {
            language?.let { add(it.uppercase(Locale.ROOT)) }
            if (format.channelCount > 0) add("${format.channelCount}ch")
            if (format.sampleRate > 0) add("${format.sampleRate / 1000} kHz")
            codec?.let { add(it) }
        }
        C.TRACK_TYPE_TEXT -> buildList {
            language?.let { add(it.uppercase(Locale.ROOT)) }
            codec?.let { add(it) }
        }
        else -> emptyList()
    }
    return listOfNotNull(label, details.joinToString(" | ").takeIf { it.isNotBlank() })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" | ")
        ?: "Дорожка ${trackIndex + 1}"
}

@UnstableApi
private fun inferMediaMimeType(url: String): String? {
    val lowered = url.substringBefore('|').lowercase(Locale.ROOT)
    val path = lowered.substringBefore('?').substringBefore('#')
    val query = lowered.substringAfter('?', "")
    return when {
        path.endsWith(".m3u8") ||
            path.endsWith(".m3u") ||
            query.contains("type=m3u8") ||
            query.contains("format=m3u8") ||
            query.contains("extension=m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mpd") || query.contains("type=mpd") -> MimeTypes.APPLICATION_MPD
        (path.contains("/manifest") && path.contains("ism")) ||
            path.endsWith(".ism") ||
            path.endsWith(".isml") -> MimeTypes.APPLICATION_SS
        lowered.startsWith("rtsp://") -> MimeTypes.APPLICATION_RTSP
        path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts") -> MimeTypes.VIDEO_MP2T
        path.endsWith(".flv") -> MimeTypes.VIDEO_FLV
        path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> MimeTypes.VIDEO_MP4
        path.endsWith(".mkv") -> MimeTypes.VIDEO_MATROSKA
        path.endsWith(".webm") -> MimeTypes.VIDEO_WEBM
        path.endsWith(".avi") -> MimeTypes.VIDEO_AVI
        path.endsWith(".mpg") || path.endsWith(".mpeg") || path.endsWith(".vob") -> MimeTypes.VIDEO_PS
        path.endsWith(".mp3") -> MimeTypes.AUDIO_MPEG
        path.endsWith(".aac") -> MimeTypes.AUDIO_AAC
        path.endsWith(".ac3") -> MimeTypes.AUDIO_AC3
        path.endsWith(".ec3") || path.endsWith(".eac3") -> MimeTypes.AUDIO_E_AC3
        path.endsWith(".ogg") || path.endsWith(".oga") -> MimeTypes.AUDIO_OGG
        path.endsWith(".wav") -> MimeTypes.AUDIO_WAV
        path.endsWith(".flac") -> MimeTypes.AUDIO_FLAC
        else -> null
    }
}

private fun compatibleHttpHeaders(headers: Map<String, String>): Map<String, String> {
    return buildMap {
        put("User-Agent", IPTV_USER_AGENT)
        put("Accept", "*/*")
        put("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                put(name, value)
            }
        }
    }
}

private fun healthPriority(health: ChannelHealth): Int {
    return when (health) {
        ChannelHealth.AVAILABLE -> 0
        ChannelHealth.UNSTABLE -> 1
        ChannelHealth.UNKNOWN -> 2
        ChannelHealth.UNAVAILABLE -> 3
    }
}

private fun channelMatchesGroup(
    channel: Channel,
    selectedGroup: String?,
    selectedSubGroup: String?
): Boolean {
    val groupRaw = channel.group?.trim().orEmpty()
    if (groupRaw.isEmpty()) {
        return selectedGroup == null && selectedSubGroup == null
    }
    val parts = groupRaw.split(Regex("\\s*(?:\\||/|>|::|\\\\\\\\)\\s*"), limit = 2)
    val group = parts.firstOrNull()?.trim().orEmpty()
    val subGroup = parts.getOrNull(1)?.trim().orEmpty()
    if (selectedGroup != null && group != selectedGroup) return false
    if (selectedSubGroup != null && subGroup != selectedSubGroup) return false
    return true
}

private fun formatPlaybackException(error: PlaybackException): String {
    val cause = error.cause
    val causeInfo = if (cause == null) {
        "-"
    } else {
        "${cause.javaClass.simpleName}:${cause.message.orEmpty().take(180)}"
    }
    return buildString {
        append(error.errorCodeName)
        val msg = error.message.orEmpty().trim()
        if (msg.isNotBlank()) {
            append(" | ")
            append(msg.take(220))
        }
        append(" | cause=")
        append(causeInfo)
    }
}

private fun formatEpgTime(epochMs: Long): String {
    return runCatching {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.timeZone = PLAYER_EPG_TIME_ZONE
        formatter.format(Date(epochMs))
    }.getOrDefault("--:--")
}

private fun formatPlayerLocalTime(epochMs: Long): String {
    return runCatching {
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        formatter.timeZone = PLAYER_EPG_TIME_ZONE
        formatter.format(Date(epochMs))
    }.getOrDefault("--:--")
}

private val PLAYER_EPG_TIME_ZONE: TimeZone = TimeZone.getTimeZone("Asia/Oral")
