package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toEntity
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.database.dao.ChannelDao
import com.iptv.tv.core.database.dao.ChannelMetadataDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.dao.SyncLogDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.domain.repository.ChannelMetadataRepository
import com.iptv.tv.core.model.ChannelMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelMetadataRepositoryImpl @Inject constructor(
    private val channelMetadataDao: ChannelMetadataDao,
    private val channelDao: ChannelDao,
    private val playlistDao: PlaylistDao,
    private val syncLogDao: SyncLogDao,
    private val logoCatalogResolver: LogoCatalogResolver = LogoCatalogResolver()
) : ChannelMetadataRepository {
    override suspend fun resolveMetadata(channelId: Long): AppResult<ChannelMetadata?> = withContext(Dispatchers.IO) {
        val existing = channelMetadataDao.findByChannelId(channelId)
        if (existing != null) return@withContext AppResult.Success(existing.toModel())
        val channel = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Канал не найден: id=$channelId")
        val metadata = buildMetadata(channel, existing = null)
        channelMetadataDao.upsert(metadata.toEntity())
        AppResult.Success(metadata)
    }

    override suspend fun setManualLogo(channelId: Long, logoUrl: String?): AppResult<Int> = withContext(Dispatchers.IO) {
        val channel = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Канал не найден: id=$channelId")
        val normalizedLogo = logoUrl?.trim()?.ifBlank { null }
        val existing = channelMetadataDao.findByChannelId(channelId)?.toModel()
        val baseMetadata = buildMetadata(
            channel = channel,
            existing = if (normalizedLogo == null) existing?.copy(manualLogoUrl = null) else existing
        )
        val metadata = baseMetadata.copy(
            manualLogoUrl = normalizedLogo,
            resolvedLogoUrl = normalizedLogo ?: baseMetadata.resolvedLogoUrl,
            metadataSource = if (normalizedLogo != null) "manual" else baseMetadata.metadataSource,
            updatedAt = System.currentTimeMillis()
        )
        channelMetadataDao.upsert(metadata.toEntity())
        val updated = channelDao.updateChannelFields(
            channelId = channel.id,
            name = channel.name,
            groupName = channel.groupName,
            logo = normalizedLogo ?: metadata.resolvedLogoUrl,
            streamUrl = channel.streamUrl
        )
        addLog("metadata_manual_logo", "channelId=${channel.id}, updated=$updated")
        AppResult.Success(updated)
    }

    override suspend fun refreshMetadata(playlistId: Long): AppResult<Int> = withContext(Dispatchers.IO) {
        refreshMetadataInternal(
            playlistId = playlistId,
            resolver = logoCatalogResolver,
            logStatus = "metadata_refresh",
            logPrefix = ""
        )
    }

    override suspend fun refreshMetadataWithLogoPack(
        playlistId: Long,
        logoPackJson: String
    ): AppResult<Int> = withContext(Dispatchers.IO) {
        val normalizedPack = logoPackJson.trim()
        if (normalizedPack.isBlank()) {
            return@withContext AppResult.Error("Logo pack JSON пуст")
        }
        refreshMetadataInternal(
            playlistId = playlistId,
            resolver = LogoCatalogResolver(baseEntries = emptyList(), packJson = normalizedPack),
            logStatus = "metadata_external_logo_pack",
            logPrefix = "externalLogoPack=true, "
        )
    }

    private suspend fun refreshMetadataInternal(
        playlistId: Long,
        resolver: LogoCatalogResolver,
        logStatus: String,
        logPrefix: String
    ): AppResult<Int> {
        val playlist = playlistDao.findById(playlistId)
            ?: return AppResult.Error("Плейлист не найден: id=$playlistId")
        val channels = channelDao.getChannels(playlistId)
        val existingByChannel = channelMetadataDao.findByChannelIds(channels.map { it.id })
            .associateBy { it.channelId }
        var changedLogos = 0
        channels.forEach { channel ->
            val metadata = buildMetadata(
                channel = channel,
                existing = existingByChannel[channel.id]?.toModel(),
                playlistSource = playlist.source,
                resolver = resolver
            )
            channelMetadataDao.upsert(metadata.toEntity())
            val effectiveLogo = metadata.manualLogoUrl ?: channel.logo ?: metadata.resolvedLogoUrl
            if (!effectiveLogo.isNullOrBlank() && effectiveLogo != channel.logo) {
                channelDao.updateChannelFields(
                    channelId = channel.id,
                    name = channel.name,
                    groupName = channel.groupName,
                    logo = effectiveLogo,
                    streamUrl = channel.streamUrl
                )
                changedLogos += 1
            }
        }
        addLog(
            status = logStatus,
            message = "${logPrefix}playlistId=$playlistId, channels=${channels.size}, changedLogos=$changedLogos"
        )
        return AppResult.Success(changedLogos)
    }

    private fun buildMetadata(
        channel: ChannelEntity,
        existing: ChannelMetadata?,
        playlistSource: String? = null,
        resolver: LogoCatalogResolver = logoCatalogResolver
    ): ChannelMetadata {
        val inferred = inferMetadata(channel, playlistSource)
        val catalogMatch = resolver.resolve(
            name = channel.name,
            tvgId = channel.tvgId,
            playlistSource = playlistSource
        )
        val resolvedLogo = existing?.manualLogoUrl ?: channel.logo ?: catalogMatch?.url
        return ChannelMetadata(
            channelId = channel.id,
            normalizedName = normalizeName(channel.name),
            country = existing?.country ?: catalogMatch?.entry?.country ?: inferred.country,
            language = existing?.language ?: catalogMatch?.entry?.language ?: inferred.language,
            category = existing?.category ?: catalogMatch?.entry?.category ?: inferred.category,
            resolvedLogoUrl = resolvedLogo,
            manualLogoUrl = existing?.manualLogoUrl,
            metadataSource = when {
                existing?.manualLogoUrl != null -> "manual"
                !channel.logo.isNullOrBlank() -> "playlist"
                catalogMatch != null -> catalogMatch.source
                else -> existing?.metadataSource
            },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun inferMetadata(channel: ChannelEntity, playlistSource: String?): InferredMetadata {
        val source = listOfNotNull(channel.tvgId, channel.groupName, channel.name, playlistSource)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val country = COUNTRY_HINTS.firstOrNull { (hint, _) -> source.contains(hint) }?.second
        val language = LANGUAGE_HINTS.firstOrNull { (hint, _) -> source.contains(hint) }?.second
        val category = CATEGORY_HINTS.firstOrNull { (hint, _) -> source.contains(hint) }?.second ?: channel.groupName
        return InferredMetadata(country, language, category)
    }

    private suspend fun addLog(status: String, message: String) {
        syncLogDao.insert(
            SyncLogEntity(
                playlistId = null,
                status = status,
                message = message,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private data class InferredMetadata(
        val country: String?,
        val language: String?,
        val category: String?
    )

    private companion object {
        val COUNTRY_HINTS = listOf(
            ".us" to "US",
            ".uk" to "UK",
            ".fr" to "FR",
            ".de" to "DE",
            ".ru" to "RU",
            ".kz" to "KZ",
            "usa" to "US",
            "united kingdom" to "UK",
            "russia" to "RU",
            "kazakhstan" to "KZ"
        )
        val LANGUAGE_HINTS = listOf(
            " english " to "en",
            " russian " to "ru",
            " kazakh " to "kk",
            " deutsch " to "de",
            " french " to "fr",
            " рус" to "ru",
            " каз" to "kk"
        )
        val CATEGORY_HINTS = listOf(
            "news" to "News",
            "sport" to "Sports",
            "movie" to "Movies",
            "music" to "Music",
            "kids" to "Kids",
            "children" to "Kids",
            "documentary" to "Documentary",
            "новост" to "News",
            "спорт" to "Sports",
            "кино" to "Movies",
            "дет" to "Kids"
        )

        fun normalizeName(value: String): String {
            return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
        }
    }
}
