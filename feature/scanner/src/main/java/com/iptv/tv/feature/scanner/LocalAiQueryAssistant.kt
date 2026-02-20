package com.iptv.tv.feature.scanner

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

        variants += "$normalized playlist m3u8"
        variants += "$normalized channels m3u"
        variants += "$normalized live tv m3u8"
        variants += "$normalized tv channel list m3u8"
        variants += "$normalized iptv channel list m3u"
        if (keywordChunk.isNotBlank()) {
            variants += "$normalized $keywordChunk m3u"
        }

        detectIntents(lowered).forEach { intent ->
            intentTemplates[intent].orEmpty().forEach { template ->
                variants += template
            }
        }

        val transliterated = transliterateCyrillicToLatin(normalized)
        if (transliterated != normalized) {
            variants += "$transliterated iptv playlist m3u8"
            variants += "$transliterated channels m3u"
        }

        variants += "public iptv m3u playlist"
        variants += "iptv channels list m3u8"
        variants += "iptv m3u github"
        variants += "iptv m3u gitlab"
        variants += "iptv channel list m3u"
        variants += "список каналов iptv m3u"
        variants += "списки m3u m3u8 iptv"

        val webFallbackIntents = detectIntents(lowered)
        if (webFallbackIntents.isNotEmpty()) {
            webFallbackIntents.forEach { intent ->
                webIntentTemplates[intent].orEmpty().forEach { template ->
                    variants += template
                }
            }
        }

        return variants
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 4 }
            .filterNot { it.equals(normalized, ignoreCase = true) }
            .take(MAX_VARIANTS)
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

    private fun detectIntents(text: String): Set<Intent> {
        val lowered = text.lowercase()
        return buildSet {
            if (containsAny(lowered, russianMarkers)) add(Intent.RUSSIAN)
            if (containsAny(lowered, worldMarkers)) add(Intent.WORLD)
            if (containsAny(lowered, sportMarkers)) add(Intent.SPORT)
            if (containsAny(lowered, movieMarkers)) add(Intent.MOVIE)
            if (containsAny(lowered, newsMarkers)) add(Intent.NEWS)
            if (containsAny(lowered, kidsMarkers)) add(Intent.KIDS)
            if (containsAny(lowered, musicMarkers)) add(Intent.MUSIC)
        }
    }

    private fun containsAny(text: String, markers: Set<String>): Boolean {
        return markers.any { marker -> text.contains(marker) }
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
        WORLD,
        SPORT,
        MOVIE,
        NEWS,
        KIDS,
        MUSIC
    }

    private companion object {
        const val MAX_VARIANTS = 14
        const val MAX_KEYWORDS = 20

        val russianMarkers = setOf("рус", "росс", "russian", "russia", "снг")
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

        val intentKeywords = mapOf(
            Intent.RUSSIAN to listOf("russian", "russia", "ru", "рус", "россия", "каналы", "список"),
            Intent.WORLD to listOf("world", "global", "international", "countries", "tv channels"),
            Intent.SPORT to listOf("sport", "football", "soccer", "hockey", "basketball", "теннис"),
            Intent.MOVIE to listOf(
                "movie", "film", "cinema", "vod", "series", "serial", "action", "thriller", "horror",
                "кино", "сериалы", "боевик", "ужасы"
            ),
            Intent.NEWS to listOf("news", "live", "breaking", "headlines", "новости"),
            Intent.KIDS to listOf("kids", "cartoon", "animation", "family", "мультфильмы"),
            Intent.MUSIC to listOf("music", "radio", "audio", "hits", "музыка")
        )

        val intentTemplates = mapOf(
            Intent.RUSSIAN to listOf(
                "russian tv channels iptv m3u8",
                "russia iptv playlist m3u",
                "русские каналы iptv m3u",
                "список каналов iptv россия m3u8"
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
            )
        )

        val webIntentTemplates = mapOf(
            Intent.RUSSIAN to listOf(
                "russian iptv m3u github",
                "русские iptv m3u gitlab",
                "список русских каналов iptv m3u github"
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
            )
        )
    }
}
