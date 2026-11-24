package dev.dsh.miuixbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM：请求头拼装与 HLS 判定（Intent 往返依赖 Android，不在这里测）。 */
class PlayerRequestTest {
    @Test fun headersOnlyIncludeNonBlank() {
        assertEquals(emptyMap<String, String>(), PlayerRequest("https://a/b.mp4").headers())
        assertEquals(
            mapOf("Referer" to "https://a/", "Cookie" to "k=v"),
            PlayerRequest("https://a/b.mp4", referer = "https://a/", cookie = "k=v").headers(),
        )
    }

    @Test fun hlsDetection() {
        assertTrue(PlayerRequest("https://a/x/index.m3u8").isHls())
        assertTrue(PlayerRequest("https://a/x/index.M3U8?token=1#t").isHls())
        assertFalse(PlayerRequest("https://a/x/video.mp4?u=a.m3u8").isHls())
        assertTrue(PlayerRequest("https://a/play?id=1", type = "hls").isHls())
        assertFalse(PlayerRequest("https://a/x/index.m3u8", type = "mp4").isHls())
    }
}
