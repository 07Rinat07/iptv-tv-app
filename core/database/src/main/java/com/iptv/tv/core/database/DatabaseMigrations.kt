package com.iptv.tv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val MIGRATION_6_7_SQL = listOf(
    "ALTER TABLE downloads ADD COLUMN resolvedSource TEXT",
    "ALTER TABLE downloads ADD COLUMN resolvedSourceType TEXT"
)

internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_6_7_SQL.forEach(db::execSQL)
    }
}

internal val MIGRATION_7_8_SQL = listOf(
    "ALTER TABLE recordings ADD COLUMN progress INTEGER NOT NULL DEFAULT 0"
)

internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_7_8_SQL.forEach(db::execSQL)
    }
}

internal val MIGRATION_8_9_SQL = listOf(
    "ALTER TABLE playlists ADD COLUMN catalogOrigin TEXT NOT NULL DEFAULT 'USER_IMPORT'",
    "UPDATE playlists SET catalogOrigin = 'PROVIDER' WHERE sourceType IN ('XTREAM','STALKER','JELLYFIN','PLEX','TVHEADEND','HDHOMERUN')",
    "UPDATE playlists SET catalogOrigin = 'LOCAL' WHERE sourceType = 'FILE'"
)

internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_8_9_SQL.forEach(db::execSQL)
    }
}

/**
 * Introduces durable logical Favorites without trying to reimplement ChannelStableIdentity in SQL.
 *
 * Existing favorite rows are copied into favorite_legacy_seeds together with the channel/playlist
 * snapshot. core:data later consolidates those raw seeds with the canonical Kotlin identity
 * algorithm. Because the seed contains all playback/display fields, deleting the original playlist
 * after upgrade cannot erase the user's pre-v10 favorites before consolidation runs.
 */
internal val MIGRATION_9_10_SQL = listOf(
    "CREATE TABLE IF NOT EXISTS `favorite_channels` (`logicalKey` TEXT NOT NULL, `tvgId` TEXT, `name` TEXT NOT NULL, `groupName` TEXT, `logo` TEXT, `preferredStreamUrl` TEXT NOT NULL, `preferredPlaylistId` INTEGER NOT NULL, `preferredChannelId` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`logicalKey`))",
    "CREATE INDEX IF NOT EXISTS `index_favorite_channels_addedAt` ON `favorite_channels` (`addedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_favorite_channels_updatedAt` ON `favorite_channels` (`updatedAt`)",
    "CREATE INDEX IF NOT EXISTS `index_favorite_channels_preferredChannelId` ON `favorite_channels` (`preferredChannelId`)",
    "CREATE TABLE IF NOT EXISTS `favorite_channel_variants` (`logicalKey` TEXT NOT NULL, `variantKey` TEXT NOT NULL, `legacyChannelId` INTEGER NOT NULL, `playlistId` INTEGER NOT NULL, `playlistName` TEXT, `sourceType` TEXT, `catalogOrigin` TEXT, `tvgId` TEXT, `name` TEXT NOT NULL, `groupName` TEXT, `logo` TEXT, `streamUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`logicalKey`, `variantKey`))",
    "CREATE INDEX IF NOT EXISTS `index_favorite_channel_variants_logicalKey` ON `favorite_channel_variants` (`logicalKey`)",
    "CREATE INDEX IF NOT EXISTS `index_favorite_channel_variants_legacyChannelId` ON `favorite_channel_variants` (`legacyChannelId`)",
    "CREATE INDEX IF NOT EXISTS `index_favorite_channel_variants_playlistId` ON `favorite_channel_variants` (`playlistId`)",
    "CREATE TABLE IF NOT EXISTS `favorite_legacy_seeds` (`legacyChannelId` INTEGER NOT NULL, `playlistId` INTEGER NOT NULL, `playlistName` TEXT, `sourceType` TEXT, `catalogOrigin` TEXT, `tvgId` TEXT, `name` TEXT NOT NULL, `groupName` TEXT, `logo` TEXT, `streamUrl` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`legacyChannelId`))",
    "CREATE INDEX IF NOT EXISTS `index_favorite_legacy_seeds_addedAt` ON `favorite_legacy_seeds` (`addedAt`)",
    "INSERT OR REPLACE INTO favorite_legacy_seeds (legacyChannelId, playlistId, playlistName, sourceType, catalogOrigin, tvgId, name, groupName, logo, streamUrl, addedAt) SELECT f.channelId, c.playlistId, p.name, p.sourceType, p.catalogOrigin, c.tvgId, c.name, c.groupName, c.logo, c.streamUrl, f.addedAt FROM favorites f INNER JOIN channels c ON c.id = f.channelId LEFT JOIN playlists p ON p.id = c.playlistId"
)

internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_9_10_SQL.forEach(db::execSQL)
    }
}
