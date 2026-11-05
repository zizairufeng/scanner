package com.example.scanstock.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DPM（金属蚀刻码）尺寸阶梯的纯逻辑测试（v1.8）。
 *
 * ## 为什么这条阶梯值得单测
 *
 * 它建立在一条**被实测钉死的经验规律**上：分析区域只能比码大 1.2~1.8 倍，
 * 超过 2 倍就解不出（见 [RoiPlanner.DPM_LADDER_RATIOS] 的表格）。
 * 于是这组比例的选取必须满足一条数学性质——**任意尺寸的码，至少有一级落在窗内**。
 * 这条性质一改数字就可能被破坏，而且破坏后现场表现是「某些码偶尔能扫、大部分不能」，
 * 极难察觉。所以把它写成断言，比写在注释里可靠。
 */
class RoiPlannerDpmLadderTest {

    /** 720p 分析流：短边 720 */
    private val bufferW = 1280
    private val bufferH = 720

    @Test
    fun `阶梯按比例从大到小_且全部居中`() {
        val plans = RoiPlanner.dpmLadder(bufferW, bufferH, 0)

        assertEquals(RoiPlanner.DPM_LADDER_RATIOS.size, plans.size)
        // 边长必须单调不增：越大越松、越紧越准
        plans.zipWithNext { a, b ->
            assertTrue(
                "阶梯必须从大到小：${a.crop.width} -> ${b.crop.width}",
                a.crop.width >= b.crop.width
            )
        }
        // 每一级都必须居中（码必须压住画面中心，Data Matrix 的硬约束）
        for (plan in plans) {
            assertEquals(
                "裁块必须水平居中",
                (bufferW - plan.crop.width) / 2,
                plan.crop.left
            )
            assertEquals(
                "裁块必须垂直居中",
                (bufferH - plan.crop.height) / 2,
                plan.crop.top
            )
            assertTrue("裁块不得超出缓冲区", plan.crop.left + plan.crop.width <= bufferW)
            assertTrue("裁块不得超出缓冲区", plan.crop.top + plan.crop.height <= bufferH)
        }
    }

    @Test
    fun `每一级的放大目标都等于长边目标_且在倍率上限内`() {
        for (plan in RoiPlanner.dpmLadder(bufferW, bufferH, 0)) {
            val longSide = maxOf(plan.outputWidth, plan.outputHeight)
            assertEquals(
                "放大后长边应顶到 ${RoiPlanner.DPM_TARGET_LONG_SIDE_PX}px",
                RoiPlanner.DPM_TARGET_LONG_SIDE_PX.toFloat(),
                longSide.toFloat(),
                1.5f
            )
            assertTrue(
                "放大倍率不得超过上限 ${RoiPlanner.DPM_MAX_UPSCALE}",
                plan.scale <= RoiPlanner.DPM_MAX_UPSCALE
            )
            assertTrue("放大倍率不得小于 1", plan.scale >= 1f)
        }
    }

    /**
     * 核心断言：**黄金窗口覆盖性**。
     *
     * 黄金窗口 = [1.2, 1.8]（裁块边长 ÷ 码边长）。
     * 相邻两级比例步长 1.3 倍，而窗口宽 1.5 倍 > 1.3，所以对任意码尺寸，
     * 一定存在一级落在窗内。这里把 48px~600px 的所有码尺寸逐个验一遍。
     */
    @Test
    fun `任意尺寸的码都至少有一级落在1点2到1点8倍的黄金窗口`() {
        val plans = RoiPlanner.dpmLadder(bufferW, bufferH, 0)
        val sides = plans.map { it.crop.width.toFloat() }

        var checked = 0
        var codeSide = 48
        while (codeSide <= 600) {
            val hit = sides.any { side ->
                val ratio = side / codeSide
                ratio in 1.2f..1.8f
            }
            assertTrue(
                "码边长 ${codeSide}px 没有任何一级落在 1.2~1.8 倍窗口内，" +
                    "阶梯比例为 ${RoiPlanner.DPM_LADDER_RATIOS.toList()}",
                hit
            )
            checked++
            codeSide += 4
        }
        assertTrue("应该覆盖足够多的尺寸样本", checked > 100)
    }

    @Test
    fun `order 可以把上次命中的级别提到最前`() {
        val normal = RoiPlanner.dpmLadder(bufferW, bufferH, 0)
        val reordered = RoiPlanner.dpmLadder(bufferW, bufferH, 0, intArrayOf(4, 0, 1, 2, 3, 5, 6, 7, 8))

        assertEquals(normal.size, reordered.size)
        // 第一级应当是默认阶梯里的第 5 级（下标 4）
        assertEquals(normal[4].crop.width, reordered[0].crop.width)
        // 顺序变了，但集合一致（不能丢级）
        assertEquals(
            normal.map { it.crop.width }.sorted(),
            reordered.map { it.crop.width }.sorted()
        )
    }

