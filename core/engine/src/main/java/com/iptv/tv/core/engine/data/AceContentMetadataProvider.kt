package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult

/**
 * Resolves Ace content descriptors into transport metadata without exposing the concrete backend.
 *
 * Autonomous/public-protocol providers can be inserted before the external-engine compatibility
 * provider without changing player-facing routing or transport classification.
 */
interface AceContentMetadataProvider {
    suspend fun resolve(rawSource: String): AppResult<AceTransportMetadata>
}

/**
 * Ordered provider chain. The first successful provider wins; errors fall through to the next
 * provider so an autonomous resolver can coexist with the external Ace Engine during migration.
 */
class ChainedAceContentMetadataProvider(
    private val providers: List<AceContentMetadataProvider>
) : AceContentMetadataProvider {
    override suspend fun resolve(rawSource: String): AppResult<AceTransportMetadata> {
        if (providers.isEmpty()) {
            return AppResult.Error("No Ace content metadata providers configured")
        }

        var lastError: AppResult.Error? = null
        for (provider in providers) {
            when (val result = provider.resolve(rawSource)) {
                is AppResult.Success -> return result
                is AppResult.Error -> lastError = result
                AppResult.Loading -> return AppResult.Loading
            }
        }

        return lastError ?: AppResult.Error("Ace content metadata resolution failed")
    }
}

/**
 * Compatibility metadata backend using the installed Ace Engine public `get_media_files` API.
 */
class ExternalEngineAceContentMetadataProvider(
    private val client: EngineStreamClient
) : AceContentMetadataProvider {
    override suspend fun resolve(rawSource: String): AppResult<AceTransportMetadata> =
        client.resolveContentIdMetadata(rawSource)
}
