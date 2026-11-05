package com.example.scanstock.ui

import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.DuplicateAlert
import com.example.scanstock.ui.components.HintTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏优先级护栏（v1.8.6）。
 *
 * ## 为什么单独测这个
 *
 * 这一版的教训是：**提示放在哪里，比提示长什么样更重要**。
 * v1.8.4 已经把颜色收成三种、时长收成两档、落点收成三个，看着很规范，
 * 但三个落点全在相机画面上——用户的原话是「都看不到是否清楚了」。
 *
 * 现在所有提示挤进画面下方同一条状态栏，于是「谁顶替谁」从"排版问题"变成了
 * 唯一的信息调度问题。一旦顺序写错，用户会看到最不重要的那条，而真正要紧的
 * （撤销、重复码）被静默吞掉。所以把它拆成纯函数 [statusOf] 并逐条锁住。
 */
class StatusPriorityTest {

    private fun undo(action: String? = "撤销") =
        Notice(id = 1, text = "已删除 1 条", tone = HintTone.NEUTRAL, actionLabel = action, onAction = {})

    private fun copied() =
        Notice(id = 2, text = "已复制该条码值", tone = HintTone.SUCCESS)

    private fun dup() = DuplicateAlert(
        value = "6901234567892",
        rowNumber = 3,
        qty = 1,
        scanTimes = 2,
        mode = DedupeMode.STRICT
    )

    /** 只是为了少写几个具名参数，真正的被测函数是 [statusOf] */
    private fun status(
        undoNotice: Notice? = null,
        notice: Notice? = null,
        duplicate: DuplicateAlert? = null,
        guide: Guide? = null,
        hitValue: String? = null,
        hitRow: Int = 0,
        perfText: String? = null
    ) = statusOf(undoNotice, notice, duplicate, guide, hitValue, hitRow, perfText)

    @Test
    fun `撤销压过一切`() {
        // 撤销窗口只有 4 秒，过了就真找不回来——它是唯一「错过有代价」的一条
        val s = status(undoNotice = undo(), notice = copied(), duplicate = dup(),
            guide = Guide.FOCUS, hitValue = "X", hitRow = 9)
        assertEquals("已删除 1 条", s.text)
        assertEquals("撤销", s.actionLabel)
        assertNotNull("撤销必须带可点回调，否则用户点不动", s.onAction)
        assertEquals("撤销是中性信息，不该抢语义色", HintTone.NEUTRAL, s.tone)
    }

    @Test
    fun `复制回执压过重复码_但压不过撤销`() {
        // 复制是用户刚主动做的事，必须立刻确认；它 2.5 秒就收，
        // 让位给重复码也不亏。而撤销一旦被顶掉就再也点不到了，所以它永远在最前。
        val withDup = status(notice = copied(), duplicate = dup())
        assertEquals("已复制该条码值", withDup.text)
        assertEquals(HintTone.SUCCESS, withDup.tone)

        val withUndo = status(undoNotice = undo(), notice = copied())
        assertEquals("撤销不能被复制回执顶掉", "已删除 1 条", withUndo.text)
    }

    @Test
    fun `撤销按钮必须带得动回调`() {
        // 回归护栏：v1.8.6 里撤销按钮是有回调的，但整个事件流被卡死了，
        // 点下去一样没反应。回调存在 + 定时器独立 = 这条链路才算完整。
        val s = status(undoNotice = undo())
        assertEquals("撤销", s.actionLabel)
        assertNotNull(s.onAction)
    }

    @Test
    fun `重复码压过失焦和识别回执`() {
        val s = status(duplicate = dup(), guide = Guide.POTENTIAL, hitValue = "X", hitRow = 9)
        assertTrue("重复码提示必须出现", s.text.contains("重复码"))
        assertEquals(HintTone.ACTION, s.tone)
    }

    @Test
    fun `重复码必须同时说清结论_位置和码值`() {
        // 严格去重挡掉时画面毫无反应，用户只能靠这条判断该不该继续对准；
        // 少了「第几行」，他就不知道该去哪儿核对
        val s = status(duplicate = dup())
        assertTrue("要说清算没算：${s.text}", s.text.contains("未计入"))
        assertTrue("要说清落在第几行：${s.text}", s.text.contains("第 3 行"))
        assertTrue("要说清第几次：${s.text}", s.text.contains("第 2 次"))
        assertEquals("码值必须原样保留", "6901234567892", s.value)
    }

    @Test
    fun `失焦压过识别回执`() {
        // 用户正在做错一件事（凑太近），比「刚才扫到什么」重要
        val s = status(guide = Guide.FOCUS, hitValue = "6901234567892", hitRow = 9)
        assertTrue(s.text.contains("失焦"))
        assertEquals(HintTone.ACTION, s.tone)
        assertNull("这条不该带码值", s.value)
    }

    @Test
    fun `识别回执压过位置引导`() {
        // 回执是即时反馈，位置引导是新手常识，随时可以让位
        val s = status(guide = Guide.POSITION, hitValue = "6901234567892", hitRow = 9)
        assertTrue(s.text.contains("已计入"))
        assertEquals(HintTone.SUCCESS, s.tone)
        assertEquals("6901234567892", s.value)
    }

    @Test
    fun `性能提示是最低级的一条`() {
        val s = status(guide = Guide.POSITION, hitValue = "X", hitRow = 1, perfText = "识别 4.2 fps")
        assertTrue("有回执时不该让诊断信息顶掉它", s.text.contains("已计入"))

        val onlyPerf = status(perfText = "识别 4.2 fps")
        assertEquals("识别 4.2 fps", onlyPerf.text)
    }

    @Test
    fun `什么都没发生时也必须有内容`() {
        // 状态栏高度固定，绝不能空着——空着就是一段白占画面的黑条
        val s = status()
        assertTrue("常驻引导不能是空字符串", s.text.isNotBlank())
        assertNull(s.actionLabel)
        assertNull(s.value)
        assertEquals(HintTone.NEUTRAL, s.tone)
    }

    @Test
    fun `没有动作的提示不带可点区域`() {
        // Snackbar 有时只有一句「已复制」而没有按钮，别渲染出一个点不动的按钮位
        val s = status(undoNotice = undo(action = null))
        assertNull(s.actionLabel)
        assertNull(s.onAction)
    }
}
