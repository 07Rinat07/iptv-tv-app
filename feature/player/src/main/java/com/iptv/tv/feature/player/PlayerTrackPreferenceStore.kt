package com.iptv.tv.feature.player

import android.content.Context
import androidx.media3.common.C

internal class PlayerTrackPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAll(): Map<Int, PlayerTrackPreference> {
        return TRACK_TYPES.mapNotNull { trackType ->
            load(trackType)?.let { trackType to it }
        }.toMap()
    }

    fun saveAuto(trackType: Int): Map<Int, PlayerTrackPreference> {
        save(trackType, PlayerTrackPreference(mode = PlayerTrackPreferenceMode.AUTO))
        return loadAll()
    }

    fun saveDisabled(trackType: Int): Map<Int, PlayerTrackPreference> {
        save(trackType, PlayerTrackPreference(mode = PlayerTrackPreferenceMode.DISABLED))
        return loadAll()
    }

    fun saveSelected(trackType: Int, language: String?, label: String?): Map<Int, PlayerTrackPreference> {
        save(
            trackType = trackType,
            preference = PlayerTrackPreference(
                mode = PlayerTrackPreferenceMode.SELECTED,
                language = language?.trim()?.ifBlank { null },
                label = label?.trim()?.ifBlank { null }
            )
        )
        return loadAll()
    }

    fun clearAll(): Map<Int, PlayerTrackPreference> {
        preferences.edit().clear().apply()
        return emptyMap()
    }

    private fun load(trackType: Int): PlayerTrackPreference? {
        val prefix = keyPrefix(trackType)
        val mode = preferences.getString("${prefix}_mode", null)
            ?.let { runCatching { PlayerTrackPreferenceMode.valueOf(it) }.getOrNull() }
            ?: return null
        return PlayerTrackPreference(
            mode = mode,
            language = preferences.getString("${prefix}_language", null),
            label = preferences.getString("${prefix}_label", null)
        )
    }

    private fun save(trackType: Int, preference: PlayerTrackPreference) {
        val prefix = keyPrefix(trackType)
        preferences.edit()
            .putString("${prefix}_mode", preference.mode.name)
            .putString("${prefix}_language", preference.language)
            .putString("${prefix}_label", preference.label)
            .apply()
    }

    private fun keyPrefix(trackType: Int): String {
        return when (trackType) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "text"
            else -> "track_$trackType"
        }
    }

    private companion object {
        const val PREFS_NAME = "player_track_preferences"
        val TRACK_TYPES = listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)
    }
}
