package com.example.scanstock.domain

import com.example.scanstock.domain.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置面板分页结构的单测（v1.4.3）。
 *
 * 这组测试存在的意义：v1.4.1 / v1.4.2 两次「修布局」都失败了，
 * 根因是设置项数量超过一屏后**没有结构性的可达性保证**。
 * 分页是那个保证，所以它的边界必须被测试锁住——
 * 加页、改顺序、改默认页都不能破坏「每一项都落在一页里」。
 */
class SettingsPageTest {

    @Test
    fun `默认页是扫码页`() {
        assertEquals(SettingsPage.SCAN, SettingsPage.DEFAULT)
        assertEquals("打开设置面板应当停在最常用的一页", SettingsPage.SCAN, SettingsPage.entries.first())
    }

    @Test
    fun `每一个分类都有非空的标签和说明`() {
        SettingsPage.entries.forEach { page ->
            assertTrue("${page.name} 的分类标签不能为空", page.label.isNotBlank())
            assertTrue("${page.name} 的页面说明不能为空", page.subtitle.isNotBlank())
        }
    }

    @Test
    fun `分类标签互不重复`() {
        val labels = SettingsPage.entries.map { it.label }
        assertEquals("标签重复会让用户分不清自己在哪一页", labels.size, labels.toSet().size)
    }

    @Test
    fun `分类标签足够短以容纳标签条`() {
        // 4 个标签横向并排，单个超过 4 个汉字在窄屏上会把标签条挤成两行
        SettingsPage.entries.forEach { page ->
            assertTrue("${page.label} 超过 4 个字，标签条会换行", page.label.length <= 4)
        }
    }

    @Test
    fun `越界序号回落到默认页`() {
        assertEquals(SettingsPage.ENGINE, SettingsPage.fromOrdinal(1))
        assertEquals(SettingsPage.ACCURACY, SettingsPage.fromOrdinal(2))
        assertEquals(SettingsPage.DATA, SettingsPage.fromOrdinal(3))
        assertEquals(SettingsPage.DEFAULT, SettingsPage.fromOrdinal(-1))
        assertEquals(SettingsPage.DEFAULT, SettingsPage.fromOrdinal(99))
    }

    @Test
    fun `翻页按序号前进`() {
        assertEquals(SettingsPage.ENGINE, SettingsPage.next(SettingsPage.SCAN))
        assertEquals(SettingsPage.ACCURACY, SettingsPage.next(SettingsPage.ENGINE))
        assertEquals(SettingsPage.DATA, SettingsPage.next(SettingsPage.ACCURACY))
    }

    @Test
    fun `最后一页翻页回到第一页而不是卡住`() {
        // 只放一个翻页按钮，所以末尾必须循环。卡住会让用户以为按钮坏了
        assertEquals(SettingsPage.SCAN, SettingsPage.next(SettingsPage.DATA))
    }

    @Test
    fun `连续翻满一圈回到原点`() {
        var page = SettingsPage.DEFAULT
        repeat(SettingsPage.entries.size) { page = SettingsPage.next(page) }
        assertEquals(SettingsPage.DEFAULT, page)
    }

    @Test
    fun `面板总高不超过sheet最大高度`() {
        // v1.4.3 的教训：预算拍成 360dp，复算后发现四页有三页超出（最多 153dp），
        // 溢出部分盖住翻页条 = 用户看到的「元素重叠」。
        // 这里把「固定开销 + 预算 ≤ sheet 上限」这条约束锁死，
        // 以后改任何一个常量都会被这条测试拦住。
        val total = SettingsPage.PANEL_TOTAL_HEIGHT_DP
        assertTrue(
            "面板总高 ${total}dp 超过 sheet 上限 ${SettingsPage.SHEET_MAX_HEIGHT_DP}dp，底部会被 sheet 自己裁掉",
            total <= SettingsPage.SHEET_MAX_HEIGHT_DP
        )
    }

    @Test
    fun `内容预算大到能装下复算过的最重页面`() {
        // 按 Material3 默认排版逐项复算（v1.7，识别页新增「双引擎补扫」开关）：
        //   识别 563 / 扫码 449 / 准确 441 / 结果 421 —— 最重的是识别页。
        // 预算必须至少容得下最重的那一页，否则默认打开就要滚动。
        // （即使超了也不会重叠——内容区有滚动兜底，这里只是要求「默认不滚」。）
        val heaviest = 563
        assertTrue(
            "预算 ${SettingsPage.PAGE_HEIGHT_BUDGET_DP}dp 装不下最重的页面（约 ${heaviest}dp）",
            SettingsPage.PAGE_HEIGHT_BUDGET_DP >= heaviest
        )
    }

    @Test
    fun `预算给最重页面留出容错余量`() {
        // 复算是估算：字号、字重、字体库不同都会让实际高度往上飘。
        // 留出的余量就是允许估算偏差的范围，太薄的话一有偏差就要滚动。
        val heaviest = 563
        val margin = SettingsPage.PAGE_HEIGHT_BUDGET_DP - heaviest
        assertTrue(
            "预算只比最重页面多 ${margin}dp，估算一偏就要滚动",
            margin >= 40
        )
    }

    @Test
    fun `固定开销与预算之和与UI取的是同一处`() {
        // 防止 UI 里又写一份 210 / 360 的硬编码，与这里的常量脱节后静默失效
        assertEquals(
            SettingsPage.PANEL_FIXED_OVERHEAD_DP + SettingsPage.PAGE_HEIGHT_BUDGET_DP,
            SettingsPage.PANEL_TOTAL_HEIGHT_DP
        )
        assertTrue("固定开销应为正", SettingsPage.PANEL_FIXED_OVERHEAD_DP > 0)
    }

    @Test
    fun `页数不多于标签条能容纳的数量`() {
        // 超过 5 页就该换成可横向滚动的标签条或二级页面，这里先卡住
        assertTrue("分类超过 5 个，标签条放不下", SettingsPage.entries.size <= 5)
    }

    @Test
    fun `识别引擎页确实存在且不是默认页`() {
        // v1.4 用户反馈「看不到新增项」，这条防止它被误删或被合并回扫码页
        assertTrue(SettingsPage.entries.contains(SettingsPage.ENGINE))
        assertNotEquals(SettingsPage.SCAN, SettingsPage.ENGINE)
    }
}
