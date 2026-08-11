package com.iptv.tv.feature.importer

import com.iptv.tv.core.model.CatalogOriginKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ImportPrefillBusTest {
    @Before
    fun setUp() {
        ImportPrefillBus.consume()
    }

    @After
    fun tearDown() {
        ImportPrefillBus.consume()
    }

    @Test
    fun preservesExplicitCatalogOrigin() {
        ImportPrefillBus.push(
            ImportPrefill(
                url = "https://example.test/scanner.m3u",
                playlistName = "Scanner result",
                autoImport = true,
                catalogOrigin = CatalogOriginKind.SCANNER_IMPORT
            )
        )

        assertEquals(CatalogOriginKind.SCANNER_IMPORT, ImportPrefillBus.consume()?.catalogOrigin)
    }

    @Test
    fun defaultsManualPrefillToUserImport() {
        ImportPrefillBus.push(
            ImportPrefill(
                url = "https://example.test/manual.m3u",
                playlistName = "Manual",
                autoImport = false
            )
        )

        assertEquals(CatalogOriginKind.USER_IMPORT, ImportPrefillBus.consume()?.catalogOrigin)
    }
}
