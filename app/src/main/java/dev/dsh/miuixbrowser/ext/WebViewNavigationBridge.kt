package dev.dsh.miuixbrowser.ext

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.Navigation
import androidx.webkit.NavigationListener
import androidx.webkit.NavigationRedirectParameters
import androidx.webkit.NavigationState
import androidx.webkit.PageState
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * [WebViewNavigationBridge] 把主框架导航事件交给拓展子系统的出口。ExtensionSystem 实现它：
 * 同时驱动 webNavigation（committed / DOMContentLoaded / completed）和 webRequest（主文档真实响应）。
 */
interface NavigationSink {
    /** 服务端重定向：[fromUrl] → [toUrl]，[statusCode] / [headers] 是重定向响应本身的（不支持时为 null）。 */
    fun redirected(tabId: Int, fromUrl: String, toUrl: String, statusCode: Int?, headers: JSONArray?)

    /** 主文档提交（不含同文档导航、错误页）。[statusCode] / [headers] 是最终响应的。 */
    fun committed(
        tabId: Int, url: String, transitionType: String, qualifiers: List<String>,
        statusCode: Int?, headers: JSONArray?,
    )

    fun domContentLoaded(tabId: Int, url: String)

    /** window.load。 */
    fun loaded(tabId: Int, url: String, statusCode: Int?, headers: JSONArray?)
}

/** 不依赖 androidx.webkit 的纯映射逻辑，单独拿出来方便单测。 */
object NavigationMapping {
    fun transitionType(isReload: Boolean, wasInitiatedByPage: Boolean): String = when {
        isReload -> "reload"
        wasInitiatedByPage -> "link"
        else -> "typed"
    }

    fun qualifiers(isHistory: Boolean, serverRedirected: Boolean): List<String> = buildList {
        if (isHistory) add("forward_back")
        if (serverRedirected) add("server_redirect")
    }

    /** 状态码 ≤ 0 视为未知（特性不支持 / 导航没有 HTTP 响应）。 */
    fun statusOrNull(code: Int): Int? = code.takeIf { it > 0 }

    /**
     * 响应头 → Firefox 的 `[{name, value}]`。androidx.webkit 的头集合类型随版本变化（Map<String,String> 或
     * Map<String,List<String>>），这里按运行时形状处理：值是集合就每个元素一条，否则一条。
     */
    fun headersJson(raw: Any?): JSONArray? {
        val map = raw as? Map<*, *> ?: return null
        val out = JSONArray()
        for ((k, v) in map) {
            val name = k as? String ?: continue
            when (v) {
                is Iterable<*> -> v.forEach { item -> item?.let { out.put(JSONObject().put("name", name).put("value", it.toString())) } }
                null -> Unit
                else -> out.put(JSONObject().put("name", name).put("value", v.toString()))
            }
        }
        return out
    }
}

/**
 * 用 androidx.webkit 的 NavigationListener（1.16 起稳定，1.19 起带 NavigationState 快照与响应头）取代 WebViewClient 回调的近似。
 *
 * 分工：
 * - **仍由 WebViewClient 路径负责**：onBeforeNavigate（WebView 没有「同文档导航之外的开始」信号，NavigationListener 的
 *   onNavigationStarted 连 pushState 这类同文档导航也会触发，直接用会给扩展发大量假的 beforeNavigate）、
 *   onErrorOccurred、onHistoryStateUpdated / onReferenceFragmentUpdated。
 * - **由本桥负责**：onCommitted（带真实 transitionType / qualifiers）、onDOMContentLoaded、onCompleted、
 *   webRequest 主文档的 onBeforeRedirect / onHeadersReceived / onResponseStarted / onCompleted（带真实状态码与响应头）。
 *   这些标签上 WebViewClient 的对应近似事件由 ExtensionSystem 丢弃，避免同一次导航发两遍。
 *
 * 系统 WebView 不支持时 [attach] 返回 false，调用方保持旧路径。所有回调在主线程。
 */
