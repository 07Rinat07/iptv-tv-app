package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.FavoritesPortableExport
import com.iptv.tv.core.model.FavoritesPortableImportResult
import com.iptv.tv.core.model.FavoritesPortableImportStatus
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

@Singleton
class FavoritesPortableBackupService @Inject constructor(
    private val favoriteSnapshotDao: FavoriteSnapshotDao,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao,
    private val playlistDao: PlaylistDao,
    private val favoriteDao: FavoriteDao
) {
    suspend fun exportPortableBackup(): FavoritesPortableExport {
        migrateLegacySeedsIfNeeded()

        val favorites = favoriteSnapshotDao.getFavoriteChannels()
        val sources = favorites.map { favorite ->
            val persisted = favoriteSnapshotDao.getVariants(favorite.logicalKey)
            val preferredKey = UnifiedFavoritePersistence.variantKey(favorite.preferredStreamUrl)
            val variants = if (persisted.any { it.variantKey == preferredKey }) {
                persisted
            } else {
                persisted + favorite.toSyntheticPreferredVariant(preferredKey)
            }
            FavoriteBackupSource(favorite = favorite, variants = variants)
        }
        val encoded = FavoritesPortableBackupCodec.encode(
            sources = sources,
            createdAt = System.currentTimeMillis()
        )
        return FavoritesPortableExport(
            content = encoded.content,
            favoriteCount = encoded.favoriteCount,
            variantCount = encoded.variantCount,
            redactedVariantCount = encoded.redactedVariantCount
        )
    }

    suspend fun importPortableBackup(content: String): FavoritesPortableImportResult {
        val decoded = FavoritesPortableBackupCodec.decode(content)
        val document = when (decoded) {
            is FavoriteBackupDecodeResult.Invalid -> {
                return FavoritesPortableImportResult(
                    status = FavoritesPortableImportStatus.INVALID_FORMAT,
                    message = decoded.message
                )
            }
            is FavoriteBackupDecodeResult.UnsupportedVersion -> {
                return FavoritesPortableImportResult(
                    status = FavoritesPortableImportStatus.UNSUPPORTED_VERSION,
                    message = "Unsupported Favorites backup version ${decoded.version}"
                )
            }
            is FavoriteBackupDecodeResult.Success -> decoded.document
        }

        // Validation above is complete before any write. Unsupported/malformed documents are therefore
        // rejected atomically from the caller's point of view.
        migrateLegacySeedsIfNeeded()
        val existingFavorites = favoriteSnapshotDao.getFavoriteChannels()
        val existingVariants = existingFavorites
            .flatMap { favorite -> favoriteSnapshotDao.getVariants(favorite.logicalKey) }
        val liveChannels = favoriteChannelLookupDao.getAllChannels()
        val playlists = liveChannels
            .map(ChannelEntity::playlistId)
            .distinct()
            .associateWith { playlistId -> playlistDao.findById(playlistId) }
        val plan = FavoritesPortableBackupPlanner.planImport(
            document = document,
            existingFavorites = existingFavorites,
            existingVariants = existingVariants,
            liveChannels = liveChannels,
            playlists = playlists,
            now = System.currentTimeMillis()
        )

        if (plan.favoritesToUpsert.isNotEmpty()) {
            favoriteSnapshotDao.upsertFavorites(plan.favoritesToUpsert)
        }
        if (plan.variantsToUpsert.isNotEmpty()) {
            favoriteSnapshotDao.upsertVariants(plan.variantsToUpsert)
        }
        if (plan.compatibilityFavorites.isNotEmpty()) {
            favoriteDao.upsertAll(plan.compatibilityFavorites)
        }

        return FavoritesPortableImportResult(
            status = FavoritesPortableImportStatus.SUCCESS,
            importedFavorites = plan.importedFavorites,
            mergedFavorites = plan.mergedFavorites,
            importedVariants = plan.importedVariants,
            redactedVariantsIgnored = plan.redactedVariantsIgnored,
            skippedUnrestorableFavorites = plan.skippedUnrestorableFavorites
        )
    }

    private suspend fun migrateLegacySeedsIfNeeded() {
        val seeds = favoriteSnapshotDao.getLegacySeeds()
        if (seeds.isEmpty()) return
        val batch = UnifiedFavoritePersistence.fromLegacySeeds(
            seeds = seeds,
            updatedAt = System.currentTimeMillis()
        )
        favoriteSnapshotDao.upsertFavorites(batch.favorites)
        favoriteSnapshotDao.upsertVariants(batch.variants)
        favoriteSnapshotDao.clearLegacySeeds()
    }

    private fun FavoriteChannelEntity.toSyntheticPreferredVariant(
        preferredKey: String
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = logicalKey,
        variantKey = preferredKey,
        legacyChannelId = preferredChannelId,
        playlistId = preferredPlaylistId,
        playlistName = null,
        sourceType = null,
        catalogOrigin = null,
        tvgId = tvgId,
        name = name,
        groupName = groupName,
        logo = logo,
        streamUrl = preferredStreamUrl,
        addedAt = addedAt,
        updatedAt = updatedAt
    )
}

