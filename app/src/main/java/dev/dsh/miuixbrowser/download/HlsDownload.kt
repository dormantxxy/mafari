package dev.dsh.miuixbrowser.download

import dev.dsh.miuixbrowser.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * App 端的 m3u8（HLS）下载：取清单 → 主清单选最高码率 → 并行下载分片（AES-128 解密）→ 按序合并成一个文件。
 * 跑在内置下载器的任务里（前台服务），所以离开页面 / 切后台 / 关掉悬浮播放器都不影响。
 *
 * - 续传：分片按序号落盘（filesDir/downloads/<id>/hls/N.seg），失败 / 暂停后重进只补缺的分片；清单分片数变了就清空重来。
 * - 输出：TS 分片直接拼接成 .ts；fMP4（带 EXT-X-MAP）拼成 .mp4。不做转封装。
 * - 不支持：SAMPLE-AES / 其它加密、EXT-X-BYTERANGE 分片（直接报错，不会下出坏文件）。
 */
internal object HlsDownload {
    /** 任务的 mime 用它标记「这是 HLS 任务」；合并完成后会换成真实的 video/xxx */
    const val MIME_MARK = "application/x-mpegURL"

    private const val PARALLEL = 6
    private const val SEG_RETRY = 3

    fun isHls(task: DownloadTask): Boolean = task.mime.equals(MIME_MARK, ignoreCase = true)

    private class Seg(val index: Int, val url: String, val keyUrl: String?, val iv: ByteArray?)
    private class Playlist(val segs: List<Seg>, val mapUrl: String?)

    // ───────────────────────── 清单解析 ─────────────────────────

    private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    private fun attrs(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        ATTR.findAll(s).forEach { m[it.groupValues[1]] = it.groupValues[2].trim().trim('"') }
        return m
    }

    private fun resolve(base: String, ref: String): String = URL(URL(base), ref).toString()

    private fun hexIv(s: String): ByteArray {
        val h = s.removePrefix("0x").removePrefix("0X").padStart(32, '0').takeLast(32)
        return ByteArray(16) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun seqIv(seq: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) iv[15 - i] = (seq shr (8 * i)).toByte()
        return iv
    }

