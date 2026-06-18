package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.DownloadSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceClassifierTest {

    @Test
    fun classify_detectsKnownDownloadSourceTypes() {
        assertEquals(
            DownloadSourceType.MAGNET,
            DownloadSourceClassifier.classify("magnet:?xt=urn:btih:123")
        )
        assertEquals(
            DownloadSourceType.ACESTREAM,
            DownloadSourceClassifier.classify("acestream://abcdef")
        )
        assertEquals(
            DownloadSourceType.TORRENT_FILE,
            DownloadSourceClassifier.classify("https://example.com/live/channel.torrent?token=1")
        )
        assertEquals(
            DownloadSourceType.HLS_PLAYLIST,
            DownloadSourceClassifier.classify("https://example.com/live/channel.m3u8#frag")
        )
        assertEquals(
            DownloadSourceType.HTTP_STREAM,
            DownloadSourceClassifier.classify("https://example.com/live/channel.ts")
        )
        assertEquals(
            DownloadSourceType.LOCAL_FILE,
            DownloadSourceClassifier.classify("/sdcard/Download/movie.mp4")
        )
    }

    @Test
    fun requiresExternalEngine_onlyForTorrentLikeSources() {
        assertTrue(DownloadSourceClassifier.requiresExternalEngine(DownloadSourceType.MAGNET))
        assertTrue(DownloadSourceClassifier.requiresExternalEngine(DownloadSourceType.ACESTREAM))
        assertTrue(DownloadSourceClassifier.requiresExternalEngine(DownloadSourceType.TORRENT_FILE))
        assertFalse(DownloadSourceClassifier.requiresExternalEngine(DownloadSourceType.HLS_PLAYLIST))
        assertFalse(DownloadSourceClassifier.requiresExternalEngine(DownloadSourceType.HTTP_STREAM))
    }
}
