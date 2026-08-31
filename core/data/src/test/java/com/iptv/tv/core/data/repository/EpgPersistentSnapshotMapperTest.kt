package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.EpgStoredSnapshot
import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EpgPersistentSnapshotMapperTest {
    @Test
    fun roundTripPreservesSourceAliasesAndProgramMetadata() {
        val payload = EpgPersistentSnapshotPayload(
            sourceUrl = "https://epg.example/guide.xml.gz",
            loadedAtMs = 42_000L,
            channelDisplayNames = linkedMapOf(
                "discovery.hd" to linkedSetOf("Discovery HD", "Discovery Channel"),
                "natgeo.wild" to linkedSetOf("Nat Geo Wild")
            ),
            programsByChannel = linkedMapOf(
                "discovery.hd" to listOf(
                    EpgProgram(
                        title = "How It's Made",
                        description = "Factory tour",
                        category = "Documentary",
                        startEpochMs = 10_000L,
                        endEpochMs = 20_000L
                    )
                ),
                "natgeo.wild" to listOf(
                    EpgProgram(
                        title = "Wild Russia",
                        description = null,
                        category = "Nature",
                        startEpochMs = 30_000L,
                        endEpochMs = 40_000L
                    )
                )
            )
        )

        val restored = EpgPersistentSnapshotMapper.restore(
            EpgStoredSnapshot(
                source = EpgPersistentSnapshotMapper.source(payload),
                displayNames = EpgPersistentSnapshotMapper.displayNames(payload),
                programs = EpgPersistentSnapshotMapper.programs(payload)
            )
        )

        assertEquals(payload.sourceUrl, restored.sourceUrl)
        assertEquals(payload.loadedAtMs, restored.loadedAtMs)
        assertEquals(payload.channelDisplayNames, restored.channelDisplayNames)
        assertEquals(payload.programsByChannel, restored.programsByChannel)
    }

    @Test
    fun restoreDropsRowsFromAnotherSourceAndMalformedPrograms() {
        val sourceUrl = "https://epg.example/guide.xml"
        val payload = EpgPersistentSnapshotPayload(
            sourceUrl = sourceUrl,
            loadedAtMs = 1_000L,
            channelDisplayNames = mapOf("one" to setOf("One")),
            programsByChannel = mapOf(
                "one" to listOf(
                    EpgProgram(
                        title = "Valid",
                        description = null,
                        category = null,
                        startEpochMs = 100L,
                        endEpochMs = 200L
                    )
                )
            )
        )
        val source = EpgPersistentSnapshotMapper.source(payload)
        val displayNames = EpgPersistentSnapshotMapper.displayNames(payload).toMutableList().apply {
            add(first().copy(sourceUrl = "https://other.example/guide.xml", displayName = "Other"))
        }
        val programs = EpgPersistentSnapshotMapper.programs(payload).toMutableList().apply {
            add(
                first().copy(
                    sourceUrl = sourceUrl,
                    title = "Broken",
                    startEpochMs = 300L,
                    endEpochMs = 300L
                )
            )
        }

        val restored = EpgPersistentSnapshotMapper.restore(
            EpgStoredSnapshot(source, displayNames, programs)
        )

        assertEquals(setOf("One"), restored.channelDisplayNames["one"])
        assertEquals(listOf("Valid"), restored.programsByChannel["one"]?.map(EpgProgram::title))
        assertFalse(restored.channelDisplayNames.values.flatten().contains("Other"))
    }
}
