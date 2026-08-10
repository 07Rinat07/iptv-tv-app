package com.iptv.tv.core.database

import org.junit.Assert.assertEquals
import org.junit.Test

class DatabaseMigrationsTest {
    @Test
    fun migration6To7_addsResolvedDownloadColumns() {
        assertEquals(6, MIGRATION_6_7.startVersion)
        assertEquals(7, MIGRATION_6_7.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE downloads ADD COLUMN resolvedSource TEXT",
                "ALTER TABLE downloads ADD COLUMN resolvedSourceType TEXT"
            ),
            MIGRATION_6_7_SQL
        )
    }

    @Test
    fun migration7To8_addsRecordingProgressColumn() {
        assertEquals(7, MIGRATION_7_8.startVersion)
        assertEquals(8, MIGRATION_7_8.endVersion)
        assertEquals(
            listOf("ALTER TABLE recordings ADD COLUMN progress INTEGER NOT NULL DEFAULT 0"),
            MIGRATION_7_8_SQL
        )
    }

    @Test
    fun migration8To9_addsCatalogOriginAndBackfillsUnambiguousSources() {
        assertEquals(8, MIGRATION_8_9.startVersion)
        assertEquals(9, MIGRATION_8_9.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE playlists ADD COLUMN catalogOrigin TEXT NOT NULL DEFAULT 'USER_IMPORT'",
                "UPDATE playlists SET catalogOrigin = 'PROVIDER' WHERE sourceType IN ('XTREAM','STALKER','JELLYFIN','PLEX','TVHEADEND','HDHOMERUN')",
                "UPDATE playlists SET catalogOrigin = 'LOCAL' WHERE sourceType = 'FILE'"
            ),
            MIGRATION_8_9_SQL
        )
    }
}
