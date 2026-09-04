package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelIdentityRow
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.FavoriteEntity
import com.iptv.tv.core.database.entity.FavoriteLegacySeedEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelCatchUpMetadata
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelStableIdentity
import com.iptv.tv.core.model.FavoritePlaybackContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Logical Favorites persistence layer introduced with database v10.
 *
 * The old `favorites(channelId)` table remains as a compatibility mirror for feature code that has
 * not yet migrated. The durable source of truth is `favorite_channels` plus
 * `favorite_channel_variants`; both store enough snapshot data to survive deletion of the original
 * playlist/channel rows.
 *
 * Large channel catalogs are reconciled through bounded identity pages. Do not reintroduce a
 * continuously observed `Flow<List<ChannelEntity>>` over the complete `channels` table: the
 * 2026-08-25 field build exhausted a 256 MB heap while Scanner/import and Favorites invalidations
 * overlapped.
 */
@Singleton
class UnifiedFavoritesRepositoryImpl @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val favoriteSnapshotDao: FavoriteSnapshotDao,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao,
    private val playlistDao: PlaylistDao
) : FavoritesRepository {
    private val legacyMigrationMutex = Mutex()

    @Volatile
    private var legacySeedsChecked = false

    override fun observeFavorites(): Flow<List<Channel>> {
        return combine(
            favoriteSnapshotDao.observeFavoriteChannels(),
            favoriteChannelLookupDao.observeChannelTableInvalidation()
        ) { favorites, _ ->
            favorites
        }.mapLatest { favorites ->
            val liveChannels = loadLiveChannels(
                logicalKeys = favorites.mapTo(hashSetOf(), FavoriteChannelEntity::logicalKey)
            )
            UnifiedFavoritePersistence.representFavorites(
                favorites = favorites,
                liveChannels = liveChannels
            )
        }.onStart {
            ensureLegacySeedsMigrated()
        }
    }

    override fun observeFavoriteCount(): Flow<Int> {
        return favoriteSnapshotDao.observeFavoriteChannelCount()
            .onStart {
                ensureLegacySeedsMigrated()
            }
    }

    override fun observeFavoriteChannelIds(): Flow<Set<Long>> {
        return combine(
            favoriteSnapshotDao.observeFavoriteChannels(),
            favoriteChannelLookupDao.observeChannelTableInvalidation()
        ) { favorites, _ ->
            favorites
        }.mapLatest { favorites ->
            loadLiveChannelIds(
                logicalKeys = favorites.mapTo(hashSetOf(), FavoriteChannelEntity::logicalKey)
            ).toCollection(linkedSetOf())
        }.onStart {
            ensureLegacySeedsMigrated()
        }
    }

    override suspend fun resolvePlaybackContext(favoriteChannelId: Long): FavoritePlaybackContext? {
        ensureLegacySeedsMigrated()

        val requestedLive = favoriteChannelLookupDao.findChannelById(favoriteChannelId)
        val favorite = requestedLive
            ?.let { channel -> favoriteSnapshotDao.findFavorite(UnifiedFavoritePersistence.logicalKey(channel)) }
            ?: favoriteSnapshotDao.findFavoriteByPreferredChannelId(favoriteChannelId)
            ?: return null

        val liveChannels = loadLiveChannels(setOf(favorite.logicalKey))
        val persistedVariants = favoriteSnapshotDao.getVariants(favorite.logicalKey)

        return UnifiedFavoritePersistence.resolvePlaybackContext(
            requestedChannelId = favoriteChannelId,
            favorite = favorite,
            persistedVariants = persistedVariants,
            liveChannels = liveChannels
        )
    }

    override suspend fun toggleFavorite(channelId: Long) {
        ensureLegacySeedsMigrated()

        val selected = favoriteChannelLookupDao.findChannelById(channelId)
        if (selected == null) {
            // A standalone favorite can outlive its source row. The existing feature API still
            // passes a Long channel ID, so use the persisted preferred ID as a compatibility key
            // for removal until the UI moves to logicalKey directly.
            favoriteSnapshotDao.findFavoriteByPreferredChannelId(channelId)?.let { favorite ->
                removeFavorite(favorite.logicalKey)
            }
            return
        }

        val logicalKey = UnifiedFavoritePersistence.logicalKey(selected)
        if (favoriteSnapshotDao.findFavorite(logicalKey) != null) {
            removeFavorite(logicalKey)
            return
        }

        val equivalents = loadLiveChannels(setOf(logicalKey))
            .ifEmpty { listOf(selected) }
        val playlists = equivalents
            .map(ChannelEntity::playlistId)
            .distinct()
            .associateWith { playlistId -> playlistDao.findById(playlistId) }
        val now = System.currentTimeMillis()
        val batch = UnifiedFavoritePersistence.fromLiveChannels(
            logicalKey = logicalKey,
            preferred = selected,
            equivalents = equivalents,
            playlists = playlists,
            addedAt = now,
            updatedAt = now
        )

        favoriteSnapshotDao.upsertFavorite(batch.favorite)
        favoriteSnapshotDao.upsertVariants(batch.variants)

        // Compatibility mirror: existing feature code outside this repository may still query the
        // legacy favorite row IDs. Logical storage remains authoritative.
        favoriteDao.upsertAll(
            equivalents.map { channel ->
                FavoriteEntity(channelId = channel.id, addedAt = now)
            }
        )
    }

    private suspend fun removeFavorite(logicalKey: String) {
        val liveEquivalentIds = loadLiveChannelIds(setOf(logicalKey))
        val persistedVariantIds = favoriteSnapshotDao.getVariants(logicalKey)
            .map(FavoriteChannelVariantEntity::legacyChannelId)
        val legacyIds = (liveEquivalentIds + persistedVariantIds).distinct()

        if (legacyIds.isNotEmpty()) {
            favoriteDao.deleteByChannelIds(legacyIds)
        }
        favoriteSnapshotDao.deleteVariants(logicalKey)
        favoriteSnapshotDao.deleteFavorite(logicalKey)
    }

    private suspend fun loadLiveChannels(logicalKeys: Set<String>): List<ChannelEntity> {
        if (logicalKeys.isEmpty()) return emptyList()
        val channelIds = loadLiveChannelIds(logicalKeys)
        if (channelIds.isEmpty()) return emptyList()

        return channelIds
            .chunked(FULL_CHANNEL_BATCH_SIZE)
            .flatMap { ids -> favoriteChannelLookupDao.findChannelsByIds(ids) }
            .sortedWith(
                compareBy<ChannelEntity> { it.playlistId }
                    .thenBy { it.orderIndex }
                    .thenBy { it.id }
            )
    }

    private suspend fun loadLiveChannelIds(logicalKeys: Set<String>): List<Long> {
        if (logicalKeys.isEmpty()) return emptyList()

        val matched = linkedSetOf<Long>()
        var afterId = 0L

        while (true) {
            val page = favoriteChannelLookupDao.getChannelIdentityPage(
                afterId = afterId,
                limit = IDENTITY_PAGE_SIZE
            )
            if (page.isEmpty()) break

            FavoriteChannelIdentityReconciliation.collectMatchingIds(
                rows = page,
                logicalKeys = logicalKeys,
                destination = matched
            )

            afterId = page.last().id
            if (page.size < IDENTITY_PAGE_SIZE) break
        }

        return matched.toList()
    }

    private suspend fun ensureLegacySeedsMigrated() {
        if (legacySeedsChecked) return
        legacyMigrationMutex.withLock {
            if (legacySeedsChecked) return@withLock
            val seeds = favoriteSnapshotDao.getLegacySeeds()
            if (seeds.isNotEmpty()) {
                val batch = UnifiedFavoritePersistence.fromLegacySeeds(
                    seeds = seeds,
                    updatedAt = System.currentTimeMillis()
                )
                favoriteSnapshotDao.upsertFavorites(batch.favorites)
                favoriteSnapshotDao.upsertVariants(batch.variants)
                favoriteSnapshotDao.clearLegacySeeds()
            }
            legacySeedsChecked = true
        }
    }

    private companion object {
        const val IDENTITY_PAGE_SIZE = 512
        const val FULL_CHANNEL_BATCH_SIZE = 256
    }
}

