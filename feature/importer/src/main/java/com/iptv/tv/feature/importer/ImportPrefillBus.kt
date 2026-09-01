package com.iptv.tv.feature.importer

import com.iptv.tv.core.model.CatalogOriginKind
import java.util.concurrent.atomic.AtomicReference

data class ImportPrefill(
    val url: String,
    val playlistName: String,
    val autoImport: Boolean,
    val catalogOrigin: CatalogOriginKind = CatalogOriginKind.USER_IMPORT
)

object BuiltInPlaylistSources {
    const val FREE_TV_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    const val FREE_TV_NAME = "Free-TV"

    fun freeTvPrefill(): ImportPrefill = ImportPrefill(
        url = FREE_TV_URL,
        playlistName = FREE_TV_NAME,
        autoImport = false,
        catalogOrigin = CatalogOriginKind.USER_IMPORT
    )
}

object ImportPrefillBus {
    private val pending = AtomicReference<ImportPrefill?>(BuiltInPlaylistSources.freeTvPrefill())

    fun push(prefill: ImportPrefill) {
        pending.set(prefill)
    }

    fun consume(): ImportPrefill? = pending.getAndSet(null)
}
