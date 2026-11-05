package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「中心区补扫」的几何单测（v1.7）。
 *
 * 这块逻辑的立足点是一条官方硬约束：**Data Matrix 必须压住输入图像的中心点**
 * 才会被识别。所以补扫裁出来的那一块，中心必须与全帧中心重合——
 * 偏一个像素都会让「码本来在画面中央却仍然解不出」。
 *
 * 用例覆盖四个旋转角与非法输入：90/270 度时宽高互换，是这类裁剪代码最常见的错。
 */
class RoiPlannerCenterTest {

    private val bufferW = 1280
    private val bufferH = 720

    @Test
    fun `零度_裁中央60%且中心重合`() {
        val plan = RoiPlanner.centerPlan(bufferW, bufferH, 0)
        assertNotNull(plan)
        plan!!
        assertEquals(768, plan.crop.width)          // 1280 * 0.6
        assertEquals(432, plan.crop.height)         // 720 * 0.6
        assertEquals(256, plan.crop.left)           // (1280-768)/2
        assertEquals(144, plan.crop.top)            // (720-432)/2
        assertEquals(0, plan.rotationDegrees)

        // 中心必须与全帧中心重合（容差 1px：整除取整）
        assertTrue(kotlin.math.abs(plan.crop.left + plan.crop.width / 2 - bufferW / 2) <= 1)
        assertTrue(kotlin.math.abs(plan.crop.top + plan.crop.height / 2 - bufferH / 2) <= 1)
    }

    @Test
    fun `零度_放大到目标长边1200`() {
        val plan = RoiPlanner.centerPlan(bufferW, bufferH, 0)!!
        // 1200 / 768 = 1.5625，未触到 2x 上限
        assertEquals(1.5625f, plan.scale, 0.001f)
        assertEquals(1200, plan.outputWidth)
    }

    @Test
    fun `九十度与二百七十度_宽高互换但裁剪矩形不变`() {
        val p0 = RoiPlanner.centerPlan(bufferW, bufferH, 0)!!
        val p90 = RoiPlanner.centerPlan(bufferW, bufferH, 90)!!
        val p270 = RoiPlanner.centerPlan(bufferW, bufferH, 270)!!

        // 裁剪矩形是在原始缓冲区上取的，与旋转无关：旋转只绕中心，中心不动
        assertEquals(p0.crop, p90.crop)
        assertEquals(p0.crop, p270.crop)
        assertEquals(90, p90.rotationDegrees)
        assertEquals(270, p270.rotationDegrees)
        // 摆正后长边仍是 768（432 与 768 取大），放大倍率因此不变
        assertEquals(p0.scale, p90.scale, 0.001f)
    }

    @Test
    fun `一百八十度_裁剪不变`() {
        val p0 = RoiPlanner.centerPlan(bufferW, bufferH, 0)!!
        val p180 = RoiPlanner.centerPlan(bufferW, bufferH, 180)!!
        assertEquals(p0.crop, p180.crop)
        assertEquals(180, p180.rotationDegrees)
    }

    @Test
    fun `放大不超过上限`() {
        // 极小的分析帧：长边只有 240，按 1200 算需要 5 倍，但上限是 2 倍
        val plan = RoiPlanner.centerPlan(400, 240, 0)!!
        assertEquals(RoiPlanner.CENTER_MAX_UPSCALE, plan.scale, 0.001f)
    }

    @Test
    fun `非法输入返回null而不是崩溃`() {
        assertNull(RoiPlanner.centerPlan(0, 0, 0))
        assertNull(RoiPlanner.centerPlan(-10, 720, 0))
        // 不认识的旋转角：宁可放弃，也不猜一个坐标系
        assertNull(RoiPlanner.centerPlan(bufferW, bufferH, 45))
    }

    @Test
    fun `比例越界会被夹到合法区间`() {
        val tiny = RoiPlanner.centerPlan(bufferW, bufferH, 0, fraction = 0.01f)!!
        // 下限 0.2：再小就没有静区了
        assertEquals((bufferW * 0.2f).toInt(), tiny.crop.width)

        val full = RoiPlanner.centerPlan(bufferW, bufferH, 0, fraction = 5f)!!
        assertEquals(bufferW, full.crop.width)
        assertEquals(bufferH, full.crop.height)
        // 整帧就是「无需放大」
        assertEquals(1f, full.scale, 0.001f)
    }
}