internal data class FavoriteBackupSource(
    val favorite: FavoriteChannelEntity,
    val variants: List<FavoriteChannelVariantEntity>
)

internal data class FavoriteBackupDocument(
    val formatVersion: Int,
    val createdAt: Long,
    val favorites: List<FavoriteBackupEntry>
)

internal data class FavoriteBackupEntry(
    val logicalKey: String,
    val tvgId: String?,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val preferredVariantKey: String?,
    val addedAt: Long,
    val updatedAt: Long,
    val variants: List<FavoriteBackupVariant>
)

internal data class FavoriteBackupVariant(
    val variantKey: String,
    val playlistName: String?,
    val sourceType: String?,
    val catalogOrigin: String?,
    val tvgId: String?,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val streamUrl: String?,
    val addedAt: Long,
    val updatedAt: Long
) {
    val isRedacted: Boolean get() = streamUrl == null
}

internal sealed interface FavoriteBackupDecodeResult {
    data class Success(val document: FavoriteBackupDocument) : FavoriteBackupDecodeResult
    data class UnsupportedVersion(val version: Int) : FavoriteBackupDecodeResult
    data class Invalid(val message: String) : FavoriteBackupDecodeResult
}

internal data class FavoriteBackupEncodeResult(
    val content: String,
    val favoriteCount: Int,
    val variantCount: Int,
    val redactedVariantCount: Int
)

internal object FavoritesPortableBackupCodec {
    private const val FORMAT = "rinat-iptv-favorites"
    private const val VERSION = 1
    private const val MAX_CONTENT_CHARS = 20_000_000
    private const val MAX_FAVORITES = 50_000
    private const val MAX_VARIANTS_PER_FAVORITE = 2_000

