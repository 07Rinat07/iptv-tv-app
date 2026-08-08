from pathlib import Path

path = Path('core/data/src/main/java/com/iptv/tv/core/data/repository/HybridEngineRepositoryImpl.kt')
text = path.read_text()

text = text.replace(
    'import com.iptv.tv.core.engine.data.EngineStreamClient\n',
    'import com.iptv.tv.core.engine.data.AceContentTransportResolution\n'
    'import com.iptv.tv.core.engine.data.AceContentTransportResolver\n'
    'import com.iptv.tv.core.engine.data.EngineStreamClient\n',
    1,
)

text = text.replace(
    '    private val client: EngineStreamClient,\n'
    '    private val syncLogDao: SyncLogDao,\n',
    '    private val client: EngineStreamClient,\n'
    '    private val contentTransportResolver: AceContentTransportResolver,\n'
    '    private val syncLogDao: SyncLogDao,\n',
    1,
)

start = text.index('    /**\n     * A pure Ace content id is not a BitTorrent hash.')
end = text.index('    private fun isPureAceContentId', start)
new = '''    /**
     * A pure Ace content id is not a BitTorrent hash. Transport discovery is delegated to the
     * resolver boundary so playback routing no longer depends directly on an external Ace Engine.
     * Only a proven non-live BitTorrent transport may enter standard libtorrent; live/unsupported
     * transports and resolver failures keep the existing external compatibility fallback.
     */
    private suspend fun resolveAceContentIdWithEmbeddedMetadata(
        rawSource: String,
        epoch: Long
    ): AppResult<String> {
        stopEmbeddedForEpoch(epoch)
        if (streamEpoch.get() != epoch) return supersededResult()

        val resolution = contentTransportResolver.resolve(rawSource)
        if (streamEpoch.get() != epoch) return supersededResult()

        val infoHash = when (resolution) {
            is AppResult.Success -> when (val transport = resolution.data) {
                is AceContentTransportResolution.EmbeddedBitTorrent -> transport.infoHash
                is AceContentTransportResolution.AceLive -> {
                    log(
                        "engine_content_id_live_transport",
                        "Ace content id resolved to live transport; using external compatibility fallback"
                    )
                    return resolveExternalIfCurrent(rawSource, epoch)
                }
                is AceContentTransportResolution.Unsupported -> {
                    log("engine_content_id_transport_unsupported", transport.reason)
                    return resolveExternalIfCurrent(rawSource, epoch)
                }
            }
            is AppResult.Error -> {
                log("engine_content_id_metadata_error", resolution.message)
                return resolveExternalIfCurrent(rawSource, epoch)
            }
            AppResult.Loading -> return resolveExternalIfCurrent(rawSource, epoch)
        }

        log(
            "engine_content_id_metadata_resolved",
            "Ace content id mapped to proven non-live BitTorrent infohash $infoHash"
        )

        embeddedEngineUsed.set(true)
        val embedded = embeddedEngine.prepareStream(P2pSource.InfoHash(infoHash))
        if (streamEpoch.get() != epoch) return supersededResult()

        return when (embedded) {
            is P2pResult.Success -> {
                log(
                    "embedded_p2p_content_id_resolved",
                    "Embedded BitTorrent stream prepared from Ace content metadata: ${embedded.data.file.name}"
                )
                AppResult.Success(embedded.data.url)
            }
            is P2pResult.Error -> {
                log("embedded_p2p_content_id_error", embedded.message)
                resolveExternalIfCurrent(rawSource, epoch)
            }
        }
    }

    private suspend fun resolveExternalIfCurrent(
        rawSource: String,
        epoch: Long
    ): AppResult<String> {
        if (streamEpoch.get() != epoch) return supersededResult()
        val fallback = resolveExternal(rawSource)
        return if (streamEpoch.get() == epoch) fallback else supersededResult()
    }

'''
text = text[:start] + new + text[end:]
path.write_text(text)
