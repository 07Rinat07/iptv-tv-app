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
interface EpgSnapshotDao {
    @Query("SELECT * FROM epg_snapshot_sources WHERE sourceUrl = :sourceUrl LIMIT 1")
    suspend fun findSource(sourceUrl: String): EpgSnapshotSourceEntity?

    @Query("SELECT * FROM epg_snapshot_sources ORDER BY loadedAtMs ASC, sourceUrl ASC")
    suspend fun getSourcesOldestFirst(): List<EpgSnapshotSourceEntity>

    @Query(
        "SELECT * FROM epg_snapshot_display_names " +
            "WHERE sourceUrl = :sourceUrl ORDER BY channelId ASC, displayName ASC"
    )
    suspend fun getDisplayNames(sourceUrl: String): List<EpgSnapshotDisplayNameEntity>

    @Query(
        "SELECT * FROM epg_snapshot_programs " +
            "WHERE sourceUrl = :sourceUrl " +
            "ORDER BY channelId ASC, startEpochMs ASC, endEpochMs ASC, title ASC"
    )
    suspend fun getPrograms(sourceUrl: String): List<EpgSnapshotProgramEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: EpgSnapshotSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDisplayNames(items: List<EpgSnapshotDisplayNameEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(items: List<EpgSnapshotProgramEntity>)

    @Query("DELETE FROM epg_snapshot_display_names WHERE sourceUrl = :sourceUrl")
    suspend fun deleteDisplayNames(sourceUrl: String): Int

    @Query("DELETE FROM epg_snapshot_programs WHERE sourceUrl = :sourceUrl")
    suspend fun deletePrograms(sourceUrl: String): Int

    @Query("DELETE FROM epg_snapshot_sources WHERE sourceUrl = :sourceUrl")
    suspend fun deleteSource(sourceUrl: String): Int

    /** Atomically replaces one source snapshot; failed writes roll back to the previous snapshot. */
    @Transaction
    suspend fun replaceSnapshot(
        source: EpgSnapshotSourceEntity,
        displayNames: List<EpgSnapshotDisplayNameEntity>,
        programs: List<EpgSnapshotProgramEntity>
    ) {
        require(displayNames.all { it.sourceUrl == source.sourceUrl })
        require(programs.all { it.sourceUrl == source.sourceUrl })

        upsertSource(source)
        deleteDisplayNames(source.sourceUrl)
        deletePrograms(source.sourceUrl)
        if (displayNames.isNotEmpty()) insertDisplayNames(displayNames)
        if (programs.isNotEmpty()) insertPrograms(programs)
    }

    @Transaction
    suspend fun deleteSnapshot(sourceUrl: String) {
        deleteDisplayNames(sourceUrl)
        deletePrograms(sourceUrl)
        deleteSource(sourceUrl)
    }
}