internal object FavoriteChannelIdentityReconciliation {
    fun collectMatchingIds(
        rows: List<FavoriteChannelIdentityRow>,
        logicalKeys: Set<String>,
        destination: MutableSet<Long>
    ) {
        if (logicalKeys.isEmpty()) return
        rows.forEach { row ->
            val logicalKey = ChannelStableIdentity.key(
                tvgId = row.tvgId,
                name = row.name,
                streamUrl = row.streamUrl
            )
            if (logicalKey in logicalKeys) {
                destination += row.id
            }
        }
    }
}

internal data class UnifiedFavoriteBatch(
    val favorite: FavoriteChannelEntity,
    val variants: List<FavoriteChannelVariantEntity>
)

internal data class UnifiedFavoriteMigrationBatch(
    val favorites: List<FavoriteChannelEntity>,
    val variants: List<FavoriteChannelVariantEntity>
)

/** Pure deterministic transformations used by the repository and unit tests. */
internal object UnifiedFavoritePersistence {
    fun logicalKey(channel: ChannelEntity): String = ChannelStableIdentity.key(
        tvgId = channel.tvgId,
        name = channel.name,
        streamUrl = channel.streamUrl
    )

    fun fromLiveChannels(
        logicalKey: String,
        preferred: ChannelEntity,
        equivalents: List<ChannelEntity>,
        playlists: Map<Long, PlaylistEntity?>,
        addedAt: Long,
        updatedAt: Long
    ): UnifiedFavoriteBatch {
        require(logicalKey.isNotBlank()) { "Favorite logical key must not be blank" }
        require(equivalents.isNotEmpty()) { "Favorite must contain at least one source variant" }

        val favorite = FavoriteChannelEntity(
            logicalKey = logicalKey,
            tvgId = preferred.tvgId,
            name = preferred.name,
            groupName = preferred.groupName,
            logo = preferred.logo,
            preferredStreamUrl = preferred.streamUrl,
            preferredPlaylistId = preferred.playlistId,
            preferredChannelId = preferred.id,
            addedAt = addedAt,
            updatedAt = updatedAt
        )
        val variants = equivalents
            .map { channel ->
                val playlist = playlists[channel.playlistId]
                FavoriteChannelVariantEntity(
                    logicalKey = logicalKey,
                    variantKey = variantKey(channel.streamUrl),
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
                    addedAt = addedAt,
                    updatedAt = updatedAt
                )
            }
            .distinctBy(FavoriteChannelVariantEntity::variantKey)

        return UnifiedFavoriteBatch(favorite = favorite, variants = variants)
    }

