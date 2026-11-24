package dev.dsh.miuixbrowser

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import dev.dsh.miuixbrowser.player.PlayerRequest

/**
 * 系统「打开方式」里的本地视频入口（清单里 VIEW + content / file + 视频 MIME 等过滤器声明在这里）。
 * 无界面：直接把地址交给原生播放页全屏播放（Media3 的 DefaultDataSource 能读 content:// / file://，不拷贝文件），随即结束。
 * 读权限靠 ClipData + FLAG_GRANT_READ_URI_PERMISSION 转给播放页；独立 taskAffinity，返回直接回到调用方应用。
 */
class VideoOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val scheme = uri?.scheme?.lowercase()
        if (intent?.action == Intent.ACTION_VIEW && uri != null && (scheme == "content" || scheme == "file")) {
            val req = PlayerRequest(url = uri.toString(), title = displayName(uri), standalone = true)
            runCatching {
                startActivity(
                    req.toIntent(this).apply {
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                )
            }
        }
        finish()
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            name = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return name?.substringAfterLast('/').orEmpty()
    }
}
