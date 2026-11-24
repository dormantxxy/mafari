package dev.dsh.miuixbrowser.sniff

/** 嗅探到的一个视频地址。[type]：hls / mp4 / webm / mkv / flv。 */
data class SniffedMedia(
    val url: String,
    val type: String,
    val host: String,
    /** 字节数，未知为 -1（Gecko 从响应头来，WebView 的请求拦截拿不到） */
    val size: Long = -1,
)

/**
 * 视频地址判定（纯函数，无 Android 依赖，有单测）。
 *
 * 只认「播放器能直接放的」：HLS 清单（.m3u8 / mpegurl）和整段文件（mp4 / webm / mkv / flv）。
 * 分片（.ts / .m4s / video/mp2t）、DASH（原生内核没带 dash 模块）、音频、图片一律不要。
 * 响应头可用时（Gecko 的 webRequest、页面脚本的 fetch / XHR）再用 Content-Type 补判没有后缀的地址。
 */
object MediaSniff {

    /** 总开关（设置里「嗅探网页视频」）；WebView 的请求拦截在热路径上，直接读这个静态值 */
    @Volatile var enabled: Boolean = true

    const val MAX_ITEMS = 12
    const val MAX_URL_CHARS = 4096

    /** 已知长度小于它的整段文件多半是广告 / 预览 / 动图，不弹（HLS 清单本来就小，不受限） */
    const val MIN_FILE_BYTES = 256L * 1024

    private val HLS_CT = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
        "audio/mpegurl", "audio/x-mpegurl",
    )

    /** video 类型里属于分片而不是整段视频的 */
    private val SEGMENT_CT = setOf("video/mp2t", "video/mpeg2t", "video/iso.segment", "video/x-mpegts", "video/vnd.dlna.mpeg-tts")

    private val FILE_EXTS = mapOf(
        "mp4" to "mp4", "m4v" to "mp4", "mov" to "mp4",
        "webm" to "webm", "mkv" to "mkv", "flv" to "flv",
    )

    private val CT_TYPES = mapOf(
        "video/mp4" to "mp4", "video/x-m4v" to "mp4", "video/quicktime" to "mp4",
        "video/webm" to "webm", "video/x-matroska" to "mkv", "video/x-flv" to "flv",
    )

    fun isHttp(url: String): Boolean =
        url.length <= MAX_URL_CHARS && (url.startsWith("http://", true) || url.startsWith("https://", true))

    /**
     * 返回类型，不是视频返回 null。
     * [contentType] 可带参数（`; charset=`）；[length] 未知传 -1；
     * [fromMediaElement]：地址来自页面里 `<video>` 的 currentSrc，没有后缀也没有响应头时按 mp4 处理。
     */
    fun classify(url: String, contentType: String? = null, length: Long = -1, fromMediaElement: Boolean = false): String? {
        if (!isHttp(url)) return null
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (ct in SEGMENT_CT) return null
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext == "ts" || ext == "m4s") return null

        if (ext == "m3u8" || ext == "m3u" || ct in HLS_CT) return "hls"

        val type = FILE_EXTS[ext] ?: CT_TYPES[ct]
            ?: if (ct.startsWith("video/") || (fromMediaElement && (ct.isEmpty() || ct == "application/octet-stream"))) "mp4" else null
        if (type == null) return null
        if (length in 0 until MIN_FILE_BYTES) return null
        return type
    }

    fun hostOf(url: String): String {
        val rest = url.substringAfter("://", "")
        return rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@').substringBefore(':').lowercase()
    }

    /** 列表里显示的名字：文件名（没有就用域名） */
    fun displayName(m: SniffedMedia): String {
        val name = m.url.substringBefore('#').substringBefore('?').substringAfterLast('/')
        return name.takeIf { it.isNotBlank() && it.length <= 48 } ?: m.host
    }
}
