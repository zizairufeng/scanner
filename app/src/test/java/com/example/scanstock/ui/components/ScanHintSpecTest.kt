package com.example.scanstock.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏规范的护栏（v1.8.6，取代 v1.8.4 的浮层版）。
 *
 * 这些断言**不测行为，只锁住设计决策**。上一版扫描页之所以「乱」，直接原因是
 * 每加一个提示就顺手写一个私有 `const` 和一句裸 `Color(0x…)`：最后攒出
 * 4 档时长、6 种颜色、5 个落点。这类腐化靠 code review 挡不住——它每次都只多加"一条"。
 *
 * 所以把规则写成断言：以后谁想加第四种语义、第三档时长，先让这条测试红。
 * 真的要改规范，就得连着改这里，等于强制在评审里讨论一次。
 */
class ScanHintSpecTest {

    @Test
    fun `时长只有三档_撤销窗口最长`() {
        // 信息 = 扫一眼就走（识别回执、重复码）；动作 = 用户得抬手调整（失焦）；
        // 撤销 = 唯一「错过就找不回来」的操作，单独一档，必须最长。
        assertTrue("信息档必须短于动作档", ScanHintSpec.INFO_MS < ScanHintSpec.ACTION_MS)
        assertTrue("撤销窗口必须长于动作档", ScanHintSpec.ACTION_MS < ScanHintSpec.UNDO_MS)
        // 都必须是"能读完一行"的量级：太短等于闪一下，太长等于挡视线
        assertTrue(ScanHintSpec.INFO_MS in 1_500..3_000)
        assertTrue(ScanHintSpec.ACTION_MS in 3_000..5_000)
        // 撤销窗口同时是删除快照的有效期：短了来不及点，长了像没删掉
        assertEquals("撤销窗口变了要同步 ViewModel 的快照有效期", 4_000L, ScanHintSpec.UNDO_MS)
    }

    @Test
    fun `语义只有三种_成功_动作_中性`() {
        // 加第四种颜色之前先想清楚：它和「动作」有什么本质区别？
        // 想不出来就别加——上一版的六种橙就是这么来的。
        assertEquals(3, HintTone.values().size)
        assertEquals(
            listOf(HintTone.SUCCESS, HintTone.ACTION, HintTone.NEUTRAL),
            HintTone.values().toList()
        )
    }

    @Test
    fun `状态栏高度必须同时放得下两行字和撤销按钮`() {
        // 52dp 要装：14sp 主文案（约 20dp）+ 12sp 码值（约 16dp）+ 上下呼吸
        assertTrue("状态栏太矮，两行字会挤", ScanHintSpec.STATUS_BAR_HEIGHT_DP >= 44)
        // 也不能太高：它常驻在取景画面下方，每多 1dp 都是从画面里抢的
        assertTrue("状态栏太高，会明显压缩取景画面", ScanHintSpec.STATUS_BAR_HEIGHT_DP <= 64)
        // 「撤销」只有 4 秒窗口，按钮必须好按——36dp 是这里给按钮的最小高度
        assertTrue(
            "撤销按钮的 36dp 必须塞得进状态栏",
            ScanHintSpec.STATUS_BAR_HEIGHT_DP - 1 > 36
        )
    }

    @Test
    fun `语义圆点不能比文字还大`() {
        // 圆点只是辅助色块，做得比字高就会喧宾夺主
        assertTrue(ScanHintSpec.DOT_SIZE_DP in 4..9)
    }

    @Test
    fun `位置引导不能常驻`() {
        // v1.8.4 之前它永久挂在取景框下沿，正好挡着用户要看清的那块画面。
        // 现在它只是状态栏里轮换的一条，必须有明确的停留时长。
        assertTrue(ScanHintSpec.POSITION_GUIDE_MS in 4_000..12_000)
    }
}
