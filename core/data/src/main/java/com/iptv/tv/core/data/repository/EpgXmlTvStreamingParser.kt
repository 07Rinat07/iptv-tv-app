package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.EpgProgram
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

internal data class EpgXmlTvData(
    val channelDisplayNames: Map<String, Set<String>>,
    val programsByChannel: Map<String, List<EpgProgram>>,
    val channelIdByLowercase: Map<String, String>,
    val channelIdByTextKey: Map<String, String>,
    val channelIdByDisplayNameKey: Map<String, String>
)

internal data class EpgXmlTvLimits(
    val maxBytes: Long = 16L * 1024L * 1024L,
    val maxChannels: Int = 20_000,
    val maxProgramsTotal: Int = 80_000,
    val maxProgramsPerChannel: Int = 512,
    val maxDisplayNamesPerChannel: Int = 8,
    val maxTextChars: Int = 2_048
)

internal object EpgXmlTvStreamingParser {
    private val xmlTvTimeRegex =
        Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+\\-]\\d{4})?.*$")

    fun parse(input: InputStream, limits: EpgXmlTvLimits = EpgXmlTvLimits()): EpgXmlTvData {
        require(limits.maxBytes > 0)
        val bounded = CountingBoundedInputStream(input, limits.maxBytes)
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setInput(bounded, Charsets.UTF_8.name())
        }

        val channelDisplayNames = linkedMapOf<String, MutableSet<String>>()
        val programsByChannel = linkedMapOf<String, MutableList<EpgProgram>>()
        var totalPrograms = 0

        try {
            var event = parser.eventType
            var currentChannelId: String? = null
            var currentProgrammeChannel: String? = null
            var currentProgrammeTitle: String? = null
            var currentProgrammeDesc: String? = null
            var currentProgrammeCategory: String? = null
            var currentProgrammeStart: Long? = null
            var currentProgrammeStop: Long? = null

            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id")
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                            if (currentChannelId != null &&
                                currentChannelId !in channelDisplayNames &&
                                channelDisplayNames.size >= limits.maxChannels
                            ) {
                                throw IOException("XMLTV channel limit exceeded: ${limits.maxChannels}")
                            }
                        }

                        "programme" -> {
                            currentProgrammeChannel = parser.getAttributeValue(null, "channel")
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                            currentProgrammeStart = parseXmlTvTime(parser.getAttributeValue(null, "start"))
                            currentProgrammeStop = parseXmlTvTime(parser.getAttributeValue(null, "stop"))
                            currentProgrammeTitle = null
                            currentProgrammeDesc = null
                            currentProgrammeCategory = null
                        }

                        "display-name" -> {
                            val channelId = currentChannelId
                            if (!channelId.isNullOrBlank()) {
                                val value = parser.nextText().trim().take(limits.maxTextChars)
                                if (value.isNotBlank()) {
                                    val names = channelDisplayNames.getOrPut(channelId) { linkedSetOf() }
                                    if (names.size < limits.maxDisplayNamesPerChannel) names += value
                                }
                            }
                        }

                        "title" -> if (!currentProgrammeChannel.isNullOrBlank()) {
                            currentProgrammeTitle = parser.nextText().trim().take(limits.maxTextChars).takeIf(String::isNotBlank)
                        }

                        "desc" -> if (!currentProgrammeChannel.isNullOrBlank()) {
                            currentProgrammeDesc = parser.nextText().trim().take(limits.maxTextChars).takeIf(String::isNotBlank)
                        }

                        "category" -> if (!currentProgrammeChannel.isNullOrBlank()) {
                            currentProgrammeCategory = parser.nextText().trim().take(limits.maxTextChars).takeIf(String::isNotBlank)
                        }
                    }

                    XmlPullParser.END_TAG -> when (parser.name) {
                        "channel" -> currentChannelId = null
                        "programme" -> {
                            val channel = currentProgrammeChannel
                            val start = currentProgrammeStart
                            val stop = currentProgrammeStop
                            val title = currentProgrammeTitle
                            if (!channel.isNullOrBlank() && start != null && stop != null && !title.isNullOrBlank()) {
                                if (totalPrograms >= limits.maxProgramsTotal) {
                                    throw IOException("XMLTV programme limit exceeded: ${limits.maxProgramsTotal}")
                                }
                                val programs = programsByChannel.getOrPut(channel) { mutableListOf() }
                                if (programs.size < limits.maxProgramsPerChannel) {
                                    programs += EpgProgram(
                                        title = title,
                                        description = currentProgrammeDesc,
                                        category = currentProgrammeCategory,
                                        startEpochMs = start,
                                        endEpochMs = stop
                                    )
                                    totalPrograms += 1
                                }
                            }
                            currentProgrammeChannel = null
                            currentProgrammeStart = null
                            currentProgrammeStop = null
                            currentProgrammeTitle = null
                            currentProgrammeDesc = null
                            currentProgrammeCategory = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (throwable: XmlPullParserException) {
            throw IOException("Invalid XMLTV format: ${throwable.message}", throwable)
        }

        val programSnapshot = linkedMapOf<String, List<EpgProgram>>()
        programsByChannel.forEach { (channelId, items) ->
            if (items.isNotEmpty()) {
                items.sortBy(EpgProgram::startEpochMs)
                programSnapshot[channelId] = items
            }
        }
        val displaySnapshot = linkedMapOf<String, Set<String>>()
        channelDisplayNames.forEach { (channelId, names) ->
            displaySnapshot[channelId] = names
        }

        val channelIdByLowercase = programSnapshot.keys.associateByFirst { it.trim().lowercase(Locale.ROOT) }
        val channelIdByTextKey = programSnapshot.keys.associateByFirst(::normalizeTextKey)
        val channelIdByDisplayNameKey = linkedMapOf<String, String>()
        displaySnapshot.forEach { (channelId, names) ->
            names.forEach { name ->
                val key = normalizeTextKey(name)
                if (key.isNotBlank() && key !in channelIdByDisplayNameKey) channelIdByDisplayNameKey[key] = channelId
            }
        }

        return EpgXmlTvData(
            channelDisplayNames = displaySnapshot,
            programsByChannel = programSnapshot,
            channelIdByLowercase = channelIdByLowercase,
            channelIdByTextKey = channelIdByTextKey,
            channelIdByDisplayNameKey = channelIdByDisplayNameKey
        )
    }

    private fun parseXmlTvTime(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val matcher = xmlTvTimeRegex.find(raw.trim()) ?: return null
        val year = matcher.groupValues[1].toIntOrNull() ?: return null
        val month = matcher.groupValues[2].toIntOrNull() ?: return null
        val day = matcher.groupValues[3].toIntOrNull() ?: return null
        val hour = matcher.groupValues[4].toIntOrNull() ?: return null
        val minute = matcher.groupValues[5].toIntOrNull() ?: return null
        val second = matcher.groupValues[6].toIntOrNull() ?: 0
        val zoneRaw = matcher.groupValues.getOrNull(7).orEmpty().trim()
        val utcMs = runCatching {
            GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                clear()
                set(year, month - 1, day, hour, minute, second)
            }.timeInMillis
        }.getOrNull() ?: return null
        if (zoneRaw.isBlank()) return utcMs
        val zoneSign = if (zoneRaw.startsWith("-")) -1 else 1
        val zoneDigits = zoneRaw.removePrefix("+").removePrefix("-").replace(":", "")
        val hh = zoneDigits.take(2).toIntOrNull() ?: 0
        val mm = zoneDigits.drop(2).take(2).toIntOrNull() ?: 0
        return utcMs - (((hh * 60L) + mm) * 60_000L * zoneSign)
    }

    private fun normalizeTextKey(raw: String): String = raw
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun <T> Iterable<T>.associateByFirst(keySelector: (T) -> String): Map<String, T> {
        val result = linkedMapOf<String, T>()
        forEach { value ->
            val key = keySelector(value)
            if (key.isNotBlank() && key !in result) result[key] = value
        }
        return result
    }
}

private class CountingBoundedInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) record(read.toLong())
        return read
    }

    private fun record(delta: Long) {
        count += delta
        if (count > maxBytes) throw IOException("XMLTV input exceeded $maxBytes bytes")
    }
}
