package com.iptv.tv.core.data.repository

internal enum class TvHomeProviderChannelAction {
    KEEP_EXISTING,
    REUSE_DISCOVERED,
    INSERT_NEW
}

internal object TvHomeProviderChannelRecovery {
    fun decide(
        existingProviderChannelId: Long?,
        existingUpdateRows: Int?,
        discoveredProviderChannelId: Long?,
        discoveredUpdateRows: Int?
    ): TvHomeProviderChannelAction {
        if (existingProviderChannelId != null && existingUpdateRows != null && existingUpdateRows > 0) {
            return TvHomeProviderChannelAction.KEEP_EXISTING
        }
        if (discoveredProviderChannelId != null && discoveredUpdateRows != null && discoveredUpdateRows > 0) {
            return TvHomeProviderChannelAction.REUSE_DISCOVERED
        }
        return TvHomeProviderChannelAction.INSERT_NEW
    }
}
