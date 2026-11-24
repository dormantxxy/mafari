package dev.dsh.miuixbrowser.player

import android.content.Context
import android.content.Intent

/**
 * 一次播放请求：地址加上原网页带的请求头。
 * 原生请求直接带 Referer / UA / Cookie，没有跨域问题（脚本里绕 CORS 的 hls.js loader 不再需要）。
 */
data class PlayerRequest(
    val url: String,
    val title: String = "",
    val referer: String = "",
    val ua: String = "",
    val cookie: String = "",
    /** 提示类型：hls / mp4 / 空（按地址和响应嗅探） */
    val type: String = "",
    val startMs: Long = 0,
) {
    /** 要附加到每个网络请求上的头（UA 单独走 setUserAgent，不放这里） */
    fun headers(): Map<String, String> = buildMap {
        if (referer.isNotBlank()) put("Referer", referer)
        if (cookie.isNotBlank()) put("Cookie", cookie)
    }

    /** 是不是 HLS：显式声明，或地址路径里带 .m3u8（查询串里带的不算，避免把 ?u=xxx.m3u8 之类误判）。 */
    fun isHls(): Boolean {
        if (type.equals("hls", true) || type.equals("m3u8", true)) return true
        if (type.isNotBlank()) return false
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".m3u")
    }

    fun toIntent(context: Context): Intent =
        Intent(context, NativePlayerActivity::class.java)
            .putExtra(K_URL, url).putExtra(K_TITLE, title).putExtra(K_REFERER, referer)
            .putExtra(K_UA, ua).putExtra(K_COOKIE, cookie).putExtra(K_TYPE, type).putExtra(K_START, startMs)

    companion object {
        private const val K_URL = "url"
        private const val K_TITLE = "title"
        private const val K_REFERER = "referer"
        private const val K_UA = "ua"
        private const val K_COOKIE = "cookie"
        private const val K_TYPE = "type"
        private const val K_START = "startMs"

        fun from(i: Intent): PlayerRequest? {
            val url = i.getStringExtra(K_URL)?.trim().orEmpty()
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            return PlayerRequest(
                url = url,
                title = i.getStringExtra(K_TITLE).orEmpty(),
                referer = i.getStringExtra(K_REFERER).orEmpty(),
                ua = i.getStringExtra(K_UA).orEmpty(),
                cookie = i.getStringExtra(K_COOKIE).orEmpty(),
                type = i.getStringExtra(K_TYPE).orEmpty(),
                startMs = i.getLongExtra(K_START, 0),
            )
        }
    }
}
