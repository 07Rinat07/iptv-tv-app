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
}
