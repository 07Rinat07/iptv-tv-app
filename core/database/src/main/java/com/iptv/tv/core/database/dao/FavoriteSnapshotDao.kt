package com.iptv.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.FavoriteLegacySeedEntity
import kotlinx.coroutines.flow.Flow

/** Persistence API for user-owned favorites that survive playlist/channel deletion. */
@Dao
interface FavoriteSnapshotDao {
    @Query("SELECT * FROM favorite_channels ORDER BY addedAt DESC, logicalKey ASC")
    fun observeFavoriteChannels(): Flow<List<FavoriteChannelEntity>>

    @Query("SELECT COUNT(*) FROM favorite_channels")
    fun observeFavoriteChannelCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM favorite_channel_variants")
    fun observeFavoriteVariantCount(): Flow<Int>

    @Query("SELECT * FROM favorite_channel_variants ORDER BY logicalKey ASC, updatedAt DESC")
    fun observeFavoriteVariants(): Flow<List<FavoriteChannelVariantEntity>>

    @Query("SELECT * FROM favorite_channels ORDER BY addedAt DESC, logicalKey ASC")
    suspend fun getFavoriteChannels(): List<FavoriteChannelEntity>

    @Query(
        "SELECT * FROM favorite_channels WHERE preferredChannelId IN (:channelIds) " +
            "ORDER BY addedAt DESC, logicalKey ASC"
    )
    suspend fun findFavoritesByPreferredChannelIds(
        channelIds: List<Long>
    ): List<FavoriteChannelEntity>

    @Query(
        "SELECT * FROM favorite_channel_variants WHERE logicalKey IN (:logicalKeys) " +
            "ORDER BY logicalKey ASC, updatedAt DESC"
    )
    suspend fun findVariantsByLogicalKeys(
        logicalKeys: List<String>
    ): List<FavoriteChannelVariantEntity>

    @Query("SELECT * FROM favorite_channel_variants WHERE logicalKey = :logicalKey ORDER BY updatedAt DESC")
    suspend fun getVariants(logicalKey: String): List<FavoriteChannelVariantEntity>

    @Query("SELECT * FROM favorite_channels WHERE logicalKey = :logicalKey LIMIT 1")
    suspend fun findFavorite(logicalKey: String): FavoriteChannelEntity?

    @Query("SELECT * FROM favorite_channels WHERE preferredChannelId = :channelId LIMIT 1")
    suspend fun findFavoriteByPreferredChannelId(channelId: Long): FavoriteChannelEntity?