    fun fromLegacySeeds(
        seeds: List<FavoriteLegacySeedEntity>,
        updatedAt: Long
    ): UnifiedFavoriteMigrationBatch {
        val favorites = mutableListOf<FavoriteChannelEntity>()
        val variants = mutableListOf<FavoriteChannelVariantEntity>()

        seeds.groupBy { seed ->
            ChannelStableIdentity.key(
                tvgId = seed.tvgId,
                name = seed.name,
                streamUrl = seed.streamUrl
            )
        }.toSortedMap().forEach { (logicalKey, group) ->
            val ordered = group.sortedWith(
                compareBy<FavoriteLegacySeedEntity> { it.addedAt }
                    .thenBy { it.legacyChannelId }
            )
            val preferred = ordered.first()
            favorites += FavoriteChannelEntity(
                logicalKey = logicalKey,
                tvgId = preferred.tvgId,
                name = preferred.name,
                groupName = preferred.groupName,
                logo = preferred.logo,
                preferredStreamUrl = preferred.streamUrl,
                preferredPlaylistId = preferred.playlistId,
                preferredChannelId = preferred.legacyChannelId,
                addedAt = ordered.minOf(FavoriteLegacySeedEntity::addedAt),
                updatedAt = updatedAt
            )
            variants += ordered
                .map { seed ->
                    FavoriteChannelVariantEntity(
                        logicalKey = logicalKey,
                        variantKey = variantKey(seed.streamUrl),
                        legacyChannelId = seed.legacyChannelId,
                        playlistId = seed.playlistId,
                        playlistName = seed.playlistName,
                        sourceType = seed.sourceType,
                        catalogOrigin = seed.catalogOrigin,
                        tvgId = seed.tvgId,
                        name = seed.name,
                        groupName = seed.groupName,
                        logo = seed.logo,
                        streamUrl = seed.streamUrl,
                        addedAt = seed.addedAt,
                        updatedAt = updatedAt
                    )
                }
                .distinctBy(FavoriteChannelVariantEntity::variantKey)
        }

        return UnifiedFavoriteMigrationBatch(
            favorites = favorites,
            variants = variants
        )
    }

    fun representFavorites(
        favorites: List<FavoriteChannelEntity>,
        liveChannels: List<ChannelEntity>
    ): List<Channel> {
        val liveByLogicalKey = liveChannels.groupBy(::logicalKey)
        return favorites.map { favorite ->
            val liveVariants = liveByLogicalKey[favorite.logicalKey].orEmpty()
            val live = liveVariants.firstOrNull { it.id == favorite.preferredChannelId }
                ?: liveVariants.firstOrNull()
            if (live != null) {
                live.toModelSafe()
            } else {
                Channel(
                    id = favorite.preferredChannelId,
                    playlistId = favorite.preferredPlaylistId,
                    tvgId = favorite.tvgId,
                    name = favorite.name,
                    group = favorite.groupName,
                    logo = favorite.logo,
                    streamUrl = favorite.preferredStreamUrl,
                    health = ChannelHealth.UNKNOWN,
                    orderIndex = 0,
                    isHidden = false
                )
            }
        }
    }

