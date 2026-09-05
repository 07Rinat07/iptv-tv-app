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

    override suspend fun setManualMetadata(
        channelId: Long,
        country: String?,
        language: String?,
        category: String?
    ): AppResult<Int> = withContext(Dispatchers.IO) {
        val channel = channelDao.findById(channelId)
            ?: return@withContext AppResult.Error("Канал не найден: id=$channelId")
        val metadata = buildManualMetadata(channel, country, language, category)
        channelMetadataDao.upsert(metadata.toEntity())
        addLog(
            status = "metadata_manual_fields",
            message = "channelId=${channel.id}, country=${metadata.manualCountry}, language=${metadata.manualLanguage}, category=${metadata.manualCategory}"
        )
        AppResult.Success(1)
    }

    override suspend fun setManualMetadataBulk(
        channelIds: List<Long>,
        country: String?,
        language: String?,
        category: String?
    ): AppResult<Int> = withContext(Dispatchers.IO) {
        val ids = channelIds.distinct()
        if (ids.isEmpty()) {
            return@withContext AppResult.Error("Нет каналов для массового обновления метаданных")
        }
        val channelsById = ids
            .chunked(BULK_METADATA_LOOKUP_BATCH_SIZE)
            .flatMap { batch -> channelDao.findByIds(batch) }
            .associateBy(ChannelEntity::id)
        val existingMetadataByChannelId = findMetadataByChannelIds(
            ids.filter(channelsById::containsKey)
        )
        var updated = 0
        ids.forEach { channelId ->
            val channel = channelsById[channelId] ?: return@forEach
            val metadata = buildManualMetadata(
                channel = channel,
                existing = existingMetadataByChannelId[channelId]?.toModel(),
                country = country,
                language = language,
                category = category
            )
            channelMetadataDao.upsert(metadata.toEntity())
            updated += 1
        }
        addLog(
            status = "metadata_manual_fields_bulk",
            message = "requested=${ids.size}, updated=$updated, country=${normalizeOverride(country)}, language=${normalizeOverride(language)}, category=${normalizeOverride(category)}"
        )
        AppResult.Success(updated)
    }

    override suspend fun applyMetadataRules(
        playlistId: Long,
        rulesText: String,
        channelIds: List<Long>
    ): AppResult<Int> = withContext(Dispatchers.IO) {
        val rules = parseMetadataRules(rulesText)
        if (rules.isEmpty()) {
            return@withContext AppResult.Error("Нет корректных metadata rules")
        }
        val playlist = playlistDao.findById(playlistId)
            ?: return@withContext AppResult.Error("Плейлист не найден: id=$playlistId")
        val targetChannels = if (channelIds.isEmpty()) {
            channelDao.getChannels(playlistId)
        } else {
            channelIds
                .distinct()
                .chunked(BULK_METADATA_LOOKUP_BATCH_SIZE)
                .flatMap { batch -> channelDao.findByIds(batch) }
                .filter { channel -> channel.playlistId == playlistId }
                .sortedBy(ChannelEntity::orderIndex)
        }
        if (targetChannels.isEmpty()) {
            return@withContext AppResult.Error("Нет каналов для применения metadata rules")
        }
        val existingByChannel = findMetadataByChannelIds(targetChannels.map { it.id })
        var updated = 0
        targetChannels.forEach { channel ->
            val matchedRules = rules.filter { it.matches(channel, playlist.source) }
            if (matchedRules.isEmpty()) return@forEach
            val existing = existingByChannel[channel.id]?.toModel()
            var metadata = existing.copyOrNew(channel)
            matchedRules.forEach { rule ->
                metadata = metadata.copy(
                    manualCountry = rule.country ?: metadata.manualCountry,
                    manualLanguage = rule.language ?: metadata.manualLanguage,
                    manualCategory = rule.category ?: metadata.manualCategory
                )
            }
            channelMetadataDao.upsert(
                buildMetadata(
                    channel = channel,
                    existing = metadata,
                    playlistSource = playlist.source
                ).copy(updatedAt = System.currentTimeMillis()).toEntity()
            )
            updated += 1
        }
        addLog(
            status = "metadata_rules_applied",
            message = "playlistId=$playlistId, rules=${rules.size}, target=${targetChannels.size}, updated=$updated"
        )
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

    override suspend fun refreshMetadataWithLogoPackUrl(
        playlistId: Long,
        logoPackUrl: String
    ): AppResult<Int> = withContext(Dispatchers.IO) {
        val normalizedUrl = logoPackUrl.trim()
        if (normalizedUrl.isBlank()) {
            return@withContext AppResult.Error("Logo pack URL пуст")
        }
        val networkPack = runCatching { logoCatalogResolver.fetchNetworkLogoPack(normalizedUrl) }
            .getOrElse { throwable ->
                return@withContext AppResult.Error(throwable.message ?: "Не удалось загрузить logo pack")
            }
        when (
            val result = refreshMetadataInternal(
                playlistId = playlistId,
                resolver = LogoCatalogResolver(baseEntries = emptyList(), packJson = networkPack.json),
                logStatus = "metadata_network_logo_pack",
                logPrefix = "networkLogoPack=${normalizedUrl.take(LOGO_PACK_URL_LOG_LIMIT)}, fromCache=${networkPack.fromCache}, detail=${networkPack.detail}, "
            )
        ) {
            is AppResult.Success -> result
            is AppResult.Error -> result
            AppResult.Loading -> AppResult.Loading
        }
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
        val existingByChannel = findMetadataByChannelIds(channels.map { it.id })
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

    private suspend fun findMetadataByChannelIds(channelIds: Collection<Long>) = channelIds
        .distinct()
        .chunked(BULK_METADATA_LOOKUP_BATCH_SIZE)
        .flatMap { batch -> channelMetadataDao.findByChannelIds(batch) }
        .associateBy { metadata -> metadata.channelId }

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
        val hasManualFields = existing.hasManualFields()
        return ChannelMetadata(
            channelId = channel.id,
            normalizedName = normalizeName(channel.name),
            country = existing?.manualCountry ?: catalogMatch?.entry?.country ?: inferred.country,
            language = existing?.manualLanguage ?: catalogMatch?.entry?.language ?: inferred.language,
            category = existing?.manualCategory ?: catalogMatch?.entry?.category ?: inferred.category,
            resolvedLogoUrl = resolvedLogo,
            manualLogoUrl = existing?.manualLogoUrl,
            metadataSource = when {
                hasManualFields -> "manual_metadata"
                existing?.manualLogoUrl != null -> "manual"
                !channel.logo.isNullOrBlank() -> "playlist"
                catalogMatch != null -> catalogMatch.source
                else -> existing?.metadataSource
            },
            updatedAt = System.currentTimeMillis(),
            manualCountry = existing?.manualCountry,
            manualLanguage = existing?.manualLanguage,
            manualCategory = existing?.manualCategory
        )
    }

    private fun ChannelMetadata?.copyOrNew(channel: ChannelEntity): ChannelMetadata {
        return this ?: ChannelMetadata(
            channelId = channel.id,
            normalizedName = normalizeName(channel.name),
            country = null,
            language = null,
            category = null,
            resolvedLogoUrl = channel.logo,
            manualLogoUrl = null,
            metadataSource = null,
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun ChannelMetadata?.hasManualFields(): Boolean {
        return this?.manualCountry != null || this?.manualLanguage != null || this?.manualCategory != null
    }

    private suspend fun buildManualMetadata(
        channel: ChannelEntity,
        country: String?,
        language: String?,
        category: String?
    ): ChannelMetadata {
        return buildManualMetadata(
            channel = channel,
            existing = channelMetadataDao.findByChannelId(channel.id)?.toModel(),
            country = country,
            language = language,
            category = category
        )
    }

    private fun buildManualMetadata(
        channel: ChannelEntity,
        existing: ChannelMetadata?,
        country: String?,
        language: String?,
        category: String?
    ): ChannelMetadata {
        val metadataWithOverrides = existing.copyOrNew(channel).copy(
            manualCountry = normalizeOverride(country),
            manualLanguage = normalizeOverride(language),
            manualCategory = normalizeOverride(category)
        )
        return buildMetadata(channel = channel, existing = metadataWithOverrides)
            .copy(updatedAt = System.currentTimeMillis())
    }

    private fun normalizeOverride(value: String?): String? {
        return value?.trim()?.ifBlank { null }
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

        const val BULK_METADATA_LOOKUP_BATCH_SIZE = 900
        const val LOGO_PACK_URL_LOG_LIMIT = 160
    }
}

internal fun parseMetadataRules(rulesText: String): List<MetadataRule> {
    return rulesText
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull(::parseMetadataRule)
        .toList()
}

internal data class MetadataRule(
    val match: String? = null,
    val name: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val source: String? = null,
    val country: String? = null,
    val language: String? = null,
    val category: String? = null
) {
    fun matches(channel: ChannelEntity, playlistSource: String?): Boolean {
        val anyText = listOfNotNull(channel.tvgId, channel.groupName, channel.name, playlistSource)
            .joinToString(" ")
            .lowercase(Locale.ROOT)
        return matchesPart(match, anyText) &&
            matchesPart(name, channel.name) &&
            matchesPart(group, channel.groupName) &&
            matchesPart(tvgId, channel.tvgId) &&
            matchesPart(source, playlistSource)
    }

    private fun matchesPart(needle: String?, haystack: String?): Boolean {
        if (needle.isNullOrBlank()) return true
        return haystack.orEmpty().lowercase(Locale.ROOT).contains(needle.lowercase(Locale.ROOT))
    }
}

private fun parseMetadataRule(line: String): MetadataRule? {
    val parts = line
        .split(';')
        .mapNotNull { token ->
            val keyValue = token.split('=', limit = 2)
            if (keyValue.size != 2) return@mapNotNull null
            val key = keyValue[0].trim().lowercase(Locale.ROOT)
            val value = keyValue[1].trim().ifBlank { null } ?: return@mapNotNull null
            key to value
        }
        .toMap()
    if (parts.isEmpty()) return null
    val rule = MetadataRule(
        match = parts["match"] ?: parts["contains"],
        name = parts["name"],
        group = parts["group"],
        tvgId = parts["tvg"] ?: parts["tvg-id"] ?: parts["tvgid"],
        source = parts["source"] ?: parts["domain"],
        country = parts["country"] ?: parts["countrycode"] ?: parts["country-code"],
        language = parts["language"] ?: parts["lang"],
        category = parts["category"] ?: parts["groupname"] ?: parts["group-name"]
    )
    val hasMatcher = listOf(rule.match, rule.name, rule.group, rule.tvgId, rule.source).any { !it.isNullOrBlank() }
    val hasMetadata = listOf(rule.country, rule.language, rule.category).any { !it.isNullOrBlank() }
    return rule.takeIf { hasMatcher && hasMetadata }
}
