package dev.dsh.miuixbrowser.sniff

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一个页面嗅探到的视频：去重、有上限，线程安全（WebView 在 IO 线程、Gecko 在主线程写，界面在主线程读）。
 * [items] 是 Compose 状态，界面直接读；[promptedCount] 记录已经提示过的个数：只有出现新地址才再弹，
 * 同一批地址不会重复打扰（关掉之后同页再发现新的源才会再弹）。
 */
class SniffList {
    var items by mutableStateOf<List<SniffedMedia>>(emptyList())
        private set

    var promptedCount by mutableStateOf(0)

    /** 新增返回 true；重复（同一地址，不含 #片段）或超过上限返回 false */
    @Synchronized
    fun offer(m: SniffedMedia): Boolean {
        val key = m.url.substringBefore('#')
        val cur = items
        if (cur.size >= MediaSniff.MAX_ITEMS) return false
        if (cur.any { it.url.substringBefore('#') == key }) return false
        items = cur + m
        return true
    }

    @Synchronized
    fun clear() {
        items = emptyList()
        promptedCount = 0
    }
}