    @Query("SELECT * FROM favorite_legacy_seeds ORDER BY addedAt ASC, legacyChannelId ASC")
    suspend fun getLegacySeeds(): List<FavoriteLegacySeedEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(item: FavoriteChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorites(items: List<FavoriteChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVariants(items: List<FavoriteChannelVariantEntity>)

    @Query("DELETE FROM favorite_channels WHERE logicalKey = :logicalKey")
    suspend fun deleteFavorite(logicalKey: String): Int

    @Query("DELETE FROM favorite_channel_variants WHERE logicalKey = :logicalKey")
    suspend fun deleteVariants(logicalKey: String): Int

    @Query(
        "DELETE FROM favorite_channel_variants " +
            "WHERE logicalKey = :logicalKey AND variantKey = :variantKey"
    )
    suspend fun deleteVariant(logicalKey: String, variantKey: String): Int

    @Query("DELETE FROM favorite_legacy_seeds")
    suspend fun clearLegacySeeds(): Int
}

/** Narrow projection used to reconcile stable favorite identity in bounded pages. */
data class FavoriteChannelIdentityRow(
    val id: Long,
    val tvgId: String?,
    val name: String,
    val streamUrl: String
)

/** Narrow projection used only to calculate the parental-filtered All-channels count. */
data class ParentalChannelGateRow(
    val tvgId: String?,
    val name: String,
    val groupName: String?
)

/** Scalar aggregate for the All-channels summary when parental filtering is inactive. */
data class AllChannelsSummaryAggregateRow(
    val totalChannels: Int,
    val visibleChannels: Int,
    val hiddenChannels: Int,
    val channelsWithLogo: Int,
    val channelsWithTvgId: Int,
    val availableChannels: Int,
    val unstableChannels: Int,
    val unavailableChannels: Int,
    val unknownHealthChannels: Int,
    val groupCount: Int
)

/** One bounded top-group row for the All-channels summary. */
data class AllChannelsGroupCountRow(
    val groupName: String,
    val channelCount: Int
)

/** Minimal row required to render one All-channels summary preview. */
data class AllChannelsSummaryPreviewRow(
    val id: Long,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val health: String,
    val isHidden: Boolean
)

/** Consistent bounded summary components captured inside one Room read transaction. */
data class AllChannelsSummarySnapshot(
    val aggregate: AllChannelsSummaryAggregateRow,
    val topGroups: List<AllChannelsGroupCountRow>,
    val previews: List<AllChannelsSummaryPreviewRow>
)

/**
 * Narrow full-scan projection used only when dynamic parental keywords must be evaluated in Kotlin.
 * Stream URLs and catch-up payloads are intentionally excluded from this exceptional path.
 */
data class AllChannelsParentalSummaryRow(
    val id: Long,
    val playlistId: Long,
    val tvgId: String?,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val health: String,
    val orderIndex: Int,
    val isHidden: Boolean
)

/**
 * Channel lookups shared by Favorites and the system-owned All-channels virtual playlist.
 *
 * Favorites must use [observeChannelTableInvalidation], [getChannelIdentityPage] and
 * [findChannelsByIds]. Full ChannelEntity-table calls remain only for explicit compatibility
 * operations and the explicit All-channels channel view. Summary/count hot paths use scalar,
 * bounded or narrow projections instead.
 */
@Dao
interface FavoriteChannelLookupDao {
    @Query("SELECT COUNT(*) FROM channels")
    fun observeChannelTableInvalidation(): Flow<Int>

    @Query("SELECT COUNT(*) FROM channels WHERE isHidden = 0")
    fun observeVisibleChannelCount(): Flow<Int>

    @Query("SELECT tvgId, name, groupName FROM channels WHERE isHidden = 0")
    fun observeVisibleParentalGateRows(): Flow<List<ParentalChannelGateRow>>

    @Query(
        "SELECT id, tvgId, name, streamUrl FROM channels " +
            "WHERE id > :afterId ORDER BY id ASC LIMIT :limit"
    )
    suspend fun getChannelIdentityPage(
        afterId: Long,
        limit: Int
    ): List<FavoriteChannelIdentityRow>

    @Query("SELECT * FROM channels WHERE id IN (:channelIds)")
    suspend fun findChannelsByIds(channelIds: List<Long>): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    suspend fun findChannelById(channelId: Long): ChannelEntity?

    @Query(
        "SELECT " +
            "COUNT(*) AS totalChannels, " +
            "COUNT(CASE WHEN isHidden = 0 THEN 1 END) AS visibleChannels, " +
            "COUNT(CASE WHEN isHidden != 0 THEN 1 END) AS hiddenChannels, " +
            "COUNT(CASE WHEN isHidden = 0 AND logo IS NOT NULL AND TRIM(logo) != '' THEN 1 END) AS channelsWithLogo, " +
            "COUNT(CASE WHEN isHidden = 0 AND tvgId IS NOT NULL AND TRIM(tvgId) != '' THEN 1 END) AS channelsWithTvgId, " +
            "COUNT(CASE WHEN isHidden = 0 AND health = 'AVAILABLE' THEN 1 END) AS availableChannels, " +
            "COUNT(CASE WHEN isHidden = 0 AND health = 'UNSTABLE' THEN 1 END) AS unstableChannels, " +
            "COUNT(CASE WHEN isHidden = 0 AND health = 'UNAVAILABLE' THEN 1 END) AS unavailableChannels, " +
            "COUNT(CASE WHEN isHidden = 0 AND health = 'UNKNOWN' THEN 1 END) AS unknownHealthChannels, " +
            "COUNT(DISTINCT CASE " +
            "WHEN isHidden = 0 AND groupName IS NOT NULL AND TRIM(groupName) != '' " +
            "THEN TRIM(groupName) END) AS groupCount " +
            "FROM channels"
    )
    suspend fun getAllChannelsSummaryAggregate(): AllChannelsSummaryAggregateRow

    @Query(
        "SELECT TRIM(groupName) AS groupName, COUNT(*) AS channelCount " +
            "FROM channels " +
            "WHERE isHidden = 0 AND groupName IS NOT NULL AND TRIM(groupName) != '' " +
            "GROUP BY TRIM(groupName) " +
            "ORDER BY channelCount DESC, groupName ASC " +
            "LIMIT :limit"
    )
    suspend fun getAllChannelsTopGroups(limit: Int): List<AllChannelsGroupCountRow>

    @Query(
        "SELECT id, name, groupName, logo, health, isHidden FROM channels " +
            "WHERE isHidden = 0 " +
            "ORDER BY playlistId ASC, orderIndex ASC, name ASC, id ASC " +
            "LIMIT :limit"
    )
    suspend fun getAllChannelsSummaryPreviews(limit: Int): List<AllChannelsSummaryPreviewRow>

    @Transaction
    suspend fun getAllChannelsSummarySnapshot(
        groupLimit: Int,
        previewLimit: Int
    ): AllChannelsSummarySnapshot {
        return AllChannelsSummarySnapshot(
            aggregate = getAllChannelsSummaryAggregate(),
            topGroups = getAllChannelsTopGroups(groupLimit),
            previews = getAllChannelsSummaryPreviews(previewLimit)
        )
    }

    @Query(
        "SELECT id, playlistId, tvgId, name, groupName, logo, health, orderIndex, isHidden " +
            "FROM channels ORDER BY playlistId ASC, orderIndex ASC, id ASC"
    )
    suspend fun getAllChannelsParentalSummaryRows(): List<AllChannelsParentalSummaryRow>

    @Query("SELECT * FROM channels ORDER BY playlistId ASC, orderIndex ASC, id ASC")
    suspend fun getAllChannels(): List<ChannelEntity>

    @Query("SELECT * FROM channels ORDER BY playlistId ASC, orderIndex ASC, id ASC")
    fun observeAllChannels(): Flow<List<ChannelEntity>>
}