    fun encode(
        sources: List<FavoriteBackupSource>,
        createdAt: Long
    ): FavoriteBackupEncodeResult {
        var variantCount = 0
        var redactedVariantCount = 0
        val favoriteArray = JSONArray()

        sources.sortedBy { it.favorite.logicalKey }.forEach { source ->
            val favorite = source.favorite
            val preferredVariantKey = UnifiedFavoritePersistence.variantKey(favorite.preferredStreamUrl)
            val variants = source.variants
                .filter { it.logicalKey == favorite.logicalKey }
                .distinctBy(FavoriteChannelVariantEntity::variantKey)
                .sortedBy(FavoriteChannelVariantEntity::variantKey)
            val variantArray = JSONArray()
            variants.forEach { variant ->
                variantCount += 1
                val redacted = FavoriteBackupCredentialPolicy.mustRedact(variant)
                if (redacted) redactedVariantCount += 1
                variantArray.put(
                    JSONObject().apply {
                        put("variantKey", variant.variantKey)
                        putNullable("playlistName", variant.playlistName)
                        putNullable("sourceType", variant.sourceType)
                        putNullable("catalogOrigin", variant.catalogOrigin)
                        putNullable("tvgId", variant.tvgId)
                        put("name", variant.name)
                        putNullable("groupName", variant.groupName)
                        putNullable("logo", variant.logo)
                        put("redacted", redacted)
                        if (!redacted) put("streamUrl", variant.streamUrl)
                        put("addedAt", variant.addedAt)
                        put("updatedAt", variant.updatedAt)
                    }
                )
            }
            favoriteArray.put(
                JSONObject().apply {
                    put("logicalKey", favorite.logicalKey)
                    putNullable("tvgId", favorite.tvgId)
                    put("name", favorite.name)
                    putNullable("groupName", favorite.groupName)
                    putNullable("logo", favorite.logo)
                    put("preferredVariantKey", preferredVariantKey)
                    put("addedAt", favorite.addedAt)
                    put("updatedAt", favorite.updatedAt)
                    put("variants", variantArray)
                }
            )
        }

        val root = JSONObject().apply {
            put("format", FORMAT)
            put("formatVersion", VERSION)
            put("createdAt", createdAt)
            put("favorites", favoriteArray)
        }
        return FavoriteBackupEncodeResult(
            content = root.toString(2),
            favoriteCount = sources.size,
            variantCount = variantCount,
            redactedVariantCount = redactedVariantCount
        )
    }

    fun decode(content: String): FavoriteBackupDecodeResult {
        if (content.isBlank()) return FavoriteBackupDecodeResult.Invalid("Backup is empty")
        if (content.length > MAX_CONTENT_CHARS) {
            return FavoriteBackupDecodeResult.Invalid("Backup is too large")
        }

        return try {
            val root = JSONObject(content)
            if (root.optString("format") != FORMAT) {
                return FavoriteBackupDecodeResult.Invalid("Not a Rinat IPTV Favorites backup")
            }
            val version = root.optInt("formatVersion", -1)
            if (version != VERSION) {
                return FavoriteBackupDecodeResult.UnsupportedVersion(version)
            }
            val createdAt = root.requireNonNegativeLong("createdAt")
            val favoriteArray = root.requireArray("favorites")
            require(favoriteArray.length() <= MAX_FAVORITES) { "Too many favorites in backup" }

            val favorites = ArrayList<FavoriteBackupEntry>(favoriteArray.length())
            val logicalKeys = hashSetOf<String>()
            for (index in 0 until favoriteArray.length()) {
                val item = favoriteArray.getJSONObject(index)
                val logicalKey = item.requireNonBlankString("logicalKey")
                require(logicalKeys.add(logicalKey)) { "Duplicate favorite logicalKey: $logicalKey" }
                val name = item.requireNonBlankString("name")
                val variantsJson = item.requireArray("variants")
                require(variantsJson.length() <= MAX_VARIANTS_PER_FAVORITE) {
                    "Too many variants for favorite $logicalKey"
                }
                val variants = ArrayList<FavoriteBackupVariant>(variantsJson.length())
                val variantKeys = hashSetOf<String>()
                for (variantIndex in 0 until variantsJson.length()) {
                    val variantJson = variantsJson.getJSONObject(variantIndex)
                    val variantKey = variantJson.requireNonBlankString("variantKey")
                    require(variantKeys.add(variantKey)) {
                        "Duplicate variantKey for favorite $logicalKey: $variantKey"
                    }
                    val redacted = variantJson.optBoolean("redacted", false)
                    val streamUrl = variantJson.optionalString("streamUrl")
                    if (redacted) {
                        require(streamUrl == null) { "Redacted variant must not contain streamUrl" }
                    } else {
                        require(!streamUrl.isNullOrBlank()) { "Playable variant is missing streamUrl" }
                        require(UnifiedFavoritePersistence.variantKey(streamUrl) == variantKey) {
                            "variantKey does not match streamUrl"
                        }
                    }
                    variants += FavoriteBackupVariant(
                        variantKey = variantKey,
                        playlistName = variantJson.optionalString("playlistName"),
                        sourceType = variantJson.optionalString("sourceType"),
                        catalogOrigin = variantJson.optionalString("catalogOrigin"),
                        tvgId = variantJson.optionalString("tvgId"),
                        name = variantJson.requireNonBlankString("name"),
                        groupName = variantJson.optionalString("groupName"),
                        logo = variantJson.optionalString("logo"),
                        streamUrl = streamUrl,
                        addedAt = variantJson.requireNonNegativeLong("addedAt"),
                        updatedAt = variantJson.requireNonNegativeLong("updatedAt")
                    )
                }
                val preferredVariantKey = item.optionalString("preferredVariantKey")
                require(preferredVariantKey == null || preferredVariantKey in variantKeys) {
                    "preferredVariantKey is not present in variants"
                }
                favorites += FavoriteBackupEntry(
                    logicalKey = logicalKey,
                    tvgId = item.optionalString("tvgId"),
                    name = name,
                    groupName = item.optionalString("groupName"),
                    logo = item.optionalString("logo"),
                    preferredVariantKey = preferredVariantKey,
                    addedAt = item.requireNonNegativeLong("addedAt"),
                    updatedAt = item.requireNonNegativeLong("updatedAt"),
                    variants = variants
                )
            }
            FavoriteBackupDecodeResult.Success(
                FavoriteBackupDocument(
                    formatVersion = version,
                    createdAt = createdAt,
                    favorites = favorites
                )
            )
        } catch (error: JSONException) {
            FavoriteBackupDecodeResult.Invalid(error.message ?: "Malformed JSON backup")
        } catch (error: IllegalArgumentException) {
            FavoriteBackupDecodeResult.Invalid(error.message ?: "Invalid Favorites backup")
        }
    }

