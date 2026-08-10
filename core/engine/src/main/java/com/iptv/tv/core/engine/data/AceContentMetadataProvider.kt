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
 *
 * Endpoint discovery failures are promoted to a typed resolver-capability error. Callers can then
 * distinguish "no Ace content-id resolver exists" from a bad descriptor/metadata response and
 * avoid repeatedly probing the same missing external engine.
 */
class ExternalEngineAceContentMetadataProvider(
    private val client: EngineStreamClient
) : AceContentMetadataProvider {
    override suspend fun resolve(rawSource: String): AppResult<AceTransportMetadata> {
        val result = client.resolveContentIdMetadata(rawSource)
        if (result !is AppResult.Error) return result

        val runtime = client.observeRuntimeDiagnostics().value
        if (runtime.failureCode != FAILURE_ENDPOINT_UNAVAILABLE) return result

        val cause = AceContentIdResolverUnavailableException(
            message = AceContentIdResolverUnavailableException.DEFAULT_MESSAGE,
            cause = result.cause
        )
        return AppResult.Error(cause.message ?: result.message, cause)
    }

    private companion object {
        const val FAILURE_ENDPOINT_UNAVAILABLE = "endpoint_unavailable"
    }
}
