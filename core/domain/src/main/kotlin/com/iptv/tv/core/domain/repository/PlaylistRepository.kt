package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.Playlist
import kotlinx.coroutines.flow.Flow

interface PlaylistRepository {
    fun observePlaylists(): Flow<List<Playlist>>
    fun observeChannels(playlistId: Long): Flow<List<Channel>>
    suspend fun importFromUrl(url: String, name: String): AppResult<PlaylistImportReport>
    suspend fun importFromText(text: String, name: String): AppResult<PlaylistImportReport>
    suspend fun importFromFile(pathOrUri: String, name: String): AppResult<PlaylistImportReport>
    suspend fun validatePlaylist(playlistId: Long): AppResult<PlaylistValidationReport>
    suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit>
    suspend fun refreshAllPlaylists(): AppResult<Int>
    suspend fun deletePlaylist(playlistId: Long): AppResult<Int>
    suspend fun getChannelById(channelId: Long): AppResult<Channel>
    suspend fun getChannelEpgNowNext(channelId: Long): AppResult<ChannelEpgInfo>
}
