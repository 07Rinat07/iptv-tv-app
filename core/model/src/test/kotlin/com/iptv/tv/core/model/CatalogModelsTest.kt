package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogModelsTest {
    private val provenance = CatalogProvenance(
        origin = CatalogOriginKind.USER_IMPORT,
        sourceKey = "user-playlist:demo",
        sourceType = PlaylistSourceType.URL
    )

    @Test
    fun rootIdentityIsDeterministicForSameCanonicalInput() {
        val first = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist-main"
        )
        val second = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist-main"
        )

        assertEquals(first, second)
    }

    @Test
    fun rootIdentityChangesWhenSourceProvenanceChanges() {
        val first = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist-main"
        )
        val second = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance.copy(sourceKey = "user-playlist:other"),
            stableKey = "playlist-main"
        )

        assertNotEquals(first, second)
    }

    @Test
    fun childIdentityIsScopedByParent() {
        val firstParent = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist-a"
        )
        val secondParent = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist-b"
        )

        val first = CatalogNodeIdFactory.child(CatalogNodeKind.CHANNEL, firstParent, "channel-1")
        val second = CatalogNodeIdFactory.child(CatalogNodeKind.CHANNEL, secondParent, "channel-1")

        assertNotEquals(first, second)
    }

    @Test
    fun lengthPrefixedEncodingAvoidsSegmentBoundaryCollisions() {
        val first = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.CATALOG,
            provenance = provenance.copy(sourceKey = "ab"),
            stableKey = "c"
        )
        val second = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.CATALOG,
            provenance = provenance.copy(sourceKey = "a"),
            stableKey = "bc"
        )

        assertNotEquals(first, second)
    }

    @Test
    fun displayNameCanChangeWithoutChangingCanonicalIdentity() {
        val id = CatalogNodeIdFactory.root(
            kind = CatalogNodeKind.PLAYLIST,
            provenance = provenance,
            stableKey = "playlist-main"
        )
        val original = CanonicalCatalogNode(
            id = id,
            kind = CatalogNodeKind.PLAYLIST,
            name = "Новости",
            parentId = null,
            order = 0,
            provenance = provenance
        )
        val renamed = original.copy(name = "Новости HD")

        assertEquals(original.id, renamed.id)
        assertNull(original.parentId)
    }
}
