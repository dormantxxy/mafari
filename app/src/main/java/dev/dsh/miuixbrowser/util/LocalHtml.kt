package dev.dsh.miuixbrowser.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/**
 * 「用 Mafari 打开 HTML 文件」：其他应用（文件管理器、聊天软件、邮件……）通过系统「打开方式」交来的是
 * content:// 或 file:// 地址，其中 content:// 的读权限只在那次启动里有效，标签恢复、重新加载时就读不到了，
 * file:// 在新版 Android 上更是多半读不了。所以先把文件拷到应用私有的 filesDir/opened_html/<哈希>/<文件名>，
 * 再让浏览器加载这份副本（file://）。
 *
 * 安全：MainActivity 是导出的，WebView 也要开 allowFileAccess 才能加载 file://，
 * 所以所有「放行 file://」的地方都必须用 [isOpenedFile] 校验：只认本目录下的副本，其余 file:// 一律拒绝
 * （否则任何应用都能让浏览器去读本应用的私有数据库，或者网页能读手机存储）。
 */
object LocalHtml {
    private const val DIR = "opened_html"

    /** 单个 HTML 文件的体积上限 */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** 旧副本保留时间：超过就在下次打开时清掉 */
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    private val HTML_EXT = setOf("html", "htm", "xhtml")

    /** 放在 filesDir 而不是 cacheDir：冷启动会清空 cacheDir（见 CacheCleaner），标签恢复时文件就没了 */
    fun dir(context: Context): File = File(context.applicationContext.filesDir, DIR)

    /** [uri] 是本应用拷出来的副本（file://，规范路径落在 [dir] 之内）。 */
    fun isOpenedFile(context: Context, uri: Uri): Boolean {
        if (!"file".equals(uri.scheme, ignoreCase = true)) return false
        val path = uri.path ?: return false
        return runCatching {
            val root = dir(context).canonicalPath + File.separator
            File(path).canonicalPath.startsWith(root)
        }.getOrDefault(false)
    }

    fun isOpenedFileUrl(context: Context, url: String): Boolean =
        runCatching { isOpenedFile(context, Uri.parse(url)) }.getOrDefault(false)

    /** 可以由「打开方式」交进来的地址：content:// 或 file://。 */
    fun isImportable(uri: Uri?): Boolean {
        val s = uri?.scheme?.lowercase()
        return s == "content" || s == "file"
    }

    /** 把 [src] 拷成本地副本。失败（读不到 / 太大 / 磁盘满）返回 failure，并清掉没写完的文件。 */
    fun import(context: Context, src: Uri): Result<File> = runCatching {
        val app = context.applicationContext
        val folder = File(dir(app), digest(src.toString())).apply { mkdirs() }
        val target = File(folder, fileNameOf(app, src))
        try {
            val input = app.contentResolver.openInputStream(src) ?: error("cannot open $src")
            input.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) error("file too large")
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        prune(app, keep = folder)
        target
    }

    /** 清掉超过 [KEEP_MS] 没动过的旧副本；[keep] 是这次刚写的目录。 */
    private fun prune(context: Context, keep: File) {
        val now = System.currentTimeMillis()
        runCatching {
            dir(context).listFiles()?.forEach { d ->
                if (d != keep && now - d.lastModified() > KEEP_MS) d.deleteRecursively()
            }
        }
    }

    /** 取显示名：先问提供方的 DISPLAY_NAME，再用路径最后一段；去掉路径分隔符 / 控制字符，缺 html 扩展名就补 .html。 */
    private fun fileNameOf(context: Context, uri: Uri): String {
        var name: String? = null
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment
        return cleanName(name)
    }

    /** 文件名清洗：去掉路径分隔符 / 控制字符 / 非法字符，限长 80，缺 html 扩展名就补 .html。 */
    internal fun cleanName(raw: String?): String {
        var clean = (raw ?: "page").substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '"' && it != '<' && it != '>' && it != ':' && it != '|' && it != '?' && it != '*' }
            .trim().trim('.')
        if (clean.isEmpty()) clean = "page"
        if (clean.length > 80) clean = clean.takeLast(80)
        val ext = clean.substringAfterLast('.', "").lowercase()
        return if (ext in HTML_EXT) clean else "$clean.html"
    }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(6).joinToString("") { "%02x".format(it) }
}
