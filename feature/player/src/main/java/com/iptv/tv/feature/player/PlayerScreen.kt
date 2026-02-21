package com.iptv.tv.feature.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.player.toLoadControl
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val CHANNEL_QUICK_PANEL_LIMIT = 500

@Composable
@UnstableApi
@OptIn(ExperimentalLayoutApi::class)
fun PlayerScreen(
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Настройки",
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val filteredChannels = remember(state.channels, state.channelQuery, state.selectedGroup) {
        val query = state.channelQuery.trim().lowercase()
        val grouped = state.channels.filter { channel ->
            state.selectedGroup == null || channel.group?.trim() == state.selectedGroup
        }
        if (query.isBlank()) {
            grouped
        } else {
            grouped.filter { channel ->
                channel.name.lowercase().contains(query) ||
                    channel.group?.lowercase()?.contains(query) == true ||
                    channel.streamUrl.lowercase().contains(query)
            }
        }
    }
    var showTechnicalInfo by rememberSaveable { mutableStateOf(false) }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var showChannels by rememberSaveable { mutableStateOf(true) }
    var showActions by rememberSaveable { mutableStateOf(false) }
    var showStreamTools by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Управление: пульт (стрелки + OK + BACK), мышь (двойной клик по видео = развернуть/свернуть).",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Счетчики: плейлистов=${state.playlists.size} | " +
                    "каналов в текущем=${state.channels.size} | " +
                    "по фильтру=${filteredChannels.size} | избранных=${state.favoriteChannelIds.size}"
            )
            Text("Выбранный поток: ${state.selectedStreamKind}", style = MaterialTheme.typography.bodySmall)
            Text(state.epgStatus, style = MaterialTheme.typography.bodySmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { showActions = !showActions }) {
                    Text(if (showActions) "Скрыть действия" else "Показать действия")
                }
                OutlinedButton(onClick = { showStreamTools = !showStreamTools }) {
                    Text(if (showStreamTools) "Скрыть тест потока" else "Показать тест потока")
                }
                OutlinedButton(onClick = { showTechnicalInfo = !showTechnicalInfo }) {
                    Text(if (showTechnicalInfo) "Скрыть тех.инфо" else "Показать тех.инфо")
                }
                OutlinedButton(onClick = { showPlaylists = !showPlaylists }) {
                    Text(if (showPlaylists) "Свернуть плейлисты" else "Развернуть плейлисты")
                }
                OutlinedButton(onClick = { showChannels = !showChannels }) {
                    Text(if (showChannels) "Свернуть каналы" else "Развернуть каналы")
                }
            }
            if (showTechnicalInfo) {
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
                        Text("Размер плеера: ${if (state.internalPlayerExpanded) "увеличенный" else "стандарт"} | Масштаб: ${state.playerVideoScale}")
                        Text("Встроенный плеер: двойной клик по видео = полноэкранный/обычный режим.")
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
                            Button(onClick = { viewModel.setInternalPlayerExpanded(false) }) {
                                Text("Малый экран")
                            }
                            Button(onClick = { viewModel.setInternalPlayerExpanded(true) }) {
                                Text("Большой экран")
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

        state.internalSession?.let { session ->
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Сейчас играет: ${session.channelName}")
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val twoPane = maxWidth >= 900.dp && showChannels
                            if (twoPane) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 320.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1.15f)) {
                                        InternalPlayerHost(
                                            session = session,
                                            onReady = viewModel::onInternalPlaybackReady,
                                            onError = { message -> viewModel.onInternalPlaybackError(message, context) },
                                            scale = state.playerVideoScale,
                                            expanded = state.internalPlayerExpanded,
                                            onToggleExpanded = viewModel::toggleInternalPlayerSize,
                                            forceFullWidth = true
                                        )
                                    }
                                    ChannelQuickPanel(
                                        modifier = Modifier
                                            .weight(0.85f)
                                            .fillMaxHeight(),
                                        channels = filteredChannels,
                                        selectedChannelId = state.selectedChannelId,
                                        onSelect = viewModel::selectChannel,
                                        onPlay = viewModel::playChannelInternal
                                    )
                                }
                            } else {
                                InternalPlayerHost(
                                    session = session,
                                    onReady = viewModel::onInternalPlaybackReady,
                                    onError = { message -> viewModel.onInternalPlaybackError(message, context) },
                                    scale = state.playerVideoScale,
                                    expanded = state.internalPlayerExpanded,
                                    onToggleExpanded = viewModel::toggleInternalPlayerSize
                                )
                                if (showChannels) {
                                    ChannelQuickPanel(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp),
                                        channels = filteredChannels,
                                        selectedChannelId = state.selectedChannelId,
                                        onSelect = viewModel::selectChannel,
                                        onPlay = viewModel::playChannelInternal
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
                Text("Плейлисты (${state.playlists.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(state.playlists, key = { it.id }) { playlist ->
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("${playlist.name} (id=${playlist.id}, каналов=${playlist.channelCount})")
                        Button(onClick = { viewModel.selectPlaylist(playlist.id) }) {
                            Text(
                                if (playlist.id == state.selectedPlaylistId) {
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

        if (showChannels) {
            item {
                Text("Каналы (${filteredChannels.size}/${state.channels.size})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Подсказка: кнопка `Играть` сразу запускает выбранный канал во встроенном плеере.",
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
            if (filteredChannels.isEmpty()) {
                item {
                    Text("Нет каналов по текущему фильтру")
                }
            } else {
                items(filteredChannels, key = { it.id }) { channel ->
                    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(channel.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Группа: ${channel.group ?: "-"} | health=${channel.health}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (showTechnicalInfo) {
                                    Text("URL: ${channel.streamUrl}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(onClick = { viewModel.playChannelInternal(channel.id) }) {
                                    Text("Играть")
                                }
                                OutlinedButton(onClick = { viewModel.selectChannel(channel.id) }) {
                                    Text(
                                        if (channel.id == state.selectedChannelId) {
                                            "Выбран"
                                        } else {
                                            "Выбрать"
                                        }
                                    )
                                }
                            }
                        }
                    }
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
private fun ChannelQuickPanel(
    modifier: Modifier = Modifier,
    channels: List<Channel>,
    selectedChannelId: Long?,
    onSelect: (Long) -> Unit,
    onPlay: (Long) -> Unit
) {
    Card(modifier = modifier.tvFocusOutline()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Список каналов", style = MaterialTheme.typography.titleSmall)
            val limited = channels.take(CHANNEL_QUICK_PANEL_LIMIT)
            Text(
                "Прокрутка + OK: выбрать/играть. Показано: ${limited.size}${if (channels.size > limited.size) " из ${channels.size}" else ""}",
                style = MaterialTheme.typography.bodySmall
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(limited, key = { it.id }) { channel ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onSelect(channel.id) },
                            modifier = Modifier.weight(1f)
                        ) {
                            val mark = if (channel.id == selectedChannelId) "● " else ""
                            Text("$mark${channel.name}")
                        }
                        Button(onClick = { onPlay(channel.id) }) {
                            Text("Играть")
                        }
                    }
                }
            }
        }
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
    forceFullWidth: Boolean = false
) {
    val context = LocalContext.current
    val playerBuildResult = remember(session.sessionId, session.requestHeaders) {
        runCatching {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
                .setUserAgent("myscanerIPTV/0.1")
                .setDefaultRequestProperties(session.requestHeaders)
            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val renderersFactory = DefaultRenderersFactory(context)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
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
                if (playbackState == Player.STATE_READY) {
                    onReady()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    return
                }
                onError(formatPlaybackException(error))
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
            onError(throwable.message ?: throwable.javaClass.simpleName)
        }

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    val viewportModifier = if (forceFullWidth) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (expanded) 16f / 9f else 4f / 3f)
    } else if (expanded) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    } else {
        Modifier
            .fillMaxWidth(0.56f)
            .aspectRatio(4f / 3f)
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
    }
}

private fun inferMediaMimeType(url: String): String? {
    val lowered = url.lowercase()
    return when {
        lowered.contains(".m3u8") -> MimeTypes.APPLICATION_M3U8
        lowered.contains(".mpd") -> MimeTypes.APPLICATION_MPD
        lowered.contains("/manifest") && lowered.contains("ism") -> MimeTypes.APPLICATION_SS
        lowered.startsWith("rtsp://") -> MimeTypes.APPLICATION_RTSP
        else -> null
    }
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
        formatter.format(Date(epochMs))
    }.getOrDefault("--:--")
}

