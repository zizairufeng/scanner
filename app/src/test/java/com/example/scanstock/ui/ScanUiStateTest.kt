package com.example.scanstock.ui

import com.example.scanstock.domain.CodeRule
import com.example.scanstock.domain.CopyFormat
import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.DedupeTracker
import com.example.scanstock.domain.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认值回归测试。
 *
 * [ScanUiState] 是不依赖任何 Android 类型的纯 data class，可以直接在 JVM 单测里实例化。
 * 这里锁住几个由产品行为明确指定的默认值，防止后续改动无意把它们改回去：
 *
 *   1. 「复制全部」默认输出**纯文本**（每行只有码值）
 *   2. 重复码口径默认**严格去重**
 *   3. 自动变焦默认**开启**、会话保存默认**开启**
 *   4. 准入规则默认**关闭**（不限制）
 */
class ScanUiStateTest {

    @Test
    fun `复制全部默认使用纯文本格式`() {
        assertEquals(CopyFormat.PLAIN, ScanUiState().copyFormat)
    }

    @Test
    fun `纯文本排在设置面板的第一位`() {
        assertEquals(
            "枚举顺序即设置面板显示顺序，默认项应在第一位",
            CopyFormat.PLAIN,
            CopyFormat.entries.first()
        )
    }

    @Test
    fun `重复码口径默认为严格去重`() {
        assertEquals(DedupeMode.STRICT, ScanUiState().dedupeMode)
        assertEquals(DedupeMode.STRICT, DedupeMode.DEFAULT)
    }

    @Test
    fun `严格去重排在口径列表的第一位`() {
        // 口径列表按「最保守 → 最宽松」排列，默认项在最前
        assertEquals(DedupeMode.STRICT, DedupeMode.entries.first())
    }

    @Test
    fun `去重默认时间窗为1500毫秒`() {
        assertEquals(DedupeTracker.DEFAULT_WINDOW_MS, ScanUiState().dedupeWindowMs)
        assertEquals(1_500L, ScanUiState().dedupeWindowMs)
    }

    @Test
    fun `自动变焦默认开启`() {
        assertTrue("自动变焦零成本且直接提升识别率，默认必须开着", ScanUiState().autoZoomEnabled)
    }

    @Test
    fun `会话保存默认开启`() {
        assertTrue("防丢是默认行为，不该让用户先去设置里打开", ScanUiState().persistEnabled)
    }

    @Test
    fun `准入规则默认关闭且不限制`() {
        assertFalse(ScanUiState().codeRule.enabled)
        assertTrue(ScanUiState().codeRule.isBlank())
    }

    @Test
    fun `数量合计在非计数模式下等于条数`() {
        val state = ScanUiState(
            items = listOf(
                ScanItem(1, 0, "EAN-13", "A", 0L, "t"),
                ScanItem(2, 0, "EAN-13", "B", 0L, "t")
            )
        )
        assertEquals(2, state.count)
        assertEquals(2, state.totalQty)
    }

    @Test
    fun `数量合计在计数聚合模式下大于条数`() {
        val state = ScanUiState(
            items = listOf(
                ScanItem(1, 0, "EAN-13", "A", 0L, "t", qty = 3),
                ScanItem(2, 0, "EAN-13", "B", 0L, "t", qty = 1)
            )
        )
        assertEquals(2, state.count)
        assertEquals(4, state.totalQty)
    }
}
