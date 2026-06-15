package com.iptv.tv.core.data.repository

import android.net.Uri
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
    private val syncLogDao: SyncLogDao
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
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Плейлист не найден: id=$playlistId")
        val channels = channelDao.getChannels(playlistId)
        val existingByChannel = channelMetadataDao.findByChannelIds(channels.map { it.id })
            .associateBy { it.channelId }
        var changedLogos = 0
        channels.forEach { channel ->
            val metadata = buildMetadata(channel, existingByChannel[channel.id]?.toModel(), playlist.source)
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
            status = "metadata_refresh",
            message = "playlistId=$playlistId, channels=${channels.size}, changedLogos=$changedLogos"
        )
        AppResult.Success(changedLogos)
    }

    private fun buildMetadata(
        channel: ChannelEntity,
        existing: ChannelMetadata?,
        playlistSource: String? = null
    ): ChannelMetadata {
        val inferred = inferMetadata(channel, playlistSource)
        val catalogLogo = resolveLogo(channel, playlistSource)
        val resolvedLogo = existing?.manualLogoUrl ?: channel.logo ?: catalogLogo
        return ChannelMetadata(
            channelId = channel.id,
            normalizedName = normalizeName(channel.name),
            country = existing?.country ?: inferred.country,
            language = existing?.language ?: inferred.language,
            category = existing?.category ?: inferred.category,
            resolvedLogoUrl = resolvedLogo,
            manualLogoUrl = existing?.manualLogoUrl,
            metadataSource = when {
                existing?.manualLogoUrl != null -> "manual"
                !channel.logo.isNullOrBlank() -> "playlist"
                catalogLogo != null -> inferred.logoSource
                else -> existing?.metadataSource
            },
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun resolveLogo(channel: ChannelEntity, playlistSource: String?): String? {
        val tvgKey = channel.tvgId?.lowercase(Locale.ROOT)?.trim()
        if (!tvgKey.isNullOrBlank()) {
            LOGO_BY_TVG_ID[tvgKey]?.let { return it }
            val shortKey = tvgKey.substringBeforeLast('.')
            LOGO_BY_TVG_ID[shortKey]?.let { return it }
        }

        val normalizedName = normalizeName(channel.name)
        LOGO_BY_NAME_KEYWORD.firstOrNull { (keyword, _) ->
            normalizedName.contains(keyword)
        }?.let { return it.second }

        val host = runCatching { Uri.parse(playlistSource).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        return LOGO_BY_SOURCE_HOST.entries.firstOrNull { (hostKeyword, _) ->
            host.contains(hostKeyword)
        }?.value
    }

    private fun inferMetadata(channel: ChannelEntity, playlistSource: String?): InferredMetadata {
        val source = listOfNotNull(channel.tvgId, channel.groupName, channel.name, playlistSource)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        val country = COUNTRY_HINTS.firstOrNull { (hint, _) -> source.contains(hint) }?.second
        val language = LANGUAGE_HINTS.firstOrNull { (hint, _) -> source.contains(hint) }?.second
        val category = CATEGORY_HINTS.firstOrNull { (hint, _) -> source.contains(hint) }?.second ?: channel.groupName
        val tvgId = channel.tvgId?.lowercase(Locale.ROOT)
        val logoSource = when {
            !tvgId.isNullOrBlank() && LOGO_BY_TVG_ID.containsKey(tvgId) -> "catalog:tvg-id"
            LOGO_BY_NAME_KEYWORD.any { (keyword, _) -> normalizeName(channel.name).contains(keyword) } -> "catalog:name"
            else -> "catalog:source"
        }
        return InferredMetadata(country, language, category, logoSource)
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
        val category: String?,
        val logoSource: String
    )

    private companion object {
        val LOGO_BY_TVG_ID = mapOf(
            "bbcone.uk" to "https://upload.wikimedia.org/wikipedia/commons/4/47/BBC_One_logo.svg",
            "bbctwo.uk" to "https://upload.wikimedia.org/wikipedia/commons/6/62/BBC_Two_logo_2021.svg",
            "cnn.us" to "https://upload.wikimedia.org/wikipedia/commons/b/b1/CNN.svg",
            "euronews.fr" to "https://upload.wikimedia.org/wikipedia/commons/5/58/Euronews_2016_logo.svg",
            "discoverychannel.us" to "https://upload.wikimedia.org/wikipedia/commons/4/43/Discovery_Channel_Logo.svg",
            "cartoonnetwork.us" to "https://upload.wikimedia.org/wikipedia/commons/0/04/Cartoon_Network_2010_logo.svg",
            "mtv.us" to "https://upload.wikimedia.org/wikipedia/commons/e/ea/MTV_Logo_2010.svg",
            "nickelodeon.us" to "https://upload.wikimedia.org/wikipedia/commons/6/6e/Nickelodeon_2023_logo_%28outline%29.svg",
            "animalplanet.us" to "https://upload.wikimedia.org/wikipedia/commons/2/2b/Animal_Planet_logo_2018.svg",
            "natgeo.us" to "https://upload.wikimedia.org/wikipedia/commons/f/fc/Natgeologo.svg",
            "tnt.us" to "https://upload.wikimedia.org/wikipedia/commons/c/c2/TNT_Logo_2016.svg"
        )
        val LOGO_BY_NAME_KEYWORD = listOf(
            "bbc one" to "https://upload.wikimedia.org/wikipedia/commons/4/47/BBC_One_logo.svg",
            "bbc two" to "https://upload.wikimedia.org/wikipedia/commons/6/62/BBC_Two_logo_2021.svg",
            "cnn" to "https://upload.wikimedia.org/wikipedia/commons/b/b1/CNN.svg",
            "euronews" to "https://upload.wikimedia.org/wikipedia/commons/5/58/Euronews_2016_logo.svg",
            "discovery" to "https://upload.wikimedia.org/wikipedia/commons/4/43/Discovery_Channel_Logo.svg",
            "national geographic" to "https://upload.wikimedia.org/wikipedia/commons/f/fc/Natgeologo.svg",
            "nickelodeon" to "https://upload.wikimedia.org/wikipedia/commons/6/6e/Nickelodeon_2023_logo_%28outline%29.svg",
            "cartoon network" to "https://upload.wikimedia.org/wikipedia/commons/0/04/Cartoon_Network_2010_logo.svg",
            "animal planet" to "https://upload.wikimedia.org/wikipedia/commons/2/2b/Animal_Planet_logo_2018.svg",
            "mtv" to "https://upload.wikimedia.org/wikipedia/commons/e/ea/MTV_Logo_2010.svg",
            "eurosport" to "https://upload.wikimedia.org/wikipedia/commons/e/e7/Eurosport_2023.svg",
            "espn" to "https://upload.wikimedia.org/wikipedia/commons/2/2f/ESPN_wordmark.svg"
        )
        val LOGO_BY_SOURCE_HOST = mapOf(
            "iptv-org" to "https://iptv-org.github.io/assets/logo.png"
        )
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
