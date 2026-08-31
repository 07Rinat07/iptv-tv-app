package com.iptv.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/** Durable metadata for one bounded parsed XMLTV snapshot. */
@Entity(
    tableName = "epg_snapshot_sources",
    primaryKeys = ["sourceUrl"],
    indices = [Index(value = ["loadedAtMs"])]
)
data class EpgSnapshotSourceEntity(
    val sourceUrl: String,
    val loadedAtMs: Long
)

/** Human-readable XMLTV aliases retained so channel matching can be rebuilt after process restart. */
@Entity(
    tableName = "epg_snapshot_display_names",
    primaryKeys = ["sourceUrl", "channelId", "displayName"],
    indices = [
        Index(value = ["sourceUrl"]),
        Index(value = ["sourceUrl", "channelId"])
    ]
)
data class EpgSnapshotDisplayNameEntity(
    val sourceUrl: String,
    val channelId: String,
    val displayName: String
)

/** Bounded programme rows retained by the XMLTV parser for now/next and guide windows. */
@Entity(
    tableName = "epg_snapshot_programs",
    primaryKeys = ["sourceUrl", "channelId", "startEpochMs", "endEpochMs", "title"],
    indices = [
        Index(value = ["sourceUrl"]),
        Index(value = ["sourceUrl", "channelId", "startEpochMs"])
    ]
)
data class EpgSnapshotProgramEntity(
    val sourceUrl: String,
    val channelId: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val title: String,
    val description: String?,
    val category: String?
)
