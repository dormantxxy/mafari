package dev.dsh.miuixbrowser.player

import android.content.Context

/**
 * 原生播放器的用户设置（设置 → 媒体嗅探与播放 → 播放器）。
 *
 * 和 AppSettings 共用同一份 SharedPreferences（mafari_settings）：播放页是独立 Activity，
 * 不构造 AppSettings，直接读这里；AppSettings 里的同名开关写同一个 key。
 */
object PlayerPrefs {
    const val PREFS = "mafari_settings"
    const val KEY_RENDER = "player_render_mode"
    const val KEY_SPEED = "player_default_speed"
    const val KEY_LONG_PRESS = "player_long_press_speed"
    const val KEY_BG_PLAY = "player_bg_play"
    const val KEY_QUICK_MENU = "player_quick_menu"
    const val KEY_DECODER = "player_decoder_mode"
    const val KEY_DECODER_FALLBACK = "player_decoder_fallback"
    const val KEY_HDR = "player_hdr_mode"
    const val KEY_BUFFER = "player_buffer_mode"

    /** 悬浮播放器下方快捷菜单里的按钮（顺序即显示顺序）；每个按钮可在设置里单独关 */
    val QUICK_IDS = listOf("cast", "download", "shot", "speed", "quality")
    fun quickKey(id: String) = "player_quick_$id"

    /** 老版本把后台播放存在播放页自己的 prefs 里（mafari_player / bg_play），第一次读时沿用 */
    private const val LEGACY_PREFS = "mafari_player"
    private const val LEGACY_KEY_BG_PLAY = "bg_play"

    const val RENDER_TEXTURE = 0
    const val RENDER_SURFACE = 1

    /** 解码方式：0 自动（系统默认顺序）/ 1 优先硬件 / 2 优先软件（排序偏好，不是强制；配合「解码器回退」） */
    const val DECODER_AUTO = 0
    const val DECODER_HARDWARE = 1
    const val DECODER_SOFTWARE = 2

    /** HDR 视频：0 自动（设备支持就按 HDR 播）/ 1 优先 SDR（流里同时有 HDR 和 SDR 清晰度时只用 SDR） */
    const val HDR_AUTO = 0
    const val HDR_PREFER_SDR = 1

    /** 缓冲策略：0 快速起播（默认，和以前一致）/ 1 均衡 / 2 大缓冲 */
    const val BUFFER_FAST = 0
    const val BUFFER_BALANCED = 1
    const val BUFFER_LARGE = 2

    /**
     * 缓冲策略对应的 DefaultLoadControl 四个时长（毫秒）：最小缓冲、最大缓冲、起播所需、卡顿后恢复所需。
     * 保证 最小缓冲 ≥ 卡顿后恢复所需，否则 DefaultLoadControl 会抛异常。
     */
    fun bufferDurationsMs(mode: Int): IntArray = when (mode) {
        BUFFER_BALANCED -> intArrayOf(30_000, 90_000, 1_500, 3_000)
        BUFFER_LARGE -> intArrayOf(50_000, 180_000, 2_500, 5_000)
        else -> intArrayOf(15_000, 60_000, 1_000, 2_000)
    }

    /** 取值收敛到 0..[max]，越界（手改 prefs / 旧数据）回到 0（默认） */
    fun clampMode(v: Int, max: Int): Int = if (v in 0..max) v else 0

    /** 默认倍速可选值（下拉顺序） */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** 长按倍速可选值（下拉顺序） */
    val LONG_PRESS_SPEEDS = listOf(1.5f, 2f, 3f, 4f)

    fun clampRender(v: Int): Int = if (v == RENDER_SURFACE) RENDER_SURFACE else RENDER_TEXTURE

    /** 不在可选列表里的值（手改 prefs / 旧数据）一律回到 [fallback] */
    fun snap(v: Float, options: List<Float>, fallback: Float): Float =
        options.firstOrNull { Math.abs(it - v) < 0.001f } ?: fallback

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun renderMode(context: Context): Int = clampRender(prefs(context).getInt(KEY_RENDER, RENDER_TEXTURE))

    fun setRenderMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_RENDER, clampRender(mode)).apply()
    }

    fun defaultSpeed(context: Context): Float = snap(prefs(context).getFloat(KEY_SPEED, 1f), SPEEDS, 1f)

    fun setDefaultSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_SPEED, snap(v, SPEEDS, 1f)).apply()
    }

    fun longPressSpeed(context: Context): Float = snap(prefs(context).getFloat(KEY_LONG_PRESS, 2f), LONG_PRESS_SPEEDS, 2f)

    fun setLongPressSpeed(context: Context, v: Float) {
        prefs(context).edit().putFloat(KEY_LONG_PRESS, snap(v, LONG_PRESS_SPEEDS, 2f)).apply()
    }

    fun decoderMode(context: Context): Int = clampMode(prefs(context).getInt(KEY_DECODER, DECODER_AUTO), DECODER_SOFTWARE)

    /** 首选解码器初始化失败时自动换备用解码器（默认开：只在初始化失败时才会触发，平时无影响） */
    fun decoderFallback(context: Context): Boolean = prefs(context).getBoolean(KEY_DECODER_FALLBACK, true)

    fun hdrMode(context: Context): Int = clampMode(prefs(context).getInt(KEY_HDR, HDR_AUTO), HDR_PREFER_SDR)

    fun bufferMode(context: Context): Int = clampMode(prefs(context).getInt(KEY_BUFFER, BUFFER_FAST), BUFFER_LARGE)

    /** 快捷菜单总开关（默认开） */
    fun quickMenu(context: Context): Boolean = prefs(context).getBoolean(KEY_QUICK_MENU, true)

    /** 快捷菜单里实际要显示的按钮（总开关关了就是空），保持 [QUICK_IDS] 的顺序 */
    fun quickItems(context: Context): List<String> {
        val p = prefs(context)
        if (!p.getBoolean(KEY_QUICK_MENU, true)) return emptyList()
        return QUICK_IDS.filter { p.getBoolean(quickKey(it), true) }
    }

    fun bgPlay(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_BG_PLAY)) return p.getBoolean(KEY_BG_PLAY, true)
        return context.applicationContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
            .getBoolean(LEGACY_KEY_BG_PLAY, true)
    }

    fun setBgPlay(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BG_PLAY, on).apply()
    }
}
