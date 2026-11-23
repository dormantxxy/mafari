package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.util.LocalHtml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHtmlTest {
    @Test fun keepsHtmlNames() {
        assertEquals("index.html", LocalHtml.cleanName("index.html"))
        assertEquals("a.b.HTM", LocalHtml.cleanName("a.b.HTM"))
        assertEquals("doc.xhtml", LocalHtml.cleanName("doc.xhtml"))
    }

    @Test fun appendsExtensionWhenMissing() {
        assertEquals("page.html", LocalHtml.cleanName(null))
        assertEquals("page.html", LocalHtml.cleanName("   "))
        assertEquals("readme.txt.html", LocalHtml.cleanName("readme.txt"))
    }

    @Test fun stripsPathAndIllegalChars() {
        assertEquals("passwd.html", LocalHtml.cleanName("../../etc/passwd"))
        assertEquals("evil.html", LocalHtml.cleanName("C:\\x\\evil.html"))
        assertEquals("ab.html", LocalHtml.cleanName("a<b>?.html"))
    }

    @Test fun limitsLength() {
        val name = LocalHtml.cleanName("x".repeat(300) + ".html")
        assertTrue(name.length <= 85)
        assertTrue(name.endsWith(".html"))
    }
}
