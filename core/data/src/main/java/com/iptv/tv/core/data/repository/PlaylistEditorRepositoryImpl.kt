package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.domain.repository.PlaylistEditorRepository
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.EditorActionResult
import com.iptv.tv.core.model.EditorExportResult
import com.iptv.tv.core.model.PlaylistSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.lang.StringBuilder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistEditorRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val channelDao: ChannelDao
) : PlaylistEditorRepository {

    override suspend fun ensureEditablePlaylist(playlistId: Long): AppResult<EditorActionResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val context = ensureWorkingCopy(playlistId, emptyList())
                AppResult.Success(
                    EditorActionResult(
                        effectivePlaylistId = context.effectivePlaylistId,
                        affectedCount = 0,
                        createdWorkingCopy = context.createdWorkingCopy,
                        message = if (context.createdWorkingCopy) {
                            "Создана рабочая копия плейлиста"
                        } else {
                            "Плейлист уже редактируемый"
                        }
                    )
                )
            }.getOrElse { AppResult.Error("Не удалось подготовить рабочую копию", it) }
    }

    override suspend fun bulkHide(
        playlistId: Long,
        channelIds: List<Long>,
        hidden: Boolean
    ): AppResult<EditorActionResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (channelIds.isEmpty()) return@withContext AppResult.Error("Не выбраны каналы")
            val context = ensureWorkingCopy(playlistId, channelIds)
            if (context.selectedChannelIds.isEmpty()) return@withContext AppResult.Error("Не удалось сопоставить выбранные каналы")

            val affected = channelDao.setHidden(context.selectedChannelIds, hidden)
            AppResult.Success(
                EditorActionResult(
                    effectivePlaylistId = context.effectivePlaylistId,
                    affectedCount = affected,
                    createdWorkingCopy = context.createdWorkingCopy,
                    message = if (hidden) "Каналы скрыты" else "Каналы снова видимы"
                )
            )
        }.getOrElse { AppResult.Error("Не удалось изменить видимость каналов", it) }
    }

    override suspend fun bulkDelete(playlistId: Long, channelIds: List<Long>): AppResult<EditorActionResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (channelIds.isEmpty()) return@withContext AppResult.Error("Не выбраны каналы")
            val context = ensureWorkingCopy(playlistId, channelIds)
            if (context.selectedChannelIds.isEmpty()) return@withContext AppResult.Error("Не удалось сопоставить выбранные каналы")

            val affected = channelDao.deleteByIds(context.selectedChannelIds)
            normalizeOrder(context.effectivePlaylistId)

            AppResult.Success(
                EditorActionResult(
                    effectivePlaylistId = context.effectivePlaylistId,
                    affectedCount = affected,
                    createdWorkingCopy = context.createdWorkingCopy,
                    message = "Каналы удалены"
                )
            )
        }.getOrElse { AppResult.Error("Не удалось удалить каналы", it) }
    }

    override suspend fun deleteUnavailableChannels(playlistId: Long): AppResult<EditorActionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val context = ensureWorkingCopy(playlistId, emptyList())
            val affected = channelDao.deleteByHealth(context.effectivePlaylistId, ChannelHealth.UNAVAILABLE.name)
            normalizeOrder(context.effectivePlaylistId)

            AppResult.Success(
                EditorActionResult(
                    effectivePlaylistId = context.effectivePlaylistId,
                    affectedCount = affected,
                    createdWorkingCopy = context.createdWorkingCopy,
                    message = "Удалены недоступные каналы"
                )
            )
        }.getOrElse { AppResult.Error("Не удалось удалить недоступные каналы", it) }
    }

    override suspend fun moveChannelsToTop(playlistId: Long, channelIds: List<Long>): AppResult<EditorActionResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (channelIds.isEmpty()) return@withContext AppResult.Error("Не выбраны каналы")
            val context = ensureWorkingCopy(playlistId, channelIds)
            if (context.selectedChannelIds.isEmpty()) return@withContext AppResult.Error("Не удалось сопоставить выбранные каналы")

            val all = channelDao.getChannels(context.effectivePlaylistId).sortedBy { it.orderIndex }
            val selectedSet = context.selectedChannelIds.toSet()
            val selected = all.filter { it.id in selectedSet }
            val remaining = all.filter { it.id !in selectedSet }
            val reordered = selected + remaining
            applyOrder(reordered)

            AppResult.Success(
                EditorActionResult(
                    effectivePlaylistId = context.effectivePlaylistId,
                    affectedCount = selected.size,
                    createdWorkingCopy = context.createdWorkingCopy,
                    message = "Каналы перемещены вверх"
                )
            )
        }.getOrElse { AppResult.Error("Не удалось переместить каналы вверх", it) }
    }

    override suspend fun moveChannelsToBottom(playlistId: Long, channelIds: List<Long>): AppResult<EditorActionResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (channelIds.isEmpty()) return@withContext AppResult.Error("Не выбраны каналы")
            val context = ensureWorkingCopy(playlistId, channelIds)
            if (context.selectedChannelIds.isEmpty()) return@withContext AppResult.Error("Не удалось сопоставить выбранные каналы")

            val all = channelDao.getChannels(context.effectivePlaylistId).sortedBy { it.orderIndex }
            val selectedSet = context.selectedChannelIds.toSet()
            val selected = all.filter { it.id in selectedSet }
            val remaining = all.filter { it.id !in selectedSet }
            val reordered = remaining + selected
            applyOrder(reordered)

            AppResult.Success(
                EditorActionResult(
                    effectivePlaylistId = context.effectivePlaylistId,
                    affectedCount = selected.size,
                    createdWorkingCopy = context.createdWorkingCopy,
                    message = "Каналы перемещены вниз"
                )
            )
        }.getOrElse { AppResult.Error("Не удалось переместить каналы вниз", it) }
    }

    override suspend fun updateChannel(
        playlistId: Long,
        channelId: Long,
        name: String,
        group: String?,
        logo: String?,
        streamUrl: String
    ): AppResult<EditorActionResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (name.isBlank()) return@withContext AppResult.Error("Имя канала не может быть пустым")
            if (streamUrl.isBlank()) return@withContext AppResult.Error("URL канала не может быть пустым")

            val context = ensureWorkingCopy(playlistId, listOf(channelId))
            val mappedChannelId = context.selectedChannelIds.firstOrNull()
                ?: return@withContext AppResult.Error("Не удалось определить редактируемый канал")

            val affected = channelDao.updateChannelFields(
                channelId = mappedChannelId,
                name = name.trim(),
                groupName = group?.trim()?.ifEmpty { null },
                logo = logo?.trim()?.ifEmpty { null },
                streamUrl = streamUrl.trim()
            )

            AppResult.Success(
                EditorActionResult(
                    effectivePlaylistId = context.effectivePlaylistId,
                    affectedCount = affected,
                    createdWorkingCopy = context.createdWorkingCopy,
                    message = "Канал обновлён"
                )
            )
        }.getOrElse { AppResult.Error("Не удалось обновить канал", it) }
    }

    override suspend fun createCustomPlaylistFromChannels(
        name: String,
        channelIds: List<Long>
    ): AppResult<EditorActionResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (name.isBlank()) return@withContext AppResult.Error("Название плейлиста не задано")
            val distinctIds = channelIds.distinct()
            if (distinctIds.isEmpty()) return@withContext AppResult.Error("Не выбраны каналы")

            val channels = channelDao.findByIds(distinctIds)
            if (channels.isEmpty()) return@withContext AppResult.Error("Выбранные каналы не найдены")

            val channelsById = channels.associateBy { it.id }
            val ordered = distinctIds.mapNotNull { channelsById[it] }

            val customPlaylistId = playlistDao.insertPlaylist(
                PlaylistEntity(
                    name = name.trim(),
                    sourceType = PlaylistSourceType.CUSTOM.name,
                    source = "manual-builder",
                    scheduleHours = 0,
                    lastSyncedAt = null,
                    isCustom = true,
                    createdAt = System.currentTimeMillis()
                )
            )

            val cloned = ordered.mapIndexed { index, channel ->
                channel.copy(
                    id = 0,
                    playlistId = customPlaylistId,
                    orderIndex = index
                )
            }
            cloned.chunked(DB_INSERT_CHUNK).forEach { channelDao.insertAll(it) }

            AppResult.Success(
                EditorActionResult(
                    effectivePlaylistId = customPlaylistId,
                    affectedCount = cloned.size,
                    createdWorkingCopy = false,
                    message = "Создан пользовательский плейлист"
                )
            )
        }.getOrElse { AppResult.Error("Не удалось создать пользовательский плейлист", it) }
    }

    override suspend fun exportToM3u(playlistId: Long, channelIds: List<Long>): AppResult<EditorExportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val channels = if (channelIds.isEmpty()) {
                channelDao.getChannels(playlistId).filterNot { it.isHidden }
            } else {
                val selectedSet = channelIds.toSet()
                channelDao.getChannels(playlistId).filter { it.id in selectedSet }
            }

            if (channels.isEmpty()) return@withContext AppResult.Error("Нет каналов для экспорта")

            val m3u = buildM3u(channels)
            AppResult.Success(
                EditorExportResult(
                    playlistId = playlistId,
                    channelCount = channels.size,
                    m3uContent = m3u
                )
            )
        }.getOrElse { AppResult.Error("Не удалось экспортировать плейлист", it) }
    }

    private suspend fun ensureWorkingCopy(playlistId: Long, selectedChannelIds: List<Long>): WorkingContext {
        val playlist = playlistDao.findById(playlistId)
            ?: throw IllegalArgumentException("Плейлист с id=$playlistId не найден")

        if (playlist.isCustom) {
            return WorkingContext(
                effectivePlaylistId = playlistId,
                selectedChannelIds = selectedChannelIds.distinct(),
                createdWorkingCopy = false
            )
        }

        val sourceChannels = channelDao.getChannels(playlistId).sortedBy { it.orderIndex }
        val cowSource = "cow:$playlistId"
        val existingCow = playlistDao.findLatestCustomBySource(cowSource)

        if (existingCow != null) {
            val mappedSelected = mapSelectionToCow(
                sourceChannels = sourceChannels,
                selectedChannelIds = selectedChannelIds,
                existingCowPlaylistId = existingCow.id
            )
            return WorkingContext(
                effectivePlaylistId = existingCow.id,
                selectedChannelIds = mappedSelected,
                createdWorkingCopy = false
            )
        }

        val newPlaylistId = playlistDao.insertPlaylist(
            PlaylistEntity(
                name = "${playlist.name} (COW)",
                sourceType = PlaylistSourceType.CUSTOM.name,
                source = cowSource,
                scheduleHours = playlist.scheduleHours,
                lastSyncedAt = null,
                isCustom = true,
                createdAt = System.currentTimeMillis()
            )
        )

        val copied = sourceChannels.map {
            it.copy(
                id = 0,
                playlistId = newPlaylistId
            )
        }
        copied.chunked(DB_INSERT_CHUNK).forEach { channelDao.insertAll(it) }

        val mappedSelected = mapSelectionToCow(
            sourceChannels = sourceChannels,
            selectedChannelIds = selectedChannelIds,
            existingCowPlaylistId = newPlaylistId
        )

        return WorkingContext(
            effectivePlaylistId = newPlaylistId,
            selectedChannelIds = mappedSelected,
            createdWorkingCopy = true
        )
    }

    private suspend fun mapSelectionToCow(
        sourceChannels: List<ChannelEntity>,
        selectedChannelIds: List<Long>,
        existingCowPlaylistId: Long
    ): List<Long> {
        if (selectedChannelIds.isEmpty()) return emptyList()
        val copiedChannels = channelDao.getChannels(existingCowPlaylistId)
        val copiedByOrder = copiedChannels.associateBy { it.orderIndex }
        val sourceById = sourceChannels.associateBy { it.id }
        return selectedChannelIds.distinct().mapNotNull { sourceId ->
            sourceById[sourceId]?.orderIndex?.let { order -> copiedByOrder[order]?.id }
        }
    }

    private suspend fun normalizeOrder(playlistId: Long) {
        val channels = channelDao.getChannels(playlistId).sortedBy { it.orderIndex }
        applyOrder(channels)
    }

    private suspend fun applyOrder(channels: List<ChannelEntity>) {
        channels.forEachIndexed { index, channel ->
            if (channel.orderIndex != index) {
                channelDao.updateOrderIndex(channel.id, index)
            }
        }
    }

    private fun buildM3u(channels: List<ChannelEntity>): String {
        val builder = StringBuilder("#EXTM3U\n")
        channels.sortedBy { it.orderIndex }.forEach { channel ->
            val attrs = mutableListOf<String>()
            channel.tvgId?.takeIf { it.isNotBlank() }?.let { attrs += "tvg-id=\"${escapeAttr(it)}\"" }
            channel.logo?.takeIf { it.isNotBlank() }?.let { attrs += "tvg-logo=\"${escapeAttr(it)}\"" }
            channel.groupName?.takeIf { it.isNotBlank() }?.let { attrs += "group-title=\"${escapeAttr(it)}\"" }

            val attrsChunk = if (attrs.isEmpty()) "" else " ${attrs.joinToString(" ")}"
            builder.append("#EXTINF:-1")
                .append(attrsChunk)
                .append(',')
                .append(channel.name)
                .append('\n')
            builder.append(channel.streamUrl).append('\n')
        }
        return builder.toString()
    }

    private fun escapeAttr(value: String): String {
        return value
            .trim()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private data class WorkingContext(
        val effectivePlaylistId: Long,
        val selectedChannelIds: List<Long>,
        val createdWorkingCopy: Boolean
    )

    private companion object {
        const val DB_INSERT_CHUNK = 500
    }
}
