package com.iptv.tv.core.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogoCatalogResolver private constructor(
    private val assetReader: (() -> String?)?,
    baseEntries: List<LogoCatalogEntry>
) {
    private val entries: List<LogoCatalogEntry> by lazy {
        baseEntries + parseEntries(assetReader?.invoke())
    }

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        assetReader = {
            try {
                context.assets.open(LOGO_CATALOG_ASSET).bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                null
            }
        },
        baseEntries = DEFAULT_ENTRIES
    )

    constructor() : this(assetReader = null, baseEntries = DEFAULT_ENTRIES)

    internal constructor(baseEntries: List<LogoCatalogEntry>) : this(assetReader = null, baseEntries = baseEntries)

    internal constructor(baseEntries: List<LogoCatalogEntry>, packJson: String) : this(
        assetReader = { packJson },
        baseEntries = baseEntries
    )

    fun resolve(
        name: String,
        tvgId: String?,
        playlistSource: String? = null
    ): ResolvedLogo? {
        val tvgKeys = tvgIdKeys(tvgId)
        if (tvgKeys.isNotEmpty()) {
            entries.firstOrNull { entry -> tvgKeys.any { it in entry.tvgIds } }?.let { entry ->
                return ResolvedLogo(entry.logoUrl, "logo-pack:tvg-id", entry)
            }
        }

        val normalizedName = normalizeName(name)
        entries.firstOrNull { entry -> entry.names.any { normalizedName.contains(it) } }?.let { entry ->
            return ResolvedLogo(entry.logoUrl, "logo-pack:name", entry)
        }

        val host = runCatching { URI(playlistSource.orEmpty()).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        entries.firstOrNull { entry -> entry.sourceHosts.any { host.contains(it) } }?.let { entry ->
            return ResolvedLogo(entry.logoUrl, "logo-pack:source", entry)
        }

        return null
    }

    data class ResolvedLogo(
        val url: String,
        val source: String,
        val entry: LogoCatalogEntry
    )

    data class LogoCatalogEntry(
        val logoUrl: String,
        val tvgIds: Set<String> = emptySet(),
        val names: Set<String> = emptySet(),
        val sourceHosts: Set<String> = emptySet(),
        val country: String? = null,
        val language: String? = null,
        val category: String? = null
    )

    companion object {
        private const val LOGO_CATALOG_ASSET = "logo_catalog.json"

        fun normalizeName(value: String): String {
            return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
                .trim()
        }

        private fun tvgIdKeys(tvgId: String?): List<String> {
            val key = tvgId
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.replace(Regex("[^a-z0-9._-]"), "")
                .orEmpty()
            if (key.isBlank()) return emptyList()
            return listOf(key, key.substringBeforeLast('.')).distinct()
        }

        private fun parseEntries(rawJson: String?): List<LogoCatalogEntry> {
            if (rawJson.isNullOrBlank()) return emptyList()
            val root = parseLogoArray(rawJson) ?: return emptyList()
            return buildList {
                for (index in 0 until root.length()) {
                    val item = root.optJSONObject(index) ?: continue
                    val logoUrl = item.firstString("logo", "logoUrl", "url", "icon", "image")
                    if (logoUrl.isBlank()) continue
                    add(
                        LogoCatalogEntry(
                            logoUrl = logoUrl,
                            tvgIds = item.optStringSet("tvgIds", "tvgId", "tvg-id", "channelId", "id") {
                                it.normalizeTvgId()
                            },
                            names = item.optStringSet("names", "name", "title", "channel") { normalizeName(it) },
                            sourceHosts = item.optStringSet("sourceHosts", "sourceHost", "domains", "domain", "host") {
                                it.trim().lowercase(Locale.ROOT)
                            },
                            country = item.firstString("country", "countryCode").ifBlank { null },
                            language = item.firstString("language", "lang").ifBlank { null },
                            category = item.firstString("category", "group", "genre").ifBlank { null }
                        )
                    )
                }
            }
        }

        private fun parseLogoArray(rawJson: String): JSONArray? {
            runCatching { JSONArray(rawJson) }.getOrNull()?.let { return it }
            val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
            return root.optJSONArray("logos")
                ?: root.optJSONArray("channels")
                ?: root.optJSONArray("items")
                ?: JSONArray().put(root)
        }

        private fun JSONObject.optStringSet(
            vararg keys: String,
            transform: (String) -> String
        ): Set<String> {
            return buildSet {
                keys.forEach { key ->
                    val array = optJSONArray(key)
                    if (array != null) {
                        for (index in 0 until array.length()) {
                            transform(array.optString(index))
                                .takeIf { it.isNotBlank() }
                                ?.let(::add)
                        }
                    } else {
                        transform(optString(key))
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
            }
        }

        private fun JSONObject.firstString(vararg keys: String): String {
            return keys.firstNotNullOfOrNull { key ->
                optString(key).trim().takeIf { it.isNotBlank() }
            }.orEmpty()
        }

        private fun String.normalizeTvgId(): String {
            return trim()
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9._-]"), "")
        }

        private val DEFAULT_ENTRIES = listOf(
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/4/47/BBC_One_logo.svg",
                tvgIds = setOf("bbcone.uk", "bbc1.uk"),
                names = setOf("bbc one"),
                country = "UK",
                language = "en",
                category = "News"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/6/62/BBC_Two_logo_2021.svg",
                tvgIds = setOf("bbctwo.uk", "bbc2.uk"),
                names = setOf("bbc two"),
                country = "UK",
                language = "en"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/b/b1/CNN.svg",
                tvgIds = setOf("cnn.us"),
                names = setOf("cnn"),
                country = "US",
                language = "en",
                category = "News"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/5/58/Euronews_2016_logo.svg",
                tvgIds = setOf("euronews.fr"),
                names = setOf("euronews"),
                country = "FR",
                category = "News"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/4/43/Discovery_Channel_Logo.svg",
                tvgIds = setOf("discoverychannel.us"),
                names = setOf("discovery")
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/0/04/Cartoon_Network_2010_logo.svg",
                tvgIds = setOf("cartoonnetwork.us"),
                names = setOf("cartoon network"),
                category = "Kids"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/e/ea/MTV_Logo_2010.svg",
                tvgIds = setOf("mtv.us"),
                names = setOf("mtv"),
                category = "Music"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/6/6e/Nickelodeon_2023_logo_%28outline%29.svg",
                tvgIds = setOf("nickelodeon.us"),
                names = setOf("nickelodeon"),
                category = "Kids"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2b/Animal_Planet_logo_2018.svg",
                tvgIds = setOf("animalplanet.us"),
                names = setOf("animal planet")
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/f/fc/Natgeologo.svg",
                tvgIds = setOf("natgeo.us"),
                names = setOf("national geographic")
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/c/c2/TNT_Logo_2016.svg",
                tvgIds = setOf("tnt.us"),
                names = setOf("tnt")
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/e/e7/Eurosport_2023.svg",
                names = setOf("eurosport"),
                category = "Sports"
            ),
            LogoCatalogEntry(
                logoUrl = "https://upload.wikimedia.org/wikipedia/commons/2/2f/ESPN_wordmark.svg",
                names = setOf("espn"),
                category = "Sports"
            ),
            LogoCatalogEntry(
                logoUrl = "https://iptv-org.github.io/assets/logo.png",
                sourceHosts = setOf("iptv-org")
            )
        )
    }
}
