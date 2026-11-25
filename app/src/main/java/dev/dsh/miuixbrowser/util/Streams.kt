package dev.dsh.miuixbrowser.util

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Android 12 没有 InputStream.readNBytes；保留导入文件的大小上限，不关闭调用方的流。 */
internal fun InputStream.readAtMost(limit: Int): ByteArray {
    require(limit >= 0) { "limit must be non-negative" }
    val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(minOf(limit, DEFAULT_BUFFER_SIZE))
    while (output.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - output.size()))
        if (count < 0) break
        if (count == 0) {
            val byte = read()
            if (byte < 0) break
            output.write(byte)
        } else {
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}
