package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.PlaylistCandidate
import com.iptv.tv.core.model.ScannerSearchRequest

interface ScannerRepository {
    suspend fun search(request: ScannerSearchRequest): AppResult<List<PlaylistCandidate>>
}
