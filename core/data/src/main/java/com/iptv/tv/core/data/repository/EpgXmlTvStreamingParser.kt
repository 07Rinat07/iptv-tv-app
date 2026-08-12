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

                        "display-name" -> if (!currentChannelId.isNullOrBlank()) {
                            val value = parser.nextText().trim().take(limits.maxTextChars)
                            if (value.isNotBlank()) {
                                val names = channelDisplayNames.getOrPut(currentChannelId!!) { linkedSetOf() }
                                if (names.size < limits.maxDisplayNamesPerChannel) names += value
                            }
                        }

                        "title" -> if (!currentProgrammeChannel.isNullOrBlank()) {
                            currentProgrammeTitle = parser.nextText().trim().take(limits.maxTextChars)
                                .takeIf(String::isNotBlank)
                        }

                        "desc" -> if (!currentProgrammeChannel.isNullOrBlank()) {
                            currentProgrammeDesc = parser.nextText().trim().take(limits.maxTextChars)
                                .takeIf(String::isNotBlank)
                        }

                        "category" -> if (!currentProgrammeChannel.isNullOrBlank()) {
                            currentProgrammeCategory = parser.nextText().trim().take(limits.maxTextChars)
                                .takeIf(String::isNotBlank)
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
                                val items = programsByChannel.getOrPut(channel) { mutableListOf() }
                                if (items.size < limits.maxProgramsPerChannel) {
                                    if (totalPrograms >= limits.maxProgramsTotal) {
                                        throw IOException("XMLTV programme limit exceeded: ${limits.maxProgramsTotal}")
                                    }
                                    val duplicate = items.lastOrNull()?.let {
                                        it.startEpochMs == start && it.endEpochMs == stop && it.title == title
                                    } == true
                                    if (!duplicate) {
                                        items += EpgProgram(
                                            title = title,
                                            description = currentProgrammeDesc,
                                            category = currentProgrammeCategory,
                                            startEpochMs = start,
                                            endEpochMs = stop
                                        )
                                        totalPrograms += 1
                                    }
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

        programsByChannel.values.forEach { it.sortBy(EpgProgram::startEpochMs) }

        val channelIdByLowercase = linkedMapOf<String, String>()
        val channelIdByTextKey = linkedMapOf<String, String>()
        programsByChannel.keys.forEach { channelId ->
            channelId.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank)?.let { key ->
                channelIdByLowercase.putIfAbsent(key, channelId)
            }
            normalizeTextKey(channelId).takeIf(String::isNotBlank)?.let { key ->
                channelIdByTextKey.putIfAbsent(key, channelId)
            }
        }

        val channelIdByDisplayNameKey = linkedMapOf<String, String>()
        channelDisplayNames.forEach { (channelId, names) ->
            names.forEach { displayName ->
                normalizeTextKey(displayName).takeIf(String::isNotBlank)?.let { key ->
                    channelIdByDisplayNameKey.putIfAbsent(key, channelId)
                }
            }
        }

        return EpgXmlTvData(
            channelDisplayNames = channelDisplayNames,
            programsByChannel = programsByChannel,
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
        val offsetMs = ((hh * 60L) + mm) * 60_000L * zoneSign
        return utcMs - offsetMs
    }

    private fun normalizeTextKey(raw: String): String = raw
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}

private class CountingBoundedInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) account(count.toLong())
        return count
    }

    private fun account(count: Long) {
        consumed += count
        if (consumed > maxBytes) {
            throw IOException("XMLTV response exceeded ${maxBytes} bytes")
        }
    }
}
