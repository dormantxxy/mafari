package dev.dsh.miuixbrowser.sniff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 纯 JVM：嗅探的地址 / 响应头判定。 */
class MediaSniffTest {

    @Test fun hlsByExtension() {
        assertEquals("hls", MediaSniff.classify("https://cdn.example.com/a/index.m3u8"))
        assertEquals("hls", MediaSniff.classify("https://cdn.example.com/a/index.M3U8?token=abc&x=1.mp4"))
    }

    @Test fun hlsByContentTypeWithoutExtension() {
        assertEquals("hls", MediaSniff.classify("https://x.com/api/play?id=1", "application/vnd.apple.mpegurl; charset=utf-8"))
        assertEquals("hls", MediaSniff.classify("https://x.com/api/play?id=1", "application/x-mpegURL"))
    }

    @Test fun progressiveFiles() {
        assertEquals("mp4", MediaSniff.classify("https://x.com/v/1.mp4"))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v/1.m4v?e=1"))
        assertEquals("webm", MediaSniff.classify("https://x.com/v/1.webm"))
        assertEquals("flv", MediaSniff.classify("https://x.com/v/1.flv"))
        assertEquals("mkv", MediaSniff.classify("https://x.com/v/1", "video/x-matroska"))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v/1", "video/mp4"))
    }

    @Test fun segmentsAreRejected() {
        assertNull(MediaSniff.classify("https://x.com/seg/1.ts"))
        assertNull(MediaSniff.classify("https://x.com/seg/1.m4s"))
        assertNull(MediaSniff.classify("https://x.com/seg/1", "video/mp2t"))
        assertNull(MediaSniff.classify("https://x.com/seg/1.mp4", "video/iso.segment"))
    }

    @Test fun nonVideoIsRejected() {
        assertNull(MediaSniff.classify("https://x.com/a.js"))
        assertNull(MediaSniff.classify("https://x.com/a.jpg", "image/jpeg"))
        assertNull(MediaSniff.classify("https://x.com/a.mpd", "application/dash+xml"))
        assertNull(MediaSniff.classify("https://x.com/a", "text/html"))
        assertNull(MediaSniff.classify("blob:https://x.com/1234", "video/mp4"))
        assertNull(MediaSniff.classify("data:video/mp4;base64,AAAA"))
        assertNull(MediaSniff.classify("https://x.com/" + "a".repeat(5000) + ".mp4"))
    }

    @Test fun tinyFilesAreRejectedButSmallPlaylistsAreNot() {
        assertNull(MediaSniff.classify("https://x.com/ad.mp4", null, 50_000))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v.mp4", null, 5_000_000))
        assertEquals("mp4", MediaSniff.classify("https://x.com/v.mp4", null, -1))
        assertEquals("hls", MediaSniff.classify("https://x.com/i.m3u8", null, 300))
    }

    @Test fun mediaElementWithoutAnyHint() {
        assertEquals("mp4", MediaSniff.classify("https://x.com/stream/42", null, -1, fromMediaElement = true))
        assertNull(MediaSniff.classify("https://x.com/stream/42", null, -1, fromMediaElement = false))
        assertEquals("hls", MediaSniff.classify("https://x.com/stream/42.m3u8", null, -1, fromMediaElement = true))
    }

    @Test fun hostAndDisplayName() {
        assertEquals("cdn.example.com", MediaSniff.hostOf("https://user@CDN.Example.com:8443/a/b.mp4?x=1"))
        val m = SniffedMedia("https://cdn.example.com/a/movie.mp4?x=1", "mp4", "cdn.example.com")
        assertEquals("movie.mp4", MediaSniff.displayName(m))
        val bare = SniffedMedia("https://cdn.example.com/?x=1", "mp4", "cdn.example.com")
        assertEquals("cdn.example.com", MediaSniff.displayName(bare))
    }
}
