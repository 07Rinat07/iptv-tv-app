package com.iptv.tv.core.data.repository

import com.iptv.tv.core.parser.M3uParser
import com.iptv.tv.core.parser.ParseResult
import okhttp3.ResponseBody

internal fun parseM3uResponseBody(
    playlistId: Long,
    parser: M3uParser,
    body: ResponseBody?
): ParseResult {
    val responseBody = body ?: return ParseResult.Invalid("Playlist content is empty")
    if (responseBody.contentLength() == 0L) {
        return ParseResult.Invalid("Playlist content is empty")
    }

    return responseBody.charStream().buffered().use { reader ->
        reader.mark(1)
        if (reader.read() == -1) {
            ParseResult.Invalid("Playlist content is empty")
        } else {
            reader.reset()
            parser.parse(playlistId = playlistId, reader = reader)
        }
    }
}