    @Test
    fun `越界的 level 与 order 下标被安全忽略`() {
        assertNull(RoiPlanner.dpmPlan(bufferW, bufferH, 0, -1))
        assertNull(RoiPlanner.dpmPlan(bufferW, bufferH, 0, RoiPlanner.DPM_LADDER_RATIOS.size))

        // order 里混入非法下标：只跳过它们，合法的照常返回
        val plans = RoiPlanner.dpmLadder(bufferW, bufferH, 0, intArrayOf(-3, 0, 99))
        assertEquals(1, plans.size)
        assertEquals((720 * 1.0f).toInt(), plans[0].crop.width)
    }

    @Test
    fun `非法尺寸返回空阶梯而不是抛异常`() {
        assertTrue(RoiPlanner.dpmLadder(0, 0, 0).isEmpty())
        assertTrue(RoiPlanner.dpmLadder(-100, 720, 0).isEmpty())
        assertNull(RoiPlanner.dpmPlan(0, 0, 0, 0))
        // 旋转角不是 0/90/180/270 视为不可用
        assertNull(RoiPlanner.dpmPlan(bufferW, bufferH, 45, 0))
    }

    @Test
    fun `竖屏缓冲区同样居中且不越界`() {
        val w = 720
        val h = 1280
        val plans = RoiPlanner.dpmLadder(w, h, 90)
        assertTrue(plans.isNotEmpty())
        for (plan in plans) {
            assertEquals((w - plan.crop.width) / 2, plan.crop.left)
            assertEquals((h - plan.crop.height) / 2, plan.crop.top)
            assertTrue(plan.crop.left + plan.crop.width <= w)
            assertTrue(plan.crop.top + plan.crop.height <= h)
        }
        assertNotNull(plans.first())
    }

    @Test
    fun `最细一级不小于最小可识边长`() {
        val plans = RoiPlanner.dpmLadder(bufferW, bufferH, 0)
        val smallest = plans.minOf { it.crop.width }
        assertTrue(
            "最细一级 ${smallest}px 小于最小可识边长 ${RoiPlanner.MIN_VIABLE_SIDE_PX}px",
            smallest >= RoiPlanner.MIN_VIABLE_SIDE_PX
        )
        // 720 * 0.12 ≈ 86
        assertEquals(86, smallest)
    }

    // ───────────────── 轮转顺序（v1.8.2 加总预算后必须有）─────────────────

    @Test
    fun `从零开始的顺序就是从大到小`() {
        val order = RoiPlanner.levelOrder(start = 0)
        assertEquals(RoiPlanner.DPM_LADDER_RATIOS.size, order.size)
        assertArrayEquals(RoiPlanner.DPM_LADDER_RATIOS.indices.toList().toIntArray(), order)
    }

    @Test
    fun `从中间开始会绕回开头_不重不漏`() {
        // 这是「一轮扫不完也要覆盖全部级别」的正确性前提：
        // 3 号开始 → 3,4,5,6,7,8,0,1,2
        val order = RoiPlanner.levelOrder(start = 3).toList()
        assertEquals(RoiPlanner.DPM_LADDER_RATIOS.size, order.toSet().size)
        assertEquals(listOf(3, 4, 5, 6, 7, 8, 0, 1, 2), order)
    }

    @Test
    fun `越界与负数起点会被规范化`() {
        // 调用方给的是「上次命中级别 +1」或游标，长期运行后可能越界，不能因此漏扫
        assertEquals(0, RoiPlanner.levelOrder(start = 9).first())
        assertEquals(RoiPlanner.DPM_LADDER_RATIOS.size - 1, RoiPlanner.levelOrder(start = -1).first())
    }

    @Test
    fun `每轮被预算截断时_多轮仍能覆盖全部级别`() {
        // 这是 v1.8.2 加总预算后最关键的一条性质：
        // 一轮只够试 3 级（约 90ms），如果不记「停在哪」，小码需要的那几级永远等不到。
        val total = RoiPlanner.DPM_LADDER_RATIOS.size
        val perRound = 3
        val covered = mutableSetOf<Int>()
        var cursor = 0
        var rounds = 0
        while (covered.size < total && rounds < 10) {
            val order = RoiPlanner.levelOrder(cursor)
            repeat(perRound) { covered += order[it] }
            cursor = RoiPlanner.cursorAfter(start = cursor, triedCount = perRound)
            rounds++
        }
        assertEquals("应覆盖全部 $total 级", total, covered.size)
        assertEquals("每轮 $perRound 级，三轮正好扫完", 3, rounds)
        assertEquals("绕满一圈后游标回到起点", 0, cursor)
    }

    @Test
    fun `游标推进一格就是下一级`() {
        assertEquals(1, RoiPlanner.cursorAfter(start = 0, triedCount = 1))
        assertEquals(0, RoiPlanner.cursorAfter(start = RoiPlanner.DPM_LADDER_RATIOS.size - 1, triedCount = 1))
    }
}
