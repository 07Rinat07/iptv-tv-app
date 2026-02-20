package com.iptv.tv.feature.player

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.remember
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
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.player.toLoadControl
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            Text("Effective player: ${state.effectivePlayer} | default: ${state.defaultPlayer} | override: ${state.channelPlayerOverride ?: "default"}")
            Text("Buffer: ${state.bufferProfile} | manual=${state.manualBuffer}")
            Text("Engine: connected=${state.engineConnected}, peers=${state.enginePeers}, speed=${state.engineSpeedKbps} kbps")
            Text("Engine endpoint: ${state.engineEndpoint} | Tor=${state.torEnabled}")
            Text("Engine message: ${state.engineMessage}")
            Text("Размер плеера: ${if (state.internalPlayerExpanded) "увеличенный" else "стандарт"} | Масштаб: ${state.playerVideoScale}")
            Text("Встроенный плеер: двойной клик по видео = полноэкранный/обычный режим.")
            Text("VLC: сначала запускается прямой fullscreen-режим (если поддерживается), иначе режим совместимости.")
            Text("Возврат из VLC обратно в приложение: кнопка Back/Назад.")
            if (state.torEnabled) {
                Text("Tor режим активен: для работы нужен локально запущенный Tor/Orbot.")
            }
            Text("Тип выбранного потока: ${state.selectedStreamKind}")
            val aceDescriptorLabel = state.selectedAceDescriptor?.let { descriptor ->
                if (descriptor.length > 110) "${descriptor.take(110)}..." else descriptor
            } ?: "не обнаружен"
            Text("Ace-дескриптор: $aceDescriptorLabel")
            Text(state.epgStatus)
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

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text("Остановить встроенный плеер")
                }
                Button(onClick = { viewModel.setInternalPlayerExpanded(false) }) {
                    Text("Малый экран")
                }
                Button(onClick = { viewModel.setInternalPlayerExpanded(true) }) {
                    Text("Большой экран")
                }
                Button(onClick = viewModel::cycleVideoScale) {
                    Text("Режим кадра: ${state.playerVideoScale}")
                }
                Button(onClick = { viewModel.setChannelOverride(PlayerType.INTERNAL) }) {
                    Text("Override канала: Internal")
                }
                Button(onClick = { viewModel.setChannelOverride(PlayerType.VLC) }) {
                    Text("Override канала: VLC")
                }
                Button(onClick = viewModel::clearChannelOverride) {
                    Text("Override канала: Default")
                }
                Button(onClick = viewModel::toggleSelectedFavorite, enabled = state.selectedChannelId != null) {
                    val isFavorite = state.selectedChannelId?.let { id -> state.favoriteChannelIds.contains(id) } == true
                    Text(if (isFavorite) "Убрать из избранного" else "Добавить в избранное")
                }
                Button(onClick = { viewModel.installVlc(context) }) {
                    Text("Установить VLC")
                }
            }
        }

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
                        InternalPlayerHost(
                            session = session,
                            onReady = viewModel::onInternalPlaybackReady,
                            onError = { message -> viewModel.onInternalPlaybackError(message, context) },
                            scale = state.playerVideoScale,
                            expanded = state.internalPlayerExpanded,
                            onToggleExpanded = viewModel::toggleInternalPlayerSize
                        )
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

        item {
            Text("Плейлисты", style = MaterialTheme.typography.titleMedium)
        }
        items(state.playlists, key = { it.id }) { playlist ->
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("${playlist.name} (id=${playlist.id})")
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

        item {
            Text("Каналы", style = MaterialTheme.typography.titleMedium)
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
                Text("Группы каналов", style = MaterialTheme.typography.titleSmall)
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
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(channel.name, style = MaterialTheme.typography.titleSmall)
                        Text("Group: ${channel.group ?: "-"} | health=${channel.health}")
                        Text("EPG id: ${channel.tvgId ?: "-"}")
                        Text("Logo: ${channel.logo ?: "-"}")
                        Text("URL: ${channel.streamUrl}")
                        Button(onClick = { viewModel.selectChannel(channel.id) }) {
                            Text(
                                if (channel.id == state.selectedChannelId) {
                                    "Канал выбран"
                                } else {
                                    "Выбрать канал"
                                }
                            )
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

@UnstableApi
@Composable
private fun InternalPlayerHost(
    session: InternalPlaybackSession,
    onReady: () -> Unit,
    onError: (String) -> Unit,
    scale: PlayerVideoScale,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
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

    val viewportModifier = if (expanded) {
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
    } else {
        Modifier
            .fillMaxWidth(0.56f)
            .aspectRatio(16f / 9f)
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

