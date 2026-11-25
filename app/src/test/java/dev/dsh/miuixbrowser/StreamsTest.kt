package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.util.readAtMost
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamsTest {
    @Test fun stopsAtLimitWithoutConsumingRemainder() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3), input.readAtMost(3))
        assertEquals(4, input.read())
    }

    @Test fun returnsShortInputAtEof() {
        assertArrayEquals(byteArrayOf(1, 2), ByteArrayInputStream(byteArrayOf(1, 2)).readAtMost(50))
        assertArrayEquals(byteArrayOf(), ByteArrayInputStream(byteArrayOf()).readAtMost(50))
    }

    @Test fun zeroLimitDoesNotRead() {
        val input = ByteArrayInputStream(byteArrayOf(7))
        assertArrayEquals(byteArrayOf(), input.readAtMost(0))
        assertEquals(7, input.read())
    }

    @Test fun handlesPartialReadsAndLargeInput() {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val input = object : FilterInputStream(ByteArrayInputStream(bytes)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, 13))
        }
        assertArrayEquals(bytes.copyOf(19_001), input.readAtMost(19_001))
        assertEquals(bytes[19_001].toInt() and 255, input.read())
    }

    @Test fun zeroLengthReadStillMakesProgress() {
        val input = object : FilterInputStream(ByteArrayInputStream(byteArrayOf(1, 2, 3))) {
            private var first = true
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (first) { first = false; return 0 }
                return super.read(b, off, len)
            }
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), input.readAtMost(3))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsNegativeLimit() {
        ByteArrayInputStream(byteArrayOf()).readAtMost(-1)
    }

    @Test(expected = IOException::class) fun preservesReadFailure() {
        object : InputStream() { override fun read(): Int = throw IOException("test read failure") }.readAtMost(3)
    }
}
