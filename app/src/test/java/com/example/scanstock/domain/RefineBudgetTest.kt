package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补救链总预算的单测（v1.8.2）。
 *
 * ## 为什么这几十行值得单测
 *
 * 这个类唯一的职责就是「到点就停」，而它的两个失败方向都不会报错：
 *  - **判太松**：等于没加闸门，还是会 700ms 卡一帧，优化白做；
 *  - **判太紧**：一次尝试都开不了，等于把精读、DPM 阶梯整套能力悄悄关掉。
 *
 * 两种错都只在「边界那一毫秒」上体现，真机上手不可能摸出来，只能靠用例钉住。
 */
class RefineBudgetTest {

    @Test
    fun `刚开工时剩余等于总预算`() {
        val budget = RefineBudget(startedAtMs = 1_000L, totalMs = 220L)
        assertEquals(0L, budget.spent(1_000L))
        assertEquals(220L, budget.remaining(1_000L))
    }

    @Test
    fun `花掉多少就少多少`() {
        val budget = RefineBudget(startedAtMs = 1_000L, totalMs = 220L)
        assertEquals(120L, budget.spent(1_120L))
        assertEquals(100L, budget.remaining(1_120L))
    }

    @Test
    fun `超支时剩余为负而不是被截成零`() {
        // 保留负值：调用方可能要据此判断「已经超了多少」，截成 0 会丢掉这个信息
        val budget = RefineBudget(startedAtMs = 1_000L, totalMs = 220L)
        assertEquals(300L, budget.spent(1_300L))
        assertEquals(-80L, budget.remaining(1_300L))
    }

    @Test
    fun `时钟回拨时已花时间按零算`() {
        val budget = RefineBudget(startedAtMs = 1_000L, totalMs = 220L)
        assertEquals(0L, budget.spent(900L))
        assertEquals(220L, budget.remaining(900L))
    }

    @Test
    fun `剩余刚好等于一次尝试成本时允许开始`() {
        // 边界取「>=」而不是「>」：剩 25ms 就够跑一次最便宜的尝试，放弃它是白丢机会
        val budget = RefineBudget(startedAtMs = 0L, totalMs = 220L)
        assertTrue(budget.canStart(minCostMs = 25L, nowMs = 195L))
    }

    @Test
    fun `剩余差一毫秒就不开始新尝试`() {
        val budget = RefineBudget(startedAtMs = 0L, totalMs = 220L)
        assertFalse(budget.canStart(minCostMs = 25L, nowMs = 196L))
    }

    @Test
    fun `canStart 会把预留一起算进去`() {
        // refinePass 用 canStart(MIN_PASS + 预留) 表达「跑完这一级还得给 DPM 留够」
        val budget = RefineBudget(startedAtMs = 0L, totalMs = 220L)
        // 已花 100ms：剩 120ms。要开一次 25ms 的尝试并留 90ms 给阶梯 → 需要 115 ≤ 120
        assertTrue(budget.canStart(minCostMs = 25L + 90L, nowMs = 100L))
        // 已花 110ms：剩 110ms < 115 → 不开
        assertFalse(budget.canStart(minCostMs = 25L + 90L, nowMs = 110L))
    }
}
