package dev.dsh.miuixbrowser

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.dsh.miuixbrowser.i18n.tr
import dev.dsh.miuixbrowser.util.LocalHtml

/**
 * 系统「打开方式」里的网页 / 纯文本文件入口（HTML、MHTML、TXT、LOG、MD 等）（清单里 VIEW + content / file + text/html 等过滤器声明在这里）。
 * 无界面（透明）：后台线程把文件拷进应用私有目录（见 [LocalHtml]），再交给 [MainActivity] 在新标签里打开，随即结束。
 * 拷贝期间保持透明，小文件几乎瞬间完成；读不到 / 太大时给个提示。
 */
class HtmlOpenActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent?.data
        if (intent?.action != Intent.ACTION_VIEW || !LocalHtml.isImportable(src)) {
            finish()
            return
        }
        val app = applicationContext
        Thread {
            val result = LocalHtml.import(app, src!!, intent?.type)
            runOnUiThread {
                val file = result.getOrNull()
                if (file == null) {
                    Toast.makeText(app, tr("无法打开这个文件"), Toast.LENGTH_LONG).show()
                } else {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.fromFile(file))
                                .setClass(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
                finish()
            }
        }.start()
    }
}
