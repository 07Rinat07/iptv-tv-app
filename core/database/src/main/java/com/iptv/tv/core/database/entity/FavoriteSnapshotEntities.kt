package com.iptv.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable user-owned favorite independent from the lifetime of a playlist/channel Room row.
 *
 * [logicalKey] is produced by the shared ChannelStableIdentity contract in core:model. The
 * preferred legacy IDs are only lookup hints for the current UI/Player compatibility path; the
 * snapshot remains valid when those rows are later deleted.
 */
@Entity(
    tableName = "favorite_channels",
    indices = [
        Index(value = ["addedAt"]),
        Index(value = ["updatedAt"]),
        Index(value = ["preferredChannelId"])
    ]
)
data class FavoriteChannelEntity(
    @PrimaryKey val logicalKey: String,
    val tvgId: String?,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val preferredStreamUrl: String,
    val preferredPlaylistId: Long,
    val preferredChannelId: Long,
    val addedAt: Long,
    val updatedAt: Long
)

/**
 * One independently persisted playback/source variant for a logical favorite channel.
 *
 * Playlist metadata is copied as provenance instead of referenced by foreign key so removing the
 * source playlist never deletes the user's saved channel variant.
 */
@Entity(
    tableName = "favorite_channel_variants",
    primaryKeys = ["logicalKey", "variantKey"],
    indices = [
        Index(value = ["logicalKey"]),
        Index(value = ["legacyChannelId"]),
        Index(value = ["playlistId"])
    ]
)
data class FavoriteChannelVariantEntity(
    val logicalKey: String,
    val variantKey: String,
    val legacyChannelId: Long,
    val playlistId: Long,
    val playlistName: String?,
    val sourceType: String?,
    val catalogOrigin: String?,
    val tvgId: String?,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val streamUrl: String,
    val addedAt: Long,
    val updatedAt: Long
)

/**
 * Raw migration snapshot copied from the legacy favorites -> channels join during database 9->10.
 *
 * Keeping raw fields here is intentional: SQL cannot reproduce ChannelStableIdentity exactly.
 * core:data consolidates these seeds with the canonical Kotlin identity algorithm before clearing
 * this table. The seed therefore protects favorites even if a playlist is deleted immediately
 * after upgrading but before the Favorites screen is opened.
 */
@Entity(
    tableName = "favorite_legacy_seeds",
    indices = [Index(value = ["addedAt"])]
)
data class FavoriteLegacySeedEntity(
    @PrimaryKey val legacyChannelId: Long,
    val playlistId: Long,
    val playlistName: String?,
    val sourceType: String?,
    val catalogOrigin: String?,
    val tvgId: String?,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val streamUrl: String,
    val addedAt: Long
)