object WebViewNavigationBridge {
    fun isSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.NAVIGATION_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.NAVIGATION_LISTENER_NAVIGATION_STATE)
    }.getOrDefault(false)

    private fun headersSupported(): Boolean = runCatching {
        WebViewFeature.isFeatureSupported(WebViewFeature.NAVIGATION_GET_RESPONSE_HEADERS)
    }.getOrDefault(false)

    fun attach(webView: WebView, tabId: Int, sink: NavigationSink): Boolean {
        if (!isSupported()) return false
        return try {
            val main = Handler(Looper.getMainLooper())
            WebViewCompat.addNavigationListener(webView, Executor { main.post(it) }, Listener(tabId, sink))
            true
        } catch (_: Throwable) {
            false
        }
    }

    private class Listener(private val tabId: Int, private val sink: NavigationSink) : NavigationListener {
        private class Flight(var url: String, var redirected: Boolean = false)

        /** 在途导航（主线程访问）。键用 Navigation 对象本身，文档要求不要用 NavigationState 做键。 */
        private val flights = HashMap<Navigation, Flight>()

        // 当前已提交的主文档；load / DOMContentLoaded 事件靠它对上地址
        private var committedUrl: String? = null
        private var status: Int? = null
        private var headers: JSONArray? = null
        private var domReported = false
        private var loadReported = false

        override fun onNavigationStarted(navigationState: NavigationState) {
            val nav = navigationState.navigation
            val url = nav.url ?: return
            flights[nav] = Flight(url)
        }

        override fun onNavigationRedirected(
            navigationState: NavigationState,
            navigationRedirectParameters: NavigationRedirectParameters,
        ) {
            val nav = navigationState.navigation
            val to = nav.url ?: return
            val flight = flights.getOrPut(nav) { Flight(to) }
            val from = flight.url
            flight.url = to
            flight.redirected = true
            if (from == to) return
            val code = if (headersSupported()) {
                runCatching { NavigationMapping.statusOrNull(navigationRedirectParameters.statusCode) }.getOrNull()
            } else null
            val hdrs = if (headersSupported()) {
                runCatching { NavigationMapping.headersJson(navigationRedirectParameters.responseHeaders) }.getOrNull()
            } else null
            sink.redirected(tabId, from, to, code, hdrs)
        }

        override fun onNavigationCompleted(navigationState: NavigationState) {
            val nav = navigationState.navigation
            val flight = flights.remove(nav)
            // 同文档（pushState / 片段）由 doUpdateVisitedHistory 路径报告；没提交（204 / 下载 / 被取消）没有文档可言；
            // 错误页由 onReceivedError 路径报 onErrorOccurred，这里不能再报 committed。
            if (navigationState.isSameDocument || !navigationState.didCommit() || navigationState.didCommitErrorPage()) {
                if (navigationState.didCommitErrorPage()) committedUrl = null
                return
            }
            val url = nav.url ?: flight?.url ?: return
            val code = NavigationMapping.statusOrNull(runCatching { navigationState.statusCode }.getOrDefault(0))
            val hdrs = if (headersSupported()) {
                runCatching { NavigationMapping.headersJson(navigationState.responseHeaders) }.getOrNull()
            } else null
            committedUrl = url
            status = code
            headers = hdrs
            domReported = false
            loadReported = false
            sink.committed(
                tabId, url,
                NavigationMapping.transitionType(nav.isReload, nav.wasInitiatedByPage()),
                NavigationMapping.qualifiers(nav.isHistory, flight?.redirected == true),
                code, hdrs,
            )
        }

        override fun onPageDomContentLoadedEvent(pageState: PageState) {
            val url = committedUrl ?: return
            if (domReported) return
            domReported = true
            sink.domContentLoaded(tabId, url)
        }

        override fun onPageLoadEvent(pageState: PageState) {
            val url = committedUrl ?: return
            if (loadReported) return
            loadReported = true
            sink.loaded(tabId, url, status, headers)
        }
    }
}
