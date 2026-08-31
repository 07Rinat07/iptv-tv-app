package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.EpgStoredSnapshot
import com.iptv.tv.core.database.entity.EpgSnapshotDisplayNameEntity
import com.iptv.tv.core.database.entity.EpgSnapshotProgramEntity
import com.iptv.tv.core.database.entity.EpgSnapshotSourceEntity
import com.iptv.tv.core.model.EpgProgram

/** Serializable subset of parsed XMLTV data needed to rebuild runtime matching indexes. */
internal data class EpgPersistentSnapshotPayload(
    val sourceUrl: String,
    val loadedAtMs: Long,
    val channelDisplayNames: Map<String, Set<String>>,
    val programsByChannel: Map<String, List<EpgProgram>>
)

internal object EpgPersistentSnapshotMapper {
    fun source(payload: EpgPersistentSnapshotPayload): EpgSnapshotSourceEntity =
        EpgSnapshotSourceEntity(
            sourceUrl = payload.sourceUrl,
            loadedAtMs = payload.loadedAtMs
        )

    fun displayNames(payload: EpgPersistentSnapshotPayload): List<EpgSnapshotDisplayNameEntity> =
        payload.channelDisplayNames
            .asSequence()
            .flatMap { (channelId, names) ->
                names.asSequence().map { displayName ->
                    EpgSnapshotDisplayNameEntity(
                        sourceUrl = payload.sourceUrl,
                        channelId = channelId,
                        displayName = displayName
                    )
                }
            }
            .sortedWith(compareBy({ it.channelId }, { it.displayName }))
            .toList()

    fun programs(payload: EpgPersistentSnapshotPayload): List<EpgSnapshotProgramEntity> =
        payload.programsByChannel
            .asSequence()
            .flatMap { (channelId, programs) ->
                programs.asSequence().map { program ->
                    EpgSnapshotProgramEntity(
                        sourceUrl = payload.sourceUrl,
                        channelId = channelId,
                        startEpochMs = program.startEpochMs,
                        endEpochMs = program.endEpochMs,
                        title = program.title,
                        description = program.description,
                        category = program.category
                    )
                }
            }
            .sortedWith(
                compareBy<EpgSnapshotProgramEntity> { it.channelId }
                    .thenBy { it.startEpochMs }
                    .thenBy { it.endEpochMs }
                    .thenBy { it.title }
            )
            .toList()

    fun restore(stored: EpgStoredSnapshot): EpgPersistentSnapshotPayload {
        val sourceUrl = stored.source.sourceUrl
        val displayNames = linkedMapOf<String, MutableSet<String>>()
        stored.displayNames.forEach { row ->
            if (
                row.sourceUrl == sourceUrl &&
                row.channelId.isNotBlank() &&
                row.displayName.isNotBlank()
            ) {
                displayNames.getOrPut(row.channelId) { linkedSetOf() } += row.displayName
            }
        }

        val programs = linkedMapOf<String, MutableList<EpgProgram>>()
        stored.programs.forEach { row ->
            if (
                row.sourceUrl == sourceUrl &&
                row.channelId.isNotBlank() &&
                row.title.isNotBlank() &&
                row.endEpochMs > row.startEpochMs
            ) {
                programs.getOrPut(row.channelId) { mutableListOf() } += EpgProgram(
                    title = row.title,
                    description = row.description,
                    category = row.category,
                    startEpochMs = row.startEpochMs,
                    endEpochMs = row.endEpochMs
                )
            }
        }
        programs.values.forEach { items -> items.sortBy(EpgProgram::startEpochMs) }

        return EpgPersistentSnapshotPayload(
            sourceUrl = sourceUrl,
            loadedAtMs = stored.source.loadedAtMs,
            channelDisplayNames = displayNames,
            programsByChannel = programs
        )
    }
}
