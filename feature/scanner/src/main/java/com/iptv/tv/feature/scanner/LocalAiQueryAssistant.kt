package com.iptv.tv.feature.scanner

import com.iptv.tv.core.model.ScannerLearnedQuery

internal class LocalAiQueryAssistant {

    fun inferIntentKeywords(query: String, manualKeywords: List<String>): List<String> {
        val lowered = "$query ${manualKeywords.joinToString(" ")}".lowercase()
        val keywords = linkedSetOf<String>()

        keywords += "iptv"
        keywords += "playlist"
        keywords += "tv"
        keywords += "channels"
        keywords += "list"
        keywords += "m3u"
        keywords += "m3u8"
        keywords += "каналы"
        keywords += "список"
        keywords += "списки"

        detectIntents(lowered).forEach { intent ->
            keywords += intentKeywords[intent].orEmpty()
        }

        manualKeywords
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .forEach { keywords += it }

        return keywords
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_KEYWORDS)
    }

    fun buildAiVariants(
        query: String,
        manualKeywords: List<String>,
        inferredKeywords: List<String>
    ): List<String> {
        val normalized = normalizeQuery(query)
        val lowered = "$normalized ${manualKeywords.joinToString(" ")}".lowercase()
        val variants = linkedSetOf<String>()

        val keywordChunk = inferredKeywords
            .filterNot { it in setOf("iptv", "playlist", "m3u", "m3u8") }
            .take(4)
            .joinToString(" ")

        variants += "$normalized iptv playlist m3u8"
        variants += "$normalized iptv channels m3u"
        variants += "$normalized tv channel list m3u8"
        variants += "$normalized live tv channels m3u8"

        detectIntents(lowered).forEach { intent ->
            intentTemplates[intent].orEmpty().forEach { template ->
                variants += template
            }
            webIntentTemplates[intent].orEmpty().forEach { template ->
                variants += template
            }
        }

        if (keywordChunk.isNotBlank()) {
            variants += "$normalized $keywordChunk iptv m3u"
        }

        val transliterated = transliterateCyrillicToLatin(normalized)
        if (transliterated != normalized) {
            variants += "$transliterated iptv playlist m3u8"
            variants += "$transliterated channels m3u"
        }

        variants += "public iptv m3u playlist"
        variants += "free public iptv channel list m3u"
        variants += "iptv channels list m3u8"
        variants += "iptv m3u github"
        variants += "iptv m3u gitlab"
        variants += "site:github.com iptv m3u playlist"
        variants += "site:gitlab.com iptv m3u playlist"
        variants += "site:bitbucket.org iptv m3u playlist"
        variants += "site:raw.githubusercontent.com iptv m3u"
        variants += "site:raw.githubusercontent.com iptv m3u8"
        variants += "site:gitlab.com raw iptv m3u"
        variants += "iptv channel list m3u"
        variants += "список каналов iptv m3u"
        variants += "списки m3u m3u8 iptv"

        if (lowered.contains("voxlist")) {
            variants += "voxlist iptv m3u"
            variants += "voxlist playlist m3u8"
            variants += "site:github.com voxlist m3u"
            variants += "site:gitlab.com voxlist m3u"
        }

        return variants
            .map(::normalizeVariantQuery)
            .filter { it.length >= 4 }
            .filterNot { it.equals(normalized, ignoreCase = true) }
            .take(MAX_VARIANTS)
    }

    fun buildLearnedVariants(
        baseQuery: String,
        presetId: String?,
        intentKeywords: List<String>,
        learnedQueries: List<ScannerLearnedQuery>,
        limit: Int = MAX_LEARNED_VARIANTS
    ): List<String> {
        val normalizedBase = normalizeQuery(baseQuery)
        val normalizedBaseLower = normalizedBase.lowercase()
        val baseTokensAll = extractQueryTokens(normalizedBase)
        val baseTokensInformative = baseTokensAll.filterNot { it in nonInformativeQueryTokens }.toSet()
        val intentSetInformative = intentKeywords
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .filterNot { it in nonInformativeQueryTokens }
            .toSet()
        val normalizedPreset = presetId?.trim()?.ifBlank { null }

        return learnedQueries
            .asSequence()
            .flatMap { entry ->
                val normalizedCandidate = normalizeQuery(entry.query)
                if (normalizedCandidate.isBlank()) return@flatMap emptySequence()
                if (normalizedCandidate.lowercase() == normalizedBaseLower) return@flatMap emptySequence()

                val candidateTokensAll = extractQueryTokens(normalizedCandidate)
                val candidateTokensInformative = candidateTokensAll.filterNot { it in nonInformativeQueryTokens }.toSet()
                val overlapAll = candidateTokensAll.intersect(baseTokensAll).size
                val overlapInformative = candidateTokensInformative.intersect(baseTokensInformative).size
                val intentOverlap = candidateTokensInformative.intersect(intentSetInformative).size
                val hasBaseInformative = baseTokensInformative.isNotEmpty()
                val presetMatches = normalizedPreset != null && entry.presetId == normalizedPreset
                val presetFallbackAllowed = presetMatches && entry.hits >= MIN_PRESET_FALLBACK_HITS

                if (hasBaseInformative && overlapInformative == 0 && intentOverlap == 0 && !presetFallbackAllowed) {
                    return@flatMap emptySequence()
                }

                val presetBoost = if (presetMatches) 4 else 0
                val recencyBoost = (entry.lastSuccessAt / DAY_MS).coerceAtLeast(0L).toInt() % 3
                val overlapScore = if (hasBaseInformative) overlapInformative * 6 else overlapAll * 3
                val baseScore = (entry.hits * 3) + overlapScore + (intentOverlap * 4) + presetBoost + recencyBoost
                if (baseScore <= 0) return@flatMap emptySequence()

                learnedVariantCandidates(
                    normalizedBase = normalizedBase,
                    normalizedCandidate = normalizedCandidate,
                    candidateTokensInformative = candidateTokensInformative,
                    intentSetInformative = intentSetInformative,
                    baseScore = baseScore,
                    hits = entry.hits,
                    lastSuccessAt = entry.lastSuccessAt
                ).asSequence()
            }
            .sortedWith(
                compareByDescending<LearnedQueryCandidate> { it.score }
                    .thenByDescending { it.hits }
                    .thenByDescending { it.lastSuccessAt }
            )
            .map { it.query }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    private fun learnedVariantCandidates(
        normalizedBase: String,
        normalizedCandidate: String,
        candidateTokensInformative: Set<String>,
        intentSetInformative: Set<String>,
        baseScore: Int,
        hits: Int,
        lastSuccessAt: Long
    ): List<LearnedQueryCandidate> {
        val variants = mutableListOf<LearnedQueryCandidate>()
        fun add(raw: String, scoreBoost: Int) {
            val query = normalizeVariantQuery(raw)
            if (query.isNotBlank()) {
                variants += LearnedQueryCandidate(
                    query = query,
                    score = baseScore + scoreBoost,
                    hits = hits,
                    lastSuccessAt = lastSuccessAt
                )
            }
        }

        add(normalizedCandidate, 4)

        val intentHint = intentSetInformative.take(2).joinToString(" ")
        if (intentHint.isNotBlank()) {
            add("$normalizedCandidate $intentHint iptv m3u8", 2)
        }

        val learnedHint = candidateTokensInformative
            .filterNot { normalizedBase.contains(it, ignoreCase = true) }
            .take(2)
            .joinToString(" ")
        if (learnedHint.isNotBlank()) {
            add("$normalizedBase $learnedHint iptv m3u", 1)
        }

        return variants
    }

    private fun normalizeVariantQuery(raw: String): String {
        val compact = raw.replace(Regex("\\s+"), " ").trim()
        if (compact.isBlank()) return compact

        val seen = linkedSetOf<String>()
        val normalized = mutableListOf<String>()
        compact.split(" ").forEach { token ->
            val clean = token.trim()
            if (clean.isBlank()) return@forEach
            val key = clean.trim(',', ';', '.').lowercase()
            if (key.isBlank()) return@forEach
            if (seen.add(key)) {
                normalized += clean
            }
        }
        return normalized.joinToString(" ")
    }

    private fun normalizeQuery(raw: String): String {
        val compact = raw
            .trim()
            .replace(Regex("\\s+"), " ")
            .trim(',', '.', ';')
        if (compact.isBlank()) return compact

        val lowered = compact.lowercase()
        return if (lowered.contains("iptv") || lowered.contains("m3u")) compact else "iptv $compact"
    }

    private fun containsAny(text: String, markers: Set<String>): Boolean {
        return markers.any { marker -> text.contains(marker) }
    }

    private fun extractQueryTokens(raw: String): Set<String> {
        return raw.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun transliterateCyrillicToLatin(text: String): String {
        val out = StringBuilder(text.length * 2)
        text.forEach { ch ->
            out.append(
                when (ch.lowercaseChar()) {
                    'а' -> "a"
                    'б' -> "b"
                    'в' -> "v"
                    'г' -> "g"
                    'д' -> "d"
                    'е' -> "e"
                    'ё' -> "yo"
                    'ж' -> "zh"
                    'з' -> "z"
                    'и' -> "i"
                    'й' -> "y"
                    'к' -> "k"
                    'л' -> "l"
                    'м' -> "m"
                    'н' -> "n"
                    'о' -> "o"
                    'п' -> "p"
                    'р' -> "r"
                    'с' -> "s"
                    'т' -> "t"
                    'у' -> "u"
                    'ф' -> "f"
                    'х' -> "h"
                    'ц' -> "ts"
                    'ч' -> "ch"
                    'ш' -> "sh"
                    'щ' -> "sch"
                    'ъ' -> ""
                    'ы' -> "y"
                    'ь' -> ""
                    'э' -> "e"
                    'ю' -> "yu"
                    'я' -> "ya"
                    else -> ch.toString()
                }
            )
        }
        return out.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private enum class Intent {
        RUSSIAN,
        TURKEY,
        WORLD,
        SPORT,
        MOVIE,
        NEWS,
        KIDS,
        MUSIC,
        FREE,
        LISTS
    }

    private companion object {
        const val MAX_VARIANTS = 18
        const val MAX_KEYWORDS = 20
        const val MAX_LEARNED_VARIANTS = 4
        const val MIN_PRESET_FALLBACK_HITS = 3
        const val DAY_MS = 86_400_000L

        val russianMarkers = setOf("рус", "росс", "russian", "russia", "снг")
        val turkeyMarkers = setOf(
            "turkey", "turkiye", "turkish", "turk", "turkce", "türkiye", "türk",
            "turcei", "turkei", "турция", "турец", "турк"
        )
        val worldMarkers = setOf("world", "global", "international", "мир", "стран")
        val sportMarkers = setOf(
            "sport", "sports", "football", "soccer", "hockey", "basketball", "tennis", "mma", "ufc",
            "спорт", "футбол", "хоккей", "баскетбол", "теннис"
        )
        val movieMarkers = setOf(
            "movie", "movies", "film", "films", "cinema", "vod", "series", "serial",
            "action", "thriller", "horror",
            "кино", "фильм", "фильмы", "сериал", "сериалы", "боевик", "экшен", "триллер", "ужасы"
        )
        val newsMarkers = setOf("news", "новост", "breaking", "business", "headlines", "live news", "новости")
        val kidsMarkers = setOf("kids", "дет", "cartoon", "cartoons", "animation", "family", "мульт", "мультфильм")
        val musicMarkers = setOf("music", "radio", "audio", "hits", "songs", "музыка", "радио")
        val freeMarkers = setOf("free", "public", "open", "бесплат", "открыт", "gratis")
        val listMarkers = setOf("list", "lists", "playlist", "channels", "tv list", "channel list", "список", "списки", "каналы", "voxlist")
        val nonInformativeQueryTokens = setOf(
            "iptv", "playlist", "playlists", "m3u", "m3u8",
            "tv", "tvs", "live", "stream", "streams", "vod",
            "channel", "channels", "list", "lists",
            "канал", "каналы", "список", "списки", "плейлист", "тв",
            "github", "gitlab", "bitbucket", "raw"
        )

        val intentKeywords = mapOf(
            Intent.RUSSIAN to listOf("russian", "russia", "ru", "рус", "россия", "каналы", "список"),
            Intent.TURKEY to listOf("turkey", "turkiye", "turkish", "turk", "turkce", "tr", "каналы"),
            Intent.WORLD to listOf("world", "global", "international", "countries", "tv channels"),
            Intent.SPORT to listOf("sport", "football", "soccer", "hockey", "basketball", "теннис"),
            Intent.MOVIE to listOf(
                "movie", "film", "cinema", "vod", "series", "serial", "action", "thriller", "horror",
                "кино", "сериалы", "боевик", "ужасы"
            ),
            Intent.NEWS to listOf("news", "live", "breaking", "headlines", "новости"),
            Intent.KIDS to listOf("kids", "cartoon", "animation", "family", "мультфильмы"),
            Intent.MUSIC to listOf("music", "radio", "audio", "hits", "музыка"),
            Intent.FREE to listOf("free", "public", "open", "бесплатно", "бесплатные"),
            Intent.LISTS to listOf("channel list", "tv list", "playlist", "список каналов", "списки тв")
        )

        val intentTemplates = mapOf(
            Intent.RUSSIAN to listOf(
                "russian tv channels iptv m3u8",
                "russia iptv playlist m3u",
                "русские каналы iptv m3u",
                "список каналов iptv россия m3u8"
            ),
            Intent.TURKEY to listOf(
                "turkey turkiye iptv playlist m3u8",
                "turkish tv channels iptv m3u",
                "turkce canli tv iptv m3u8",
                "turkiye kanal listesi iptv m3u"
            ),
            Intent.WORLD to listOf(
                "world tv channels iptv m3u8",
                "global international iptv playlist",
                "countries channels m3u iptv",
                "world iptv channel list m3u"
            ),
            Intent.SPORT to listOf(
                "sport live channels iptv m3u8",
                "football hockey sports playlist m3u",
                "sports tv channel list iptv m3u8"
            ),
            Intent.MOVIE to listOf(
                "movie vod iptv playlist m3u8",
                "cinema films channels m3u iptv",
                "series action thriller horror iptv m3u8",
                "кино сериалы боевик триллер ужасы iptv m3u"
            ),
            Intent.NEWS to listOf(
                "news channels live iptv m3u8",
                "новостные каналы iptv m3u"
            ),
            Intent.KIDS to listOf(
                "kids cartoon channels iptv m3u8",
                "детские мультфильмы каналы iptv m3u8"
            ),
            Intent.MUSIC to listOf(
                "music radio channels iptv m3u8",
                "музыка радио каналы iptv m3u"
            ),
            Intent.FREE to listOf(
                "free public iptv playlist m3u",
                "open iptv channel list m3u8",
                "бесплатные каналы iptv m3u"
            ),
            Intent.LISTS to listOf(
                "iptv channel list m3u8",
                "tv channels list iptv m3u",
                "списки тв каналов iptv m3u8",
                "список каналов iptv m3u"
            )
        )

        val webIntentTemplates = mapOf(
            Intent.RUSSIAN to listOf(
                "russian iptv m3u github",
                "русские iptv m3u gitlab",
                "список русских каналов iptv m3u github"
            ),
            Intent.TURKEY to listOf(
                "turkey iptv m3u github",
                "turkiye iptv m3u gitlab",
                "turkish channels iptv m3u8 github"
            ),
            Intent.WORLD to listOf(
                "world iptv m3u github",
                "global iptv m3u gitlab",
                "world iptv channel list m3u github"
            ),
            Intent.SPORT to listOf(
                "sport iptv m3u github",
                "football iptv m3u gitlab",
                "sports iptv channel list m3u8 github"
            ),
            Intent.MOVIE to listOf(
                "movie iptv m3u github",
                "vod iptv m3u gitlab",
                "series action thriller horror iptv m3u github"
            ),
            Intent.NEWS to listOf(
                "news iptv m3u github",
                "новости iptv m3u gitlab"
            ),
            Intent.KIDS to listOf(
                "kids iptv m3u github",
                "мультфильмы iptv m3u gitlab"
            ),
            Intent.MUSIC to listOf(
                "radio iptv m3u github",
                "music iptv m3u8 gitlab"
            ),
            Intent.FREE to listOf(
                "free iptv m3u github",
                "public iptv playlist m3u gitlab",
                "бесплатные iptv m3u github"
            ),
            Intent.LISTS to listOf(
                "iptv channel list m3u github",
                "tv channels list m3u gitlab",
                "список каналов iptv m3u github"
            )
        )
    }

    private fun detectIntents(text: String): Set<Intent> {
        val lowered = text.lowercase()
        return buildSet {
            if (containsAny(lowered, russianMarkers)) add(Intent.RUSSIAN)
            if (containsAny(lowered, turkeyMarkers)) add(Intent.TURKEY)
            if (containsAny(lowered, worldMarkers)) add(Intent.WORLD)
            if (containsAny(lowered, sportMarkers)) add(Intent.SPORT)
            if (containsAny(lowered, movieMarkers)) add(Intent.MOVIE)
            if (containsAny(lowered, newsMarkers)) add(Intent.NEWS)
            if (containsAny(lowered, kidsMarkers)) add(Intent.KIDS)
            if (containsAny(lowered, musicMarkers)) add(Intent.MUSIC)
            if (containsAny(lowered, freeMarkers)) add(Intent.FREE)
            if (containsAny(lowered, listMarkers)) add(Intent.LISTS)
        }
    }

    private data class LearnedQueryCandidate(
        val query: String,
        val score: Int,
        val hits: Int,
        val lastSuccessAt: Long
    )
}
