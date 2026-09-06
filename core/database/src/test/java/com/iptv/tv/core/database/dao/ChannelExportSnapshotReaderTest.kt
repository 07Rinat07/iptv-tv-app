package com.iptv.tv.core.database.dao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelExportSnapshotReaderTest {

    @Test
    fun orderIndexWindows_splitsLargeCatalogAtSqliteSafeBoundaries() {
        val windows = ChannelExportSnapshotReader.orderIndexWindows(1800).toList()

        assertEquals(
            listOf(
                0..899,
                900..1799,
                1800..1800
            ),
            windows
        )
    }

    @Test
    fun orderIndexWindows_returnsNoWindowsForEmptyCatalog() {
        assertTrue(ChannelExportSnapshotReader.orderIndexWindows(-1).none())
    }
}
