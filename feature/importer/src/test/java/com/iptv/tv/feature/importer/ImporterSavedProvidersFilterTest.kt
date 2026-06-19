package com.iptv.tv.feature.importer

import com.iptv.tv.core.model.ProviderAccountStatus
import com.iptv.tv.core.model.ProviderAuthType
import com.iptv.tv.core.model.ProviderDiagnosticKind
import com.iptv.tv.core.model.ProviderType
import com.iptv.tv.core.model.PlaylistProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImporterSavedProvidersFilterTest {

    @Test
    fun filterSavedProviders_filtersByTypeAndIssueState() {
        val providers = listOf(
            SavedProviderCardModel(
                provider = provider(id = 1L, name = "Plex Home", type = ProviderType.PLEX, lastSyncedAt = 200L),
                status = null,
                history = emptyList()
            ),
            SavedProviderCardModel(
                provider = provider(id = 2L, name = "M3U World", type = ProviderType.M3U, lastSyncedAt = 300L),
                status = ProviderAccountStatus(
                    providerId = 2L,
                    type = ProviderType.M3U,
                    ok = false,
                    statusText = "Ошибка сети",
                    detail = "timeout",
                    checkedAt = 100L,
                    diagnosticKind = ProviderDiagnosticKind.NETWORK
                ),
                history = listOf(
                    ProviderSyncHistoryItem(
                        status = "provider_sync_item_error",
                        summary = "Ошибка: network",
                        createdAt = 100L,
                        isError = true
                    )
                )
            )
        )

        val filtered = filterSavedProviders(
            providers = providers,
            filterState = SavedProvidersFilterState(
                selectedType = ProviderType.M3U,
                issueOnly = true
            )
        )

        assertEquals(1, filtered.size)
        assertEquals(2L, filtered.first().provider.id)
    }

    @Test
    fun filterSavedProviders_filtersBySearchQuery() {
        val providers = listOf(
            SavedProviderCardModel(
                provider = provider(id = 1L, name = "Plex Home", type = ProviderType.PLEX, lastSyncedAt = 200L),
                status = null,
                history = emptyList()
            ),
            SavedProviderCardModel(
                provider = provider(id = 2L, name = "Family IPTV", type = ProviderType.M3U, lastSyncedAt = 300L),
                status = null,
                history = emptyList()
            )
        )

        val filtered = filterSavedProviders(
            providers = providers,
            filterState = SavedProvidersFilterState(query = "plex")
        )

        assertEquals(1, filtered.size)
        assertTrue(filtered.first().provider.name.contains("Plex"))
    }

    @Test
    fun bulkProviderIds_returnsVisibleUniqueIdsInOrder() {
        val providers = listOf(
            SavedProviderCardModel(
                provider = provider(id = 3L, name = "Third", type = ProviderType.M3U, lastSyncedAt = 300L),
                status = null,
                history = emptyList()
            ),
            SavedProviderCardModel(
                provider = provider(id = 1L, name = "First", type = ProviderType.PLEX, lastSyncedAt = 100L),
                status = null,
                history = emptyList()
            ),
            SavedProviderCardModel(
                provider = provider(id = 3L, name = "Third copy", type = ProviderType.M3U, lastSyncedAt = 200L),
                status = null,
                history = emptyList()
            )
        )

        assertEquals(listOf(3L, 1L), bulkProviderIds(providers))
    }

    private fun provider(
        id: Long,
        name: String,
        type: ProviderType,
        lastSyncedAt: Long?
    ): PlaylistProvider {
        return PlaylistProvider(
            id = id,
            type = type,
            name = name,
            baseUrl = "https://example.com/$id",
            username = null,
            password = null,
            token = null,
            macAddress = null,
            authType = ProviderAuthType.NONE,
            linkedPlaylistId = null,
            lastSyncedAt = lastSyncedAt,
            createdAt = id
        )
    }
}
