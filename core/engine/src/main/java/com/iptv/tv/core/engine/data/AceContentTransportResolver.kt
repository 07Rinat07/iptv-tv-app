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

    data class AceLive(
        val metadata: AceTransportMetadata
    ) : AceContentTransportResolution

    data class Unsupported(
        val reason: String,
        val metadata: AceTransportMetadata
    ) : AceContentTransportResolution
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
            ?: "unknown"
        return AceContentTransportResolution.Unsupported(
            reason = "Ace content metadata is not a supported non-live BitTorrent transport: $transport",
            metadata = metadata
        )
    }
}