    private fun JSONObject.requireArray(name: String): JSONArray {
        require(has(name) && !isNull(name)) { "Missing $name" }
        return getJSONArray(name)
    }

    private fun JSONObject.requireNonBlankString(name: String): String {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = getString(name).trim()
        require(value.isNotBlank()) { "$name must not be blank" }
        return value
    }

    private fun JSONObject.requireNonNegativeLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = getLong(name)
        require(value >= 0L) { "$name must not be negative" }
        return value
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return getString(name).trim().takeIf(String::isNotEmpty)
    }

    private fun JSONObject.putNullable(name: String, value: String?) {
        if (!value.isNullOrBlank()) put(name, value)
    }
}

internal object FavoriteBackupCredentialPolicy {
    private val providerSourceTypes = setOf(
        "XTREAM",
        "STALKER",
        "JELLYFIN",
        "PLEX",
        "TVHEADEND",
        "HDHOMERUN"
    )
    private val sensitiveQueryKeys = setOf(
        "token",
        "access_token",
        "auth",
        "authorization",
        "username",
        "user",
        "password",
        "pass",
        "key",
        "api_key",
        "apikey",
        "mac",
        "macaddress",
        "signature",
        "sig",
        "session",
        "credential"
    )

    fun mustRedact(variant: FavoriteChannelVariantEntity): Boolean {
        val sourceType = variant.sourceType?.trim()?.uppercase(Locale.ROOT)
        val catalogOrigin = variant.catalogOrigin?.trim()?.uppercase(Locale.ROOT)
        if (catalogOrigin == "PROVIDER" || sourceType in providerSourceTypes) return true
        return containsCredentials(variant.streamUrl)
    }

    private fun containsCredentials(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        val parsed = runCatching { URI(trimmed) }.getOrNull()
        if (!parsed?.userInfo.isNullOrBlank()) return true

        val query = parsed?.rawQuery
        if (!query.isNullOrBlank()) {
            query.split('&', ';').forEach { pair ->
                val rawName = pair.substringBefore('=', pair)
                val name = runCatching {
                    URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
                }.getOrDefault(rawName).lowercase(Locale.ROOT)
                if (name in sensitiveQueryKeys || sensitiveQueryKeys.any { key -> name.contains(key) }) {
                    return true
                }
            }
        }

        // Xtream-style URLs commonly embed username/password as path segments.
        val segments = parsed?.path.orEmpty().split('/').filter(String::isNotBlank)
        if (segments.size >= 4 && segments.firstOrNull()?.lowercase(Locale.ROOT) in setOf("live", "movie", "series")) {
            return true
        }
        return Regex("://[^/]+@", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)
    }
}

