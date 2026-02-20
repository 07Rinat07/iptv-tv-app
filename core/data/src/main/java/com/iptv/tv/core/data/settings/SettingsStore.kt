package com.iptv.tv.core.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsDataStore by preferencesDataStore(name = "iptv_settings")

object SettingsKeys {
    val defaultPlayer: Preferences.Key<String> = stringPreferencesKey("default_player")
    val bufferProfile: Preferences.Key<String> = stringPreferencesKey("buffer_profile")
    val manualStartMs: Preferences.Key<Int> = intPreferencesKey("manual_start_ms")
    val manualRebufferMs: Preferences.Key<Int> = intPreferencesKey("manual_rebuffer_ms")
    val manualMaxMs: Preferences.Key<Int> = intPreferencesKey("manual_max_ms")
    val channelPlayerOverrides: Preferences.Key<String> = stringPreferencesKey("channel_player_overrides")
    val engineEndpoint: Preferences.Key<String> = stringPreferencesKey("engine_endpoint")
    val torEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("tor_enabled")
    val legalAccepted: Preferences.Key<Boolean> = booleanPreferencesKey("legal_accepted")
    val allowInsecureUrls: Preferences.Key<Boolean> = booleanPreferencesKey("allow_insecure_urls")
    val downloadsWifiOnly: Preferences.Key<Boolean> = booleanPreferencesKey("downloads_wifi_only")
    val maxParallelDownloads: Preferences.Key<Int> = intPreferencesKey("downloads_max_parallel")
    val scannerAiEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("scanner_ai_enabled")
    val scannerProxyEnabled: Preferences.Key<Boolean> = booleanPreferencesKey("scanner_proxy_enabled")
    val scannerProxyHost: Preferences.Key<String> = stringPreferencesKey("scanner_proxy_host")
    val scannerProxyPort: Preferences.Key<Int> = intPreferencesKey("scanner_proxy_port")
    val scannerProxyUsername: Preferences.Key<String> = stringPreferencesKey("scanner_proxy_username")
    val scannerProxyPassword: Preferences.Key<String> = stringPreferencesKey("scanner_proxy_password")
    val scannerLearnedQueries: Preferences.Key<String> = stringPreferencesKey("scanner_learned_queries")
}
