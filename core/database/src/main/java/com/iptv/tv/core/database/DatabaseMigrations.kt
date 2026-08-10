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