internal data class FavoritesBackupImportPlan(
    val favoritesToUpsert: List<FavoriteChannelEntity>,
    val variantsToUpsert: List<FavoriteChannelVariantEntity>,
    val compatibilityFavorites: List<FavoriteEntity>,
    val importedFavorites: Int,
    val mergedFavorites: Int,
    val importedVariants: Int,
    val redactedVariantsIgnored: Int,
    val skippedUnrestorableFavorites: Int
)

internal object FavoritesPortableBackupPlanner {
    fun planImport(
        document: FavoriteBackupDocument,
        existingFavorites: List<FavoriteChannelEntity>,
        existingVariants: List<FavoriteChannelVariantEntity>,
        liveChannels: List<ChannelEntity>,
        playlists: Map<Long, PlaylistEntity?>,
        now: Long
    ): FavoritesBackupImportPlan {
        val existingFavoritesByKey = existingFavorites.associateBy(FavoriteChannelEntity::logicalKey)
        val existingVariantsByKey = existingVariants.groupBy(FavoriteChannelVariantEntity::logicalKey)
        val liveByKey = liveChannels.groupBy(UnifiedFavoritePersistence::logicalKey)
        val favoriteUpserts = mutableListOf<FavoriteChannelEntity>()
        val variantUpserts = mutableListOf<FavoriteChannelVariantEntity>()
        val compatibility = mutableListOf<FavoriteEntity>()
        var importedFavorites = 0
        var mergedFavorites = 0
        var importedVariants = 0
        var redactedIgnored = 0
        var skipped = 0

        document.favorites.sortedBy(FavoriteBackupEntry::logicalKey).forEach { entry ->
            val existingFavorite = existingFavoritesByKey[entry.logicalKey]
            val live = liveByKey[entry.logicalKey].orEmpty()
                .sortedWith(compareBy<ChannelEntity> { it.playlistId }.thenBy { it.orderIndex }.thenBy { it.id })
            val safeImported = entry.variants.filterNot(FavoriteBackupVariant::isRedacted)
            redactedIgnored += entry.variants.count(FavoriteBackupVariant::isRedacted)

            if (existingFavorite == null && live.isEmpty() && safeImported.isEmpty()) {
                skipped += 1
                return@forEach
            }

            val liveByVariantKey = live.associateBy { channel ->
                UnifiedFavoritePersistence.variantKey(channel.streamUrl)
            }
            val mergedVariants = linkedMapOf<String, FavoriteChannelVariantEntity>()

            safeImported.forEach { variant ->
                val streamUrl = requireNotNull(variant.streamUrl)
                val matchedLive = liveByVariantKey[variant.variantKey]
                mergedVariants[variant.variantKey] = FavoriteChannelVariantEntity(
                    logicalKey = entry.logicalKey,
                    variantKey = variant.variantKey,
                    legacyChannelId = matchedLive?.id ?: syntheticCompatibilityId(
                        "${entry.logicalKey}|${variant.variantKey}"
                    ),
                    playlistId = matchedLive?.playlistId ?: 0L,
                    playlistName = variant.playlistName,
                    sourceType = variant.sourceType,
                    catalogOrigin = variant.catalogOrigin,
                    tvgId = variant.tvgId,
                    name = variant.name,
                    groupName = variant.groupName,
                    logo = variant.logo,
                    streamUrl = streamUrl,
                    addedAt = variant.addedAt,
                    updatedAt = maxOf(variant.updatedAt, now)
                )
            }

            existingVariantsByKey[entry.logicalKey].orEmpty().forEach { existing ->
                val matchedLive = liveByVariantKey[existing.variantKey]
                mergedVariants[existing.variantKey] = if (matchedLive == null) {
                    existing
                } else {
                    existing.copy(
                        legacyChannelId = matchedLive.id,
                        playlistId = matchedLive.playlistId,
                        updatedAt = maxOf(existing.updatedAt, now)
                    )
                }
            }

            live.forEach { channel ->
                val variantKey = UnifiedFavoritePersistence.variantKey(channel.streamUrl)
                if (variantKey !in mergedVariants) {
                    val playlist = playlists[channel.playlistId]
                    mergedVariants[variantKey] = FavoriteChannelVariantEntity(
                        logicalKey = entry.logicalKey,
                        variantKey = variantKey,
                        legacyChannelId = channel.id,
                        playlistId = channel.playlistId,
                        playlistName = playlist?.name,
                        sourceType = playlist?.sourceType,
                        catalogOrigin = playlist?.catalogOrigin,
                        tvgId = channel.tvgId,
                        name = channel.name,
                        groupName = channel.groupName,
                        logo = channel.logo,
                        streamUrl = channel.streamUrl,
                        addedAt = entry.addedAt,
                        updatedAt = now
                    )
                }
            }

            val favorite = if (existingFavorite != null) {
                mergedFavorites += 1
                val preferredKey = UnifiedFavoritePersistence.variantKey(existingFavorite.preferredStreamUrl)
                val matchedLive = liveByVariantKey[preferredKey]
                if (matchedLive == null) {
                    existingFavorite
                } else {
                    existingFavorite.copy(
                        preferredPlaylistId = matchedLive.playlistId,
                        preferredChannelId = matchedLive.id,
                        updatedAt = maxOf(existingFavorite.updatedAt, now)
                    )
                }
            } else {
                importedFavorites += 1
                val preferredKey = entry.preferredVariantKey
                val selectedLive = preferredKey?.let(liveByVariantKey::get)
                    ?: live.firstOrNull()
                val selectedImported = preferredKey
                    ?.let { key -> mergedVariants[key] }
                    ?: mergedVariants.values.firstOrNull()
                val selected = selectedLive?.let { channel ->
                    mergedVariants[UnifiedFavoritePersistence.variantKey(channel.streamUrl)]
                } ?: selectedImported
                requireNotNull(selected) { "Validated backup favorite must have a restorable source" }
                FavoriteChannelEntity(
                    logicalKey = entry.logicalKey,
                    tvgId = entry.tvgId,
                    name = entry.name,
                    groupName = entry.groupName,
                    logo = entry.logo,
                    preferredStreamUrl = selected.streamUrl,
                    preferredPlaylistId = selected.playlistId,
                    preferredChannelId = selected.legacyChannelId,
                    addedAt = entry.addedAt,
                    updatedAt = now
                )
            }

            favoriteUpserts += favorite
            variantUpserts += mergedVariants.values
            importedVariants += safeImported.size
            live.forEach { channel ->
                compatibility += FavoriteEntity(
                    channelId = channel.id,
                    addedAt = favorite.addedAt
                )
            }
        }

        return FavoritesBackupImportPlan(
            favoritesToUpsert = favoriteUpserts,
            variantsToUpsert = variantUpserts
                .distinctBy { variant -> variant.logicalKey to variant.variantKey },
            compatibilityFavorites = compatibility.distinctBy(FavoriteEntity::channelId),
            importedFavorites = importedFavorites,
            mergedFavorites = mergedFavorites,
            importedVariants = importedVariants,
            redactedVariantsIgnored = redactedIgnored,
            skippedUnrestorableFavorites = skipped
        )
    }

    private fun syntheticCompatibilityId(seed: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val positive = ByteBuffer.wrap(digest.copyOfRange(0, Long.SIZE_BYTES)).long and Long.MAX_VALUE
        return if (positive == 0L) -1L else -positive
    }
}
