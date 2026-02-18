package com.iptv.tv.core.domain.usecase

import com.iptv.tv.core.domain.repository.PlaylistRepository

class ImportPlaylistFromUrlUseCase(
    private val playlistRepository: PlaylistRepository
) {
    suspend operator fun invoke(url: String, name: String) = playlistRepository.importFromUrl(url, name)
}