    fun favoriteLiveChannelIds(
        favorites: List<FavoriteChannelEntity>,
        liveChannels: List<ChannelEntity>
    ): Set<Long> {
        val logicalKeys = favorites.mapTo(hashSetOf(), FavoriteChannelEntity::logicalKey)
        return liveChannels
            .asSequence()
            .filter { logicalKey(it) in logicalKeys }
            .mapTo(linkedSetOf(), ChannelEntity::id)
    }

    fun resolvePlaybackContext(
        requestedChannelId: Long,
        favorite: FavoriteChannelEntity,
        persistedVariants: List<FavoriteChannelVariantEntity>,
        liveChannels: List<ChannelEntity>
    ): FavoritePlaybackContext {
        val liveVariants = liveChannels
            .filter { channel -> logicalKey(channel) == favorite.logicalKey }
            .sortedWith(
                compareBy<ChannelEntity> { it.playlistId }
                    .thenBy { it.orderIndex }
                    .thenBy { it.id }
            )
        val savedVariants = persistedVariants
            .filter { variant -> variant.logicalKey == favorite.logicalKey }
            .sortedWith(
                compareByDescending<FavoriteChannelVariantEntity> { it.updatedAt }
                    .thenBy { it.variantKey }
            )

        val selectedLive = liveVariants.firstOrNull { it.id == requestedChannelId }
            ?: liveVariants.firstOrNull { it.id == favorite.preferredChannelId }
            ?: liveVariants.firstOrNull()

        val selectedSaved = if (selectedLive == null) {
            savedVariants.firstOrNull { it.legacyChannelId == requestedChannelId }
                ?: savedVariants.firstOrNull { it.legacyChannelId == favorite.preferredChannelId }
                ?: savedVariants.firstOrNull { it.streamUrl.trim() == favorite.preferredStreamUrl.trim() }
                ?: savedVariants.firstOrNull()
        } else {
            null
        }

        val selectedChannel = when {
            selectedLive != null -> selectedLive.toModelSafe()
            selectedSaved != null -> selectedSaved.toModelSafe()
            else -> Channel(
                id = favorite.preferredChannelId,
                playlistId = favorite.preferredPlaylistId,
                tvgId = favorite.tvgId,
                name = favorite.name,
                group = favorite.groupName,
                logo = favorite.logo,
                streamUrl = favorite.preferredStreamUrl,
                health = ChannelHealth.UNKNOWN,
                orderIndex = 0,
                isHidden = false
            )
        }

        val uniqueVariantKeys = buildSet {
            liveVariants.forEach { channel -> add(variantKey(channel.streamUrl)) }
            savedVariants.forEach { variant -> add(variant.variantKey) }
            add(variantKey(favorite.preferredStreamUrl))
        }

        return FavoritePlaybackContext(
            logicalKey = favorite.logicalKey,
            channel = selectedChannel,
            selectedVariantKey = when {
                selectedLive != null -> variantKey(selectedLive.streamUrl)
                selectedSaved != null -> selectedSaved.variantKey
                else -> variantKey(favorite.preferredStreamUrl)
            },
            isLiveVariant = selectedLive != null,
            availableVariantCount = uniqueVariantKeys.size.coerceAtLeast(1)
        )
    }

    fun variantKey(streamUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(streamUrl.trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun ChannelEntity.toModelSafe(): Channel = Channel(
        id = id,
        playlistId = playlistId,
        tvgId = tvgId,
        name = name,
        group = groupName,
        logo = logo,
        streamUrl = streamUrl,
        health = runCatching { ChannelHealth.valueOf(health) }.getOrDefault(ChannelHealth.UNKNOWN),
        orderIndex = orderIndex,
        isHidden = isHidden,
        catchUp = if (
            catchUpMode == null &&
            catchUpDays == null &&
            catchUpSourceTemplate == null &&
            !catchUpDaysDeclared
        ) {
            null
        } else {
            ChannelCatchUpMetadata(
                mode = catchUpMode,
                days = catchUpDays,
                sourceTemplate = catchUpSourceTemplate,
                daysDeclared = catchUpDaysDeclared
            )
        }
    )

    private fun FavoriteChannelVariantEntity.toModelSafe(): Channel = Channel(
        id = legacyChannelId,
        playlistId = playlistId,
        tvgId = tvgId,
        name = name,
        group = groupName,
        logo = logo,
        streamUrl = streamUrl,
        health = ChannelHealth.UNKNOWN,
        orderIndex = 0,
        isHidden = false
    )
}
