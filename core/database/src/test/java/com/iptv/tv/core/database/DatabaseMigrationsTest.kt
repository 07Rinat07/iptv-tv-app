package com.iptv.tv.core.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun migration9To10_createsStandaloneFavoriteStorageAndSnapshotsLegacyRows() {
        assertEquals(9, MIGRATION_9_10.startVersion)
        assertEquals(10, MIGRATION_9_10.endVersion)
        assertEquals(11, MIGRATION_9_10_SQL.size)
        assertTrue(MIGRATION_9_10_SQL[0].contains("favorite_channels"))
        assertTrue(MIGRATION_9_10_SQL[4].contains("favorite_channel_variants"))
        assertTrue(MIGRATION_9_10_SQL[8].contains("favorite_legacy_seeds"))
        assertEquals(
            "INSERT OR REPLACE INTO favorite_legacy_seeds (legacyChannelId, playlistId, playlistName, sourceType, catalogOrigin, tvgId, name, groupName, logo, streamUrl, addedAt) SELECT f.channelId, c.playlistId, p.name, p.sourceType, p.catalogOrigin, c.tvgId, c.name, c.groupName, c.logo, c.streamUrl, f.addedAt FROM favorites f INNER JOIN channels c ON c.id = f.channelId LEFT JOIN playlists p ON p.id = c.playlistId",
            MIGRATION_9_10_SQL.last()
        )
    }

    @Test
    fun migration10To11_addsFailClosedCatchUpMetadataColumns() {
        assertEquals(10, MIGRATION_10_11.startVersion)
        assertEquals(11, MIGRATION_10_11.endVersion)
        assertEquals(
            listOf(
                "ALTER TABLE channels ADD COLUMN catchUpMode TEXT",
                "ALTER TABLE channels ADD COLUMN catchUpDays INTEGER",
                "ALTER TABLE channels ADD COLUMN catchUpSourceTemplate TEXT",
                "ALTER TABLE channels ADD COLUMN catchUpDaysDeclared INTEGER NOT NULL DEFAULT 0"
            ),
            MIGRATION_10_11_SQL
        )
    }

    @Test
    fun migration11To12_createsNormalizedPersistentEpgSnapshotStorage() {
        assertEquals(11, MIGRATION_11_12.startVersion)
        assertEquals(12, MIGRATION_11_12.endVersion)
        assertEquals(8, MIGRATION_11_12_SQL.size)

        assertTrue(MIGRATION_11_12_SQL[0].contains("epg_snapshot_sources"))
        assertTrue(MIGRATION_11_12_SQL[0].contains("PRIMARY KEY(`sourceUrl`)"))
        assertTrue(MIGRATION_11_12_SQL[2].contains("epg_snapshot_display_names"))
        assertTrue(
            MIGRATION_11_12_SQL[2].contains(
                "PRIMARY KEY(`sourceUrl`, `channelId`, `displayName`)"
            )
        )
        assertTrue(MIGRATION_11_12_SQL[5].contains("epg_snapshot_programs"))
        assertTrue(
            MIGRATION_11_12_SQL[5].contains(
                "PRIMARY KEY(`sourceUrl`, `channelId`, `startEpochMs`, `endEpochMs`, `title`)"
            )
        )
        assertTrue(MIGRATION_11_12_SQL[7].contains("sourceUrl_channelId_startEpochMs"))
    }
}
