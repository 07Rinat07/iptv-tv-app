package com.iptv.tv.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.iptv.tv.core.database.entity.EpgSnapshotDisplayNameEntity
import com.iptv.tv.core.database.entity.EpgSnapshotProgramEntity
import com.iptv.tv.core.database.entity.EpgSnapshotSourceEntity

/** Persistence boundary for bounded parsed XMLTV snapshots. */
@Dao
abstract class EpgSnapshotDao {
    @Query("SELECT * FROM epg_snapshot_sources WHERE sourceUrl = :sourceUrl LIMIT 1")
    abstract suspend fun findSource(sourceUrl: String): EpgSnapshotSourceEntity?

    @Query("SELECT * FROM epg_snapshot_sources ORDER BY loadedAtMs ASC, sourceUrl ASC")
    abstract suspend fun getSourcesOldestFirst(): List<EpgSnapshotSourceEntity>

    @Query(
        "SELECT * FROM epg_snapshot_display_names " +
            "WHERE sourceUrl = :sourceUrl ORDER BY channelId ASC, displayName ASC"
    )
    abstract suspend fun getDisplayNames(sourceUrl: String): List<EpgSnapshotDisplayNameEntity>

    @Query(
        "SELECT * FROM epg_snapshot_programs " +
            "WHERE sourceUrl = :sourceUrl " +
            "ORDER BY channelId ASC, startEpochMs ASC, endEpochMs ASC, title ASC"
    )
    abstract suspend fun getPrograms(sourceUrl: String): List<EpgSnapshotProgramEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSource(source: EpgSnapshotSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDisplayNames(items: List<EpgSnapshotDisplayNameEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPrograms(items: List<EpgSnapshotProgramEntity>)

    @Query("DELETE FROM epg_snapshot_display_names WHERE sourceUrl = :sourceUrl")
    abstract suspend fun deleteDisplayNames(sourceUrl: String): Int

    @Query("DELETE FROM epg_snapshot_programs WHERE sourceUrl = :sourceUrl")
    abstract suspend fun deletePrograms(sourceUrl: String): Int

    @Query("DELETE FROM epg_snapshot_sources WHERE sourceUrl = :sourceUrl")
    abstract suspend fun deleteSource(sourceUrl: String): Int

    /** Reads metadata and all child rows from one consistent Room transaction. */
    @Transaction
    open suspend fun readSnapshot(sourceUrl: String): EpgStoredSnapshot? {
        val source = findSource(sourceUrl) ?: return null
        return EpgStoredSnapshot(
            source = source,
            displayNames = getDisplayNames(sourceUrl),
            programs = getPrograms(sourceUrl)
        )
    }

    /** Atomically replaces one source snapshot; failed writes roll back to the previous snapshot. */
    @Transaction
    open suspend fun replaceSnapshot(
        source: EpgSnapshotSourceEntity,
        displayNames: List<EpgSnapshotDisplayNameEntity>,
        programs: List<EpgSnapshotProgramEntity>
    ) {
        require(displayNames.all { it.sourceUrl == source.sourceUrl }) {
            "All EPG display-name rows must belong to the snapshot source"
        }
        require(programs.all { it.sourceUrl == source.sourceUrl }) {
            "All EPG programme rows must belong to the snapshot source"
        }

        upsertSource(source)
        deleteDisplayNames(source.sourceUrl)
        deletePrograms(source.sourceUrl)
        if (displayNames.isNotEmpty()) insertDisplayNames(displayNames)
        if (programs.isNotEmpty()) insertPrograms(programs)
    }

    @Transaction
    open suspend fun deleteSnapshot(sourceUrl: String) {
        deleteDisplayNames(sourceUrl)
        deletePrograms(sourceUrl)
        deleteSource(sourceUrl)
    }
}

data class EpgStoredSnapshot(
    val source: EpgSnapshotSourceEntity,
    val displayNames: List<EpgSnapshotDisplayNameEntity>,
    val programs: List<EpgSnapshotProgramEntity>
)
