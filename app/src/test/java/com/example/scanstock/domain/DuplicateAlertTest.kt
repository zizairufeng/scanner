package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重复码轻提示的两块纯逻辑（v1.7 起，v1.8.1 文案压缩）：文案 + 提示节流。
 *
 * 为什么单独测节流：提示最怕两件事——
 *  1. **漏提示**：用户扫到已存在的码却什么都没看到（那正是 v1.7 要修的问题）；
 *  2. **连提示**：手机一直对着同一个码，每秒闪一次，根本没法继续扫。
 * 两者的边界都在时间上，靠人眼 review 看不出来，只能靠用例钉住。
 */
class DuplicateAlertTest {

    private fun alert(mode: DedupeMode, qty: Int = 1, row: Int = 3, times: Int = 2) = DuplicateAlert(
        value = "JR5UCCB25B007JG",
        rowNumber = row,
        qty = qty,
        scanTimes = times,
        mode = mode
    )

    // ───────────────────────── 文案 ─────────────────────────

    @Test
    fun `严格去重_标题说明未计入`() {
        val a = alert(DedupeMode.STRICT)
        assertTrue(a.blocked)
        assertTrue(a.title().contains("未计入"))
    }

    @Test
    fun `计数聚合_标题直接写到数量`() {
        val a = alert(DedupeMode.COUNT, qty = 7)
        assertFalse(a.blocked)
        // 轻提示只有 2.5 秒，数量必须写进标题，不能让用户自己回列表核对
        assertTrue(a.title().contains("已累计 ×7"))
    }

    @Test
    fun `允许重复_标题说明已重复`() {
        assertTrue(alert(DedupeMode.ALLOW).title().contains("已重复"))
    }

    @Test
    fun `副标题含行号与次数`() {
        val text = alert(DedupeMode.STRICT).detail()
        assertTrue(text.contains("第 3 行"))
        assertTrue(text.contains("第 2 次"))
    }

    @Test
    fun `行号未知时不显示第0行`() {
        val text = alert(DedupeMode.STRICT, row = 0).detail()
        assertFalse(text.contains("第 0 行"))
        assertTrue(text.contains("已在列表"))
    }

    @Test
    fun `标题必须短到一眼能读完`() {
        // 护栏：v1.7 的标题是「重复码 · 本次未计入」这类长句，配上三行正文就成了弹框。
        // 既然改成了浮条，标题超过 12 个字就说明有人又把说明文塞回来了。
        DedupeMode.entries.forEach { mode ->
            val title = alert(mode, qty = 8).title()
            assertTrue("「$title」太长，轻提示装不下", title.length <= 12)
        }
    }

    // ───────────────────────── 节流 ─────────────────────────

    @Test
    fun `第一次重复立刻弹`() {
        val monitor = DuplicateMonitor()
        assertTrue(monitor.recordAndShouldAlert("A", 1_000L))
        assertEquals(1, monitor.hitsOf("A"))
    }

    @Test
    fun `同码冷却期内不弹但次数照记`() {
        val monitor = DuplicateMonitor(perValueCooldownMs = 3_000L, globalCooldownMs = 0L)
        assertTrue(monitor.recordAndShouldAlert("A", 0L))
        assertFalse(monitor.recordAndShouldAlert("A", 1_500L))   // 1.5s 后：不弹
        assertEquals("命中次数必须累加，否则「第 N 次」会说错", 2, monitor.hitsOf("A"))
        assertTrue(monitor.recordAndShouldAlert("A", 3_000L))     // 满 3s：可以再弹
    }

    @Test
    fun `不同码之间受全局冷却约束`() {
        val monitor = DuplicateMonitor(perValueCooldownMs = 3_000L, globalCooldownMs = 1_200L)
        assertTrue(monitor.recordAndShouldAlert("A", 0L))
        // 货架上一排同款：B 在 300ms 后命中，不该紧接着弹第二个框
        assertFalse(monitor.recordAndShouldAlert("B", 300L))
        assertTrue(monitor.recordAndShouldAlert("B", 1_200L))
    }

    @Test
    fun `forget之后重新当作第一次`() {
        val monitor = DuplicateMonitor(perValueCooldownMs = 3_000L, globalCooldownMs = 0L)
        assertTrue(monitor.recordAndShouldAlert("A", 0L))
        monitor.forget("A")
        assertEquals(0, monitor.hitsOf("A"))
        assertTrue(monitor.recordAndShouldAlert("A", 10L))
    }

    @Test
    fun `reset清空全部状态`() {
        val monitor = DuplicateMonitor(globalCooldownMs = 0L)
        assertTrue(monitor.recordAndShouldAlert("A", 0L))
        monitor.reset()
        assertEquals(0, monitor.hitsOf("A"))
        // 全局冷却也被清掉：立刻就能弹
        assertTrue(monitor.recordAndShouldAlert("B", 1L))
    }

    @Test
    fun `空码值永远不弹`() {
        assertFalse(DuplicateMonitor().recordAndShouldAlert("", 0L))
    }
}
