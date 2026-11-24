package dev.dsh.miuixbrowser.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.dsh.miuixbrowser.engine.gecko.GeckoGm
import dev.dsh.miuixbrowser.engine.webview.IncognitoProfile
import dev.dsh.miuixbrowser.engine.webview.OneWayBridge
import dev.dsh.miuixbrowser.player.PlayerRequest
import dev.dsh.miuixbrowser.player.PlayerSource
import dev.dsh.miuixbrowser.sniff.MediaSniff
import dev.dsh.miuixbrowser.sniff.SniffedMedia
import dev.dsh.miuixbrowser.util.Diag
import org.json.JSONObject

/*
 * 视频嗅探（NATIVE_PLAYER.md 阶段四）：
 *  - WebView：shouldInterceptRequest 按地址判定（热路径，只做字符串判断）+ 页面脚本 mafari_sniff.js 补响应头 / <video> 地址；
 *  - Gecko：内置扩展的 webRequest.onHeadersReceived 上报，见 engine/gecko/GeckoSniff；
 *  - 结果都落在 [BrowserTab.sniffs]，界面弹提示、点「播放」走 [playSniffed]。
 */

/** shouldInterceptRequest（IO 线程）里调用：地址像视频就记下来。主框架请求不看（页面本身不是视频文件）。 */
internal fun BrowserTab.sniffRequest(request: WebResourceRequest) {
    val u = request.url.toString()
    val type = MediaSniff.classify(u) ?: return
    webSniffs.offer(SniffedMedia(u, type, MediaSniff.hostOf(u)))
}

/** 安装页面脚本的消息桥并注册文档开始脚本。不支持 WebMessageListener / 文档开始脚本时只剩请求拦截这条路，不影响基本嗅探。 */
internal fun BrowserTab.installMediaSniff() {
    runCatching {
        if (!OneWayBridge.install(webView, "MafariSniff", 4200) { onSniffMessage(it) }) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val js = InjectAssets.read(appContext, "mafari_sniff.js") ?: error("mafari_sniff.js missing")
        WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
    }.onFailure { Diag.log("media sniff install failed: ${it.message}") }
}

/** 页面脚本的报告（主线程；来源 / 主框架 / 长度已在 [OneWayBridge] 校验）：{u, ct, len, el} */
internal fun BrowserTab.onSniffMessage(json: String) {
    if (!MediaSniff.enabled) return
    val o = runCatching { JSONObject(json) }.getOrNull() ?: return
    val u = o.optString("u")
    val len = o.optLong("len", -1)
    val type = MediaSniff.classify(u, o.optString("ct").ifEmpty { null }, len, o.optBoolean("el")) ?: return
    webSniffs.offer(SniffedMedia(u, type, MediaSniff.hostOf(u), len))
}

/**
 * 用原生播放器打开嗅探到的视频：请求头带上当前页的 Referer / UA / Cookie（防盗链源要靠它），
 * 其余嗅探到的地址作为可切换的源一起交给播放页。Gecko 取 Cookie 要一来一回，放到后台线程。
 */
internal fun BrowserTab.playSniffed(context: Context, selected: SniffedMedia, all: List<SniffedMedia>) {
    val pageUrl = url
    val pageTitle = title
    val gecko = isGecko
    val others = all.filter { it.url != selected.url }.map {
        PlayerSource(it.url, label = "${it.type.uppercase()} · ${MediaSniff.displayName(it)}", type = hintOf(it))
    }
    fun launch(ua: String, cookie: String) {
        val req = PlayerRequest(
            url = selected.url,
            title = pageTitle,
            referer = pageUrl,
            ua = ua,
            cookie = cookie,
            type = hintOf(selected),
            sources = others,
            gecko = gecko,
        )
        val intent = req.toIntent(context).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { context.startActivity(intent) }.onFailure { Diag.log("sniff play failed: ${it.message}") }
    }
    if (gecko) {
        val main = Handler(Looper.getMainLooper())
        Thread({
            val cookie = runCatching { GeckoGm.cookieHeader(selected.url) }.getOrNull().orEmpty()
            val ua = GeckoGm.userAgent.orEmpty()
            main.post { launch(ua, cookie) }
        }, "sniff-play").start()
    } else {
        val cookie = runCatching {
            IncognitoProfile.cookieManagerFor(webView).getCookie(selected.url)
        }.getOrNull().orEmpty()
        val ua = runCatching { webView.settings.userAgentString }.getOrNull().orEmpty()
        launch(ua, cookie)
    }
}

/** 播放器的类型提示：只有 HLS 明确声明，其余让播放器按地址 / 响应判断 */
private fun hintOf(m: SniffedMedia): String = if (m.type == "hls") "hls" else ""
