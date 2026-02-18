package com.iptv.tv.core.domain.usecase

import com.iptv.tv.core.domain.repository.ScannerRepository
import com.iptv.tv.core.model.ScannerSearchRequest

class SearchPlaylistsUseCase(
    private val scannerRepository: ScannerRepository
) {
    suspend operator fun invoke(request: ScannerSearchRequest) = scannerRepository.search(request)
}
