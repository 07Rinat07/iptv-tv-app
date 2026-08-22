package com.iptv.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("SELECT * FROM favorite_channel_variants ORDER BY logicalKey ASC, updatedAt DESC")
    fun observeFavoriteVariants(): Flow<List<FavoriteChannelVariantEntity>>

    @Query("SELECT * FROM favorite_channels ORDER BY addedAt DESC, logicalKey ASC")
    suspend fun getFavoriteChannels(): List<FavoriteChannelEntity>

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

/**
 * Read-only channel observation dedicated to the logical Favorites layer.
 *
 * A separate DAO avoids expanding the legacy ChannelDao contract while allowing favorite identity
 * to be recomputed whenever imports/re-imports add or remove concrete channel rows.
 */
@Dao
interface FavoriteChannelLookupDao {
    @Query("SELECT * FROM channels ORDER BY playlistId ASC, orderIndex ASC, id ASC")
    fun observeAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY playlistId ASC, orderIndex ASC, id ASC")
    suspend fun getAllChannels(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE id = :channelId LIMIT 1")
    suspend fun findChannelById(channelId: Long): ChannelEntity?
}
