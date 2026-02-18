package com.iptv.tv.feature.importer

import java.util.concurrent.atomic.AtomicReference

data class ImportPrefill(
    val url: String,
    val playlistName: String,
    val autoImport: Boolean
)

object ImportPrefillBus {
    private val pending = AtomicReference<ImportPrefill?>(null)

    fun push(prefill: ImportPrefill) {
        pending.set(prefill)
    }

    fun consume(): ImportPrefill? = pending.getAndSet(null)
}