    /** 主清单：取码率最高的子清单；不是主清单返回 null */
    private fun pickVariant(base: String, text: String): String? {
        val lines = text.lines().map { it.trim() }
        var bestBw = -1L
        var best: String? = null
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF:")) continue
            val bw = attrs(lines[i].substringAfter(':'))["BANDWIDTH"]?.toLongOrNull() ?: 0L
            val uri = (i + 1 until lines.size).map { lines[it] }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            if (bw > bestBw) { bestBw = bw; best = resolve(base, uri) }
        }
        return best
    }

    private fun parseMedia(base: String, text: String): Playlist {
        var seq = 0L
        var method = "NONE"
        var keyUri: String? = null
        var keyIv: ByteArray? = null
        var mapUrl: String? = null
        val segs = ArrayList<Seg>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(line.substringAfter(':'))
                    method = (a["METHOD"] ?: "NONE").uppercase()
                    keyUri = a["URI"]?.let { resolve(base, it) }
                    keyIv = a["IV"]?.let { hexIv(it) }
                    if (method != "NONE" && method != "AES-128") throw IllegalStateException(tr("不支持的加密方式：{0}", method))
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attrs(line.substringAfter(':'))
                    if (a.containsKey("BYTERANGE")) throw IllegalStateException(tr("不支持按字节范围切分的 m3u8"))
                    mapUrl = a["URI"]?.let { resolve(base, it) }
                }
                line.startsWith("#EXT-X-BYTERANGE") -> throw IllegalStateException(tr("不支持按字节范围切分的 m3u8"))
                line.startsWith("#") -> {}
                else -> {
                    val aes = method == "AES-128" && keyUri != null
                    segs += Seg(segs.size, resolve(base, line), if (aes) keyUri else null, if (aes) (keyIv ?: seqIv(seq)) else null)
                    seq++
                }
            }
        }
        return Playlist(segs, mapUrl)
    }

    // ───────────────────────── 网络 ─────────────────────────

    private suspend fun fetchBytes(task: DownloadTask, url: String): ByteArray {
        val c = Downloader.connect(task, null, null, url)
        try {
            return Downloader.withConn(c) {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                c.inputStream.use { it.readBytes() }
            }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetchSeg(task: DownloadTask, seg: Seg, dest: File, tmp: File, keys: ConcurrentHashMap<String, ByteArray>) {
        var last: Exception? = null
        repeat(SEG_RETRY) { n ->
            try {
                val c = Downloader.connect(task, null, null, seg.url)
                try {
                    Downloader.withConn(c) {
                        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                        c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
                    }
                } finally {
                    c.disconnect()
                }
                if (seg.keyUrl != null && seg.iv != null) {
                    val key = keys[seg.keyUrl] ?: fetchBytes(task, seg.keyUrl).also { keys[seg.keyUrl] = it }
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(seg.iv))
                    tmp.writeBytes(cipher.doFinal(tmp.readBytes()))
                }
                if (!tmp.renameTo(dest)) throw IllegalStateException(tr("无法写入文件"))
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                runCatching { tmp.delete() }
                delay(500L * (n + 1))
            }
        }
        throw last ?: IllegalStateException(tr("分片下载失败"))
    }

    // ───────────────────────── 主流程 ─────────────────────────

    /** 下完所有分片并合并成 data.part；返回合并后的文件（已标记 fetched、mime / 文件名已改成真实类型）。入库由调用方做 */
    suspend fun run(id: Long): File {
        val task = Downloader.tasks.firstOrNull { it.id == id } ?: throw CancellationException()
        val dir = Downloader.taskDir(Downloader.app, id).apply { mkdirs() }
        val hdir = File(dir, "hls").apply { mkdirs() }

        var url = task.url
        var text = String(fetchBytes(task, url), Charsets.UTF_8)
        repeat(3) {
            if (text.contains("#EXT-X-STREAM-INF")) {
                url = pickVariant(url, text) ?: throw IllegalStateException(tr("无法解析 m3u8 清单"))
                text = String(fetchBytes(task, url), Charsets.UTF_8)
            }
        }
        if (!text.contains("#EXTM3U")) throw IllegalStateException(tr("不是有效的 m3u8 清单"))
        val pl = parseMedia(url, text)
        if (pl.segs.isEmpty()) throw IllegalStateException(tr("m3u8 清单里没有分片"))

        // 续传依据：分片数没变才沿用已下载的分片
        val sig = File(hdir, "count")
        if (sig.exists() && sig.readText() != pl.segs.size.toString()) hdir.listFiles()?.forEach { it.delete() }
        sig.writeText(pl.segs.size.toString())

        val keys = ConcurrentHashMap<String, ByteArray>()
        val initFile = File(hdir, "init.bin")
        if (pl.mapUrl != null && !initFile.exists()) {
            val tmp = File(hdir, "init.tmp")
            tmp.writeBytes(fetchBytes(task, pl.mapUrl))
            tmp.renameTo(initFile)
        }

        fun segFile(i: Int) = File(hdir, "$i.seg")
        val total = pl.segs.size
        val done = AtomicInteger(0)
        val bytes = AtomicLong(0)
        pl.segs.forEach { s -> segFile(s.index).takeIf { it.exists() }?.let { done.incrementAndGet(); bytes.addAndGet(it.length()) } }

        var lastUi = 0L
        fun report(force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - lastUi < 300) return
            lastUi = now
            val d = done.get()
            val b = bytes.get()
            val estTotal = if (d > 0) b * total / d else 0L
            Downloader.update(id) { it.copy(bytes = b, total = estTotal) }
            val progress = d.toFloat() / total
            DownloadService.instance?.update(task.name, progress, "${(progress * 100).toInt()}% · ${Downloader.sizeText(b)}", id)
        }
        report(force = true)

        val sem = Semaphore(PARALLEL)
        coroutineScope {
            pl.segs.filter { !segFile(it.index).exists() }.map { seg ->
                async {
                    sem.withPermit {
                        currentCoroutineContext().ensureActive()
                        val dest = segFile(seg.index)
                        fetchSeg(task, seg, dest, File(hdir, "${seg.index}.tmp"), keys)
                        done.incrementAndGet()
                        bytes.addAndGet(dest.length())
                        report()
                    }
                }
            }.awaitAll()
        }
        report(force = true)
        Downloader.persist(id)

        // 合并：fMP4（有 init 段）→ .mp4，其余 TS → .ts
        val part = File(dir, "data.part")
        runCatching { part.delete() }
        java.io.FileOutputStream(part).use { out ->
            val buf = ByteArray(256 * 1024)
            fun append(f: File) = f.inputStream().use { i ->
                while (true) {
                    val r = i.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                }
            }
            if (pl.mapUrl != null) append(initFile)
            for (s in pl.segs) {
                currentCoroutineContext().ensureActive()
                append(segFile(s.index))
            }
            out.flush()
            out.fd.sync()
        }
        val len = part.length()
        val fmp4 = pl.mapUrl != null
        val ext = if (fmp4) "mp4" else "ts"
        val base = task.name.replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "").ifBlank { "video" }
        Downloader.update(id) { it.copy(name = "$base.$ext", mime = if (fmp4) "video/mp4" else "video/mp2t", total = len, bytes = len) }
        Downloader.markFetched(id)
        runCatching { hdir.deleteRecursively() }
        return part
    }
}
