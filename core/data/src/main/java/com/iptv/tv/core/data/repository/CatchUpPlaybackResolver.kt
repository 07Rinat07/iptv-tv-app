package com.iptv.tv.core.data.repository

import com.iptv.tv.core.parser.ChannelCatchUpMetadata

internal data class CatchUpPlaybackResolution(
    val playbackUrl: String?,
    val supported: Boolean,
    val reason: String?
)

/**
 * Builds an archive playback URL only from explicit provider catch-up metadata.
 *
 * This policy intentionally supports a bounded subset of M3U catch-up modes. Provider-specific
 * URL families (for example Flussonic/Xtream) stay unsupported until they have their own tested
 * contracts; the resolver must never infer archive capability from a live URL alone.
 */
internal object CatchUpPlaybackResolver {
    private val supportedTemplateTokens = linkedMapOf<String, (CatchUpWindow) -> String>(
        "${'$'}{start}" to { it.startEpochSeconds.toString() },
        "${'$'}{timestamp}" to { it.nowEpochSeconds.toString() },
        "${'$'}{end}" to { it.endEpochSeconds.toString() },
        "${'$'}{duration}" to { it.durationSeconds.toString() },
        "{start}" to { it.startEpochSeconds.toString() },
        "{utc}" to { it.startEpochSeconds.toString() },
        "{end}" to { it.endEpochSeconds.toString() },
        "{duration}" to { it.durationSeconds.toString() }
    )

    fun resolve(
        rawLiveStreamUrl: String,
        metadata: ChannelCatchUpMetadata?,
        programStartEpochMs: Long,
        programEndEpochMs: Long,
        nowEpochMs: Long
    ): CatchUpPlaybackResolution {
        val mode = metadata?.mode?.trim()?.lowercase().orEmpty()
        if (metadata == null || mode.isBlank()) {
            return unsupported("Archive metadata is not declared")
        }
        if (metadata.daysDeclared && metadata.days == null) {
            return unsupported("Declared catchup-days is invalid")
        }
        if (programStartEpochMs < 0L || programEndEpochMs <= programStartEpochMs) {
            return unsupported("Archive programme window is invalid")
        }
        if (programEndEpochMs > nowEpochMs) {
            return unsupported("Archive programme has not finished yet")
        }

        val window = CatchUpWindow(
            startEpochSeconds = programStartEpochMs / 1_000L,
            endEpochSeconds = programEndEpochMs / 1_000L,
            nowEpochSeconds = nowEpochMs / 1_000L
        )
        if (window.durationSeconds <= 0L) {
            return unsupported("Archive programme duration is invalid")
        }
        val declaredDays = metadata.days
        if (declaredDays != null) {
            val earliestAllowed = window.nowEpochSeconds - declaredDays.toLong() * SECONDS_PER_DAY
            if (window.startEpochSeconds < earliestAllowed) {
                return unsupported("Programme is outside the declared catch-up window")
            }
        }

        val (liveUrl, transportSuffix) = splitTransportSuffix(rawLiveStreamUrl)
        if (!isHttpUrl(liveUrl)) {
            return unsupported("Catch-up currently requires an HTTP/HTTPS live URL")
        }

        val resolvedBase = when (mode) {
            "append" -> {
                val template = metadata.sourceTemplate?.trim().orEmpty()
                if (template.isBlank()) return unsupported("Append catch-up requires catchup-source")
                val rendered = renderTemplate(template, window)
                    ?: return unsupported("Catch-up template contains unsupported placeholders")
                appendBeforeFragment(liveUrl, rendered)
            }
            "default" -> {
                val template = metadata.sourceTemplate?.trim().orEmpty()
                if (template.isBlank()) return unsupported("Default catch-up requires an explicit catchup-source")
                val rendered = renderTemplate(template, window)
                    ?: return unsupported("Catch-up template contains unsupported placeholders")
                if (!isHttpUrl(rendered)) {
                    return unsupported("Default catch-up source must resolve to an absolute HTTP/HTTPS URL")
                }
                rendered
            }
            "shift", "timeshift" -> {
                val (base, fragment) = splitFragment(liveUrl)
                val delimiter = if ('?' in base) '&' else '?'
                "$base${delimiter}utc=${window.startEpochSeconds}&lutc=${window.nowEpochSeconds}$fragment"
            }
            else -> return unsupported("Catch-up mode '$mode' is not supported yet")
        }

        return CatchUpPlaybackResolution(
            playbackUrl = resolvedBase + transportSuffix,
            supported = true,
            reason = null
        )
    }

    private fun renderTemplate(template: String, window: CatchUpWindow): String? {
        var rendered = template
        supportedTemplateTokens.forEach { (token, value) ->
            rendered = rendered.replace(token, value(window))
        }
        return rendered.takeUnless { UNRESOLVED_PLACEHOLDER_PREFIX.containsMatchIn(it) }
    }

    private fun appendBeforeFragment(url: String, suffix: String): String {
        val (base, fragment) = splitFragment(url)
        return base + suffix + fragment
    }

    private fun splitFragment(url: String): Pair<String, String> {
        val separator = url.indexOf('#')
        if (separator < 0) return url to ""
        return url.substring(0, separator) to url.substring(separator)
    }

    private fun splitTransportSuffix(rawUrl: String): Pair<String, String> {
        val separator = rawUrl.indexOf('|')
        if (separator < 0) return rawUrl.trim() to ""
        return rawUrl.substring(0, separator).trim() to rawUrl.substring(separator)
    }

    private fun isHttpUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun unsupported(reason: String) = CatchUpPlaybackResolution(
        playbackUrl = null,
        supported = false,
        reason = reason
    )

    private data class CatchUpWindow(
        val startEpochSeconds: Long,
        val endEpochSeconds: Long,
        val nowEpochSeconds: Long
    ) {
        val durationSeconds: Long = endEpochSeconds - startEpochSeconds
    }

    private const val SECONDS_PER_DAY = 24L * 60L * 60L
    private val UNRESOLVED_PLACEHOLDER_PREFIX = Regex("""\$\{|\{[A-Za-z]""")
}
