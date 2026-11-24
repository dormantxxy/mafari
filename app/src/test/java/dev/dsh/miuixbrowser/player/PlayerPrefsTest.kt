package dev.dsh.miuixbrowser.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 纯 JVM：播放器设置的取值收敛（不碰 SharedPreferences）。 */
class PlayerPrefsTest {

    @Test fun renderModeClamps() {
        assertEquals(PlayerPrefs.RENDER_TEXTURE, PlayerPrefs.clampRender(0))
        assertEquals(PlayerPrefs.RENDER_SURFACE, PlayerPrefs.clampRender(1))
        assertEquals(PlayerPrefs.RENDER_TEXTURE, PlayerPrefs.clampRender(7))
        assertEquals(PlayerPrefs.RENDER_TEXTURE, PlayerPrefs.clampRender(-1))
    }

    @Test fun snapKeepsListedValuesAndFallsBackOtherwise() {
        assertEquals(1.5f, PlayerPrefs.snap(1.5f, PlayerPrefs.SPEEDS, 1f), 0f)
        assertEquals(1f, PlayerPrefs.snap(1.3f, PlayerPrefs.SPEEDS, 1f), 0f)
        assertEquals(4f, PlayerPrefs.snap(4f, PlayerPrefs.LONG_PRESS_SPEEDS, 2f), 0f)
        assertEquals(2f, PlayerPrefs.snap(0.5f, PlayerPrefs.LONG_PRESS_SPEEDS, 2f), 0f)
    }

    @Test fun defaultsAreInOptionLists() {
        assertEquals(true, 1f in PlayerPrefs.SPEEDS)
        assertEquals(true, 2f in PlayerPrefs.LONG_PRESS_SPEEDS)
    }

    @Test fun modesClampToDefaultWhenOutOfRange() {
        assertEquals(PlayerPrefs.DECODER_HARDWARE, PlayerPrefs.clampMode(1, PlayerPrefs.DECODER_SOFTWARE))
        assertEquals(PlayerPrefs.DECODER_AUTO, PlayerPrefs.clampMode(9, PlayerPrefs.DECODER_SOFTWARE))
        assertEquals(PlayerPrefs.HDR_AUTO, PlayerPrefs.clampMode(-1, PlayerPrefs.HDR_PREFER_SDR))
        assertEquals(PlayerPrefs.BUFFER_LARGE, PlayerPrefs.clampMode(2, PlayerPrefs.BUFFER_LARGE))
    }

    @Test fun bufferPresetsAreValidForLoadControl() {
        // DefaultLoadControl 要求：最小缓冲 ≥ 起播所需 / 卡顿后恢复所需，最大缓冲 ≥ 最小缓冲
        for (mode in PlayerPrefs.BUFFER_FAST..PlayerPrefs.BUFFER_LARGE) {
            val (min, max, play, rebuffer) = PlayerPrefs.bufferDurationsMs(mode).toList()
            assertEquals(true, min >= play && min >= rebuffer && max >= min)
        }
        // 默认档和改造前的写死值一致
        assertEquals(listOf(15_000, 60_000, 1_000, 2_000), PlayerPrefs.bufferDurationsMs(PlayerPrefs.BUFFER_FAST).toList())
        // 越界模式回到默认档
        assertEquals(PlayerPrefs.bufferDurationsMs(PlayerPrefs.BUFFER_FAST).toList(), PlayerPrefs.bufferDurationsMs(99).toList())
    }
}
