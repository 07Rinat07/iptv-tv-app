package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult

/**
 * Resolves an Ace content id into a transport decision without forcing callers to depend on the
 * external Ace service implementation. A future in-process resolver can implement the same
 * contract and be inserted ahead of the compatibility resolver.
 */
interface AceContentTransportResolver {
    suspend fun resolve(rawSource: String): AppResult<AceContentTransportResolution>
}

sealed interface AceContentTransportResolution {
    data class EmbeddedBitTorrent(
        val infoHash: String,
        val metadata: AceTransportMetadata
    ) : AceContentTransportResolution

    data class EmbeddedTorrentFile(
        val transportFileDataBase64: String,
        val metadata: AceTransportMetadata
    ) : AceContentTransportResolution

    data class AceLive(
        val metadata: AceTransportMetadata
    ) : AceContentTransportResolution

    data class Unsupported(
        val reason: String,
        val metadata: AceTransportMetadata
    ) : AceContentTransportResolution
}

/**
 * Compatibility recovery wrapper for Android installations where the Ace HTTP engine is already
 * listening on the conventional loopback endpoint but its bound service is unavailable or hidden.
 *
 * The normal resolver is attempted first so a healthy bound-service connection and its access
 * token remain authoritative. Only an error triggers a direct loopback status probe and one retry.
 * This does not make pure content-id resolution autonomous: the loopback process is still an
 * external Ace Engine compatibility backend.
 */
class LoopbackFirstAceContentTransportResolver(
    private val client: EngineStreamClient,
    private val delegate: AceContentTransportResolver,
    private val loopbackEndpoint: String = DEFAULT_LOOPBACK_ENDPOINT
) : AceContentTransportResolver {
    override suspend fun resolve(rawSource: String): AppResult<AceContentTransportResolution> {
        val primary = delegate.resolve(rawSource)
        if (primary !is AppResult.Error) return primary

        val loopback = client.connect(loopbackEndpoint)
        if (loopback !is AppResult.Success) return primary

        return delegate.resolve(rawSource)
    }

    private companion object {
        const val DEFAULT_LOOPBACK_ENDPOINT = "http://127.0.0.1:6878"
    }
}

/**
 * Current compatibility resolver backed by the external Ace Engine metadata API.
 *
 * This class intentionally exposes the dependency boundary: successful non-live BitTorrent
 * metadata may be handed to the embedded libtorrent backend, while live/wrapper/HLS transports
 * stay outside standard libtorrent. Replacing this resolver with an in-process implementation is
 * therefore isolated from player-facing routing.
 */
class ExternalAceContentTransportResolver(
    private val client: EngineStreamClient
) : AceContentTransportResolver {
    override suspend fun resolve(rawSource: String): AppResult<AceContentTransportResolution> {
        return when (val result = client.resolveContentIdMetadata(rawSource)) {
            is AppResult.Success -> AppResult.Success(classify(result.data))
            is AppResult.Error -> result
            AppResult.Loading -> AppResult.Loading
        }
    }

    internal fun classify(metadata: AceTransportMetadata): AceContentTransportResolution {
        if (metadata.isLive) {
            return AceContentTransportResolution.AceLive(metadata)
        }

        val infoHash = metadata.embeddedBitTorrentInfoHash
        if (infoHash != null) {
            return AceContentTransportResolution.EmbeddedBitTorrent(
                infoHash = infoHash,
                metadata = metadata
            )
        }

        val transport = metadata.transportType
            ?: metadata.files.firstNotNullOfOrNull { it.transportType }
        val transportFileData = metadata.transportFileData?.takeIf { it.isNotBlank() }
        if (transportFileData != null && (transport == null || transport == "bt")) {
            return AceContentTransportResolution.EmbeddedTorrentFile(
                transportFileDataBase64 = transportFileData,
                metadata = metadata
            )
        }

        return AceContentTransportResolution.Unsupported(
            reason = "Ace content metadata is not a supported non-live BitTorrent transport: ${transport ?: "unknown"}",
            metadata = metadata
        )
    }
}
