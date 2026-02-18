package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.EditorActionResult
import com.iptv.tv.core.model.EditorExportResult

interface PlaylistEditorRepository {
    suspend fun ensureEditablePlaylist(playlistId: Long): AppResult<EditorActionResult>
    suspend fun bulkHide(playlistId: Long, channelIds: List<Long>, hidden: Boolean): AppResult<EditorActionResult>
    suspend fun bulkDelete(playlistId: Long, channelIds: List<Long>): AppResult<EditorActionResult>
    suspend fun deleteUnavailableChannels(playlistId: Long): AppResult<EditorActionResult>
    suspend fun moveChannelsToTop(playlistId: Long, channelIds: List<Long>): AppResult<EditorActionResult>
    suspend fun moveChannelsToBottom(playlistId: Long, channelIds: List<Long>): AppResult<EditorActionResult>
    suspend fun updateChannel(
        playlistId: Long,
        channelId: Long,
        name: String,
        group: String?,
        logo: String?,
        streamUrl: String
    ): AppResult<EditorActionResult>

    suspend fun createCustomPlaylistFromChannels(name: String, channelIds: List<Long>): AppResult<EditorActionResult>
    suspend fun exportToM3u(playlistId: Long, channelIds: List<Long>): AppResult<EditorExportResult>
}
