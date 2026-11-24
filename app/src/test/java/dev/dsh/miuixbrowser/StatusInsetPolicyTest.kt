package dev.dsh.miuixbrowser

import dev.dsh.miuixbrowser.ui.StatusInsetPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 状态栏留白 / 拉伸取样的切换：拉伸只在初始或最顶部，其余滑动时刻一律铺到屏幕顶、直接采样 */
class StatusInsetPolicyTest {
    private fun collapsed(imm: Boolean = false, keep: Boolean = false, band: Boolean = true, page: Boolean = true, top: Boolean) =
        StatusInsetPolicy.collapsed(imm, keep, band, page, top)

    @Test fun initialAtTopKeepsInsetAndStretches() {
        val c = collapsed(top = true)
        assertFalse(c)
        assertTrue(StatusInsetPolicy.stretched(true, false, c))
    }

    @Test fun leavingTopCollapsesEvenWhenBarIsShown() {
        // 下滑唤出栏（imm = false）但页面不在顶部：不能把留白放回来
        val c = collapsed(imm = false, top = false)
        assertTrue(c)
        assertFalse(StatusInsetPolicy.stretched(false, false, c))
    }

    @Test fun scrollingDownWithBarHiddenCollapses() {
        val c = collapsed(imm = true, top = false)
        assertTrue(c)
        assertFalse(StatusInsetPolicy.stretched(false, false, c))
    }

    @Test fun backAtTopRestoresInsetAndStretch() {
        val c = collapsed(imm = false, top = true)
        assertFalse(c)
        assertTrue(StatusInsetPolicy.stretched(true, false, c))
    }

    @Test fun pageWithFixedTopBarAlwaysKeepsInsetAndStretches() {
        val c = collapsed(imm = true, keep = true, top = false)
        assertFalse(c)
        assertTrue(StatusInsetPolicy.stretched(false, true, c))
    }

    @Test fun homePageAndInactiveBandAreNotCollapsedByScroll() {
        assertFalse(collapsed(page = false, top = false))
        assertFalse(collapsed(band = false, top = false))
    }
}
