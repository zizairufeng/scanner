package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROI 规划器的单测。
 *
 * 这个类里最需要被守住的是 [RoiPlanner.toBufferedCoords]：坐标系逆变换写错了
 * **不会报错、不会崩**，只会让精读永远裁到一块空白，表现为「识别率没提升」——
 * 是最难从现象反推根因的一类 bug。所以四种旋转都用具体数字逐一钉住。
 */
class RoiPlannerTest {

    // ══════════════════════════ 坐标逆变换（核心） ══════════════════════════

    @Test
    fun `未旋转时坐标原样传递`() {
        val (x, y) = RoiPlanner.toBufferedCoords(100, 200, 1920, 1080, 0)
        assertEquals(100, x)
        assertEquals(200, y)
    }

    /**
     * 90 度是最容易写反的一个：不仅要交换 x/y，还要用 **bufferHeight** 去减，
     * 而不是 bufferWidth。这两个数字在横屏缓冲区上差了一倍，写错了肉眼看不出来。
     *
     * 验证方式：摆正后坐标系是 `H×W`（宽 = 原始高），取它的四个角逆变换回去，
     * 必须正好落在原始缓冲区的四个角上。
     */
    @Test
    fun `旋转 90 度时按 bufferHeight 做镜像`() {
        // 原始缓冲区 1920×1080 → 摆正后 1080×1920
        val bufferW = 1920
        val bufferH = 1080

        // 摆正坐标系左上角 (0,0) → 原始坐标系左下角
        assertEquals(0 to (bufferH - 1), RoiPlanner.toBufferedCoords(0, 0, bufferW, bufferH, 90))
        // 摆正坐标系右上角 (H-1, 0) → 原始坐标系右下角
        assertEquals(
            0 to 0,
            RoiPlanner.toBufferedCoords(bufferH - 1, 0, bufferW, bufferH, 90)
        )
        // 摆正坐标系左下角 (0, W-1) → 原始坐标系左上角
        assertEquals(
            (bufferW - 1) to (bufferH - 1),
            RoiPlanner.toBufferedCoords(0, bufferW - 1, bufferW, bufferH, 90)
        )
        // 摆正坐标系右下角 → 原始坐标系右上角
        assertEquals(
            (bufferW - 1) to 0,
            RoiPlanner.toBufferedCoords(bufferH - 1, bufferW - 1, bufferW, bufferH, 90)
        )
    }

    @Test
    fun `旋转 180 度时两个方向都镜像`() {
        val (x, y) = RoiPlanner.toBufferedCoords(0, 0, 1920, 1080, 180)
        assertEquals(1919, x)
        assertEquals(1079, y)
    }

    @Test
    fun `旋转 270 度时按 bufferWidth 做镜像`() {
        val bufferW = 1920
        val bufferH = 1080
        // 摆正坐标系尺寸是 H×W = 1080×1920
        // 摆正左上角 (0,0) → 原始右上角 (W-1, 0)
        assertEquals(
            (bufferW - 1) to 0,
            RoiPlanner.toBufferedCoords(0, 0, bufferW, bufferH, 270)
        )
        // 摆正右下角 (H-1, W-1) → 原始左下角 (0, H-1)
        assertEquals(
            0 to (bufferH - 1),
            RoiPlanner.toBufferedCoords(bufferH - 1, bufferW - 1, bufferW, bufferH, 270)
        )
    }

    /**
     * 无论哪个角度、哪个点，逆变换的结果都必须落在原始缓冲区内部。
     *
     * 这条是「兜底断言」：上面几条只验证了几个角，这条扫全量，
     * 能抓住镜像方向写反（会跑到负数区间）这类错误。
     */
    @Test
    fun `任意角度下逆变换结果都在缓冲区范围内`() {
        val bufferW = 640
        val bufferH = 480
        for (rotation in listOf(0, 90, 180, 270)) {
            // 摆正后的坐标系尺寸：90/270 时宽高互换
            val uprightW = if (rotation % 180 == 0) bufferW else bufferH
            val uprightH = if (rotation % 180 == 0) bufferH else bufferW
            for (x in 0 until uprightW) {
                for (y in 0 until uprightH step 7) {
                    val (ox, oy) = RoiPlanner.toBufferedCoords(x, y, bufferW, bufferH, rotation)
                    assertTrue(
                        "r=$rotation ($x,$y) -> ($ox,$oy) 的 x 越界",
                        ox in 0 until bufferW
                    )
                    assertTrue(
                        "r=$rotation ($x,$y) -> ($ox,$oy) 的 y 越界",
                        oy in 0 until bufferH
                    )
                }
            }
        }
    }

    @Test
    fun `角度规范化只接受 90 的整数倍`() {
        assertEquals(0, RoiPlanner.normalizeRotation(0))
        assertEquals(90, RoiPlanner.normalizeRotation(90))
        assertEquals(180, RoiPlanner.normalizeRotation(180))
        assertEquals(270, RoiPlanner.normalizeRotation(270))
        // 超出 360 的等价角度要能收敛
        assertEquals(0, RoiPlanner.normalizeRotation(360))
        assertEquals(90, RoiPlanner.normalizeRotation(450))
        assertEquals(270, RoiPlanner.normalizeRotation(-90))
        // 认识的只有这四个
        assertNull(RoiPlanner.normalizeRotation(45))
        assertNull(RoiPlanner.normalizeRotation(1))
        assertNull(RoiPlanner.normalizeRotation(89))
    }

    // ══════════════════════════ 裁剪矩形 ══════════════════════════

    @Test
    fun `未旋转时按外扩比例裁出矩形`() {
        // box 100×60，外扩 25% → 各边 ±25 / ±15
        val plan = RoiPlanner.plan(
            boxLeft = 900, boxTop = 500, boxRight = 1000, boxBottom = 560,
            bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
        )
        assertNotNull(plan)
        plan!!
        // padded = (875,485)-(1025,575)，闭区间转成左闭右开就是 151×91
        assertEquals(875, plan.crop.left)
        assertEquals(485, plan.crop.top)
        assertEquals(151, plan.crop.width)
        assertEquals(91, plan.crop.height)
        assertEquals(0, plan.rotationDegrees)
    }

    /**
     * 90 度时裁剪块的宽高必须**对调**。
     *
     * 这是整个方案最容易出错的一处：摆正坐标系里一个「宽 100 高 60」的框，
     * 在原始缓冲区上是一个「宽 60 高 100」的框。若直接照搬摆正坐标系的宽高去裁，
     * 裁出来的会是一块完全无关的区域，而且**不会报任何错**。
     */
    @Test
    fun `旋转 90 度时裁剪块宽高对调`() {
        val plan = RoiPlanner.plan(
            boxLeft = 400, boxTop = 800, boxRight = 500, boxBottom = 860,
            bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 90
        )
        assertNotNull(plan)
        plan!!
        // 摆正坐标系里是 100 宽 × 60 高；到原始坐标系必须变成 91 宽 × 151 高
        assertEquals(91, plan.crop.width)
        assertEquals(151, plan.crop.height)
        assertEquals(90, plan.rotationDegrees)
    }

    @Test
    fun `裁剪矩形被夹在缓冲区范围内`() {
        // 框贴着左上角，外扩后必然为负
        val plan = RoiPlanner.plan(
            boxLeft = 2, boxTop = 2, boxRight = 202, boxBottom = 142,
            bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
        )
        assertNotNull(plan)
        plan!!
        assertTrue("left 不能为负", plan.crop.left >= 0)
        assertTrue("top 不能为负", plan.crop.top >= 0)
        assertTrue("右边不能越界", plan.crop.left + plan.crop.width <= 1920)
        assertTrue("下边不能越界", plan.crop.top + plan.crop.height <= 1080)
    }

    @Test
    fun `框贴着右下角时同样不会越界`() {
        val plan = RoiPlanner.plan(
            boxLeft = 1820, boxTop = 1000, boxRight = 1919, boxBottom = 1079,
            bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
        )
        assertNotNull(plan)
        plan!!
        assertTrue(plan.crop.left + plan.crop.width <= 1920)
        assertTrue(plan.crop.top + plan.crop.height <= 1080)
    }

    // ══════════════════════════ 放大倍率 ══════════════════════════

    @Test
    fun `小框会被放大到目标长边`() {
        val plan = RoiPlanner.plan(
            boxLeft = 900, boxTop = 500, boxRight = 1000, boxBottom = 560,
            bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
        )!!
        // 摆正后长边 151 → 目标 800
        assertEquals(800f / 151f, plan.scale, 0.01f)
        assertEquals(800, plan.outputWidth)
    }

    @Test
    fun `已经够大的框不会被缩小`() {
        // 框本身就是 900×600，放大只会浪费内存
        val plan = RoiPlanner.plan(
            boxLeft = 500, boxTop = 300, boxRight = 1250, boxBottom = 780,
            bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
        )!!
        assertTrue("不应当缩小，实际 scale=${plan.scale}", plan.scale >= 1f)
    }

    @Test
    fun `放大倍率有上限`() {
        // 极小的框（30×40）理论上要放大 20 倍，必须被 MAX_UPSCALE 拦住
        val plan = RoiPlanner.plan(
            boxLeft = 900, boxTop = 500, boxRight = 930, boxBottom = 540,
            bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
        )
        assertNotNull(plan)
        plan!!
        assertTrue("scale ${plan.scale} 超过上限", plan.scale <= RoiPlanner.MAX_UPSCALE + 0.001f)
    }

    /**
     * 框已经大到接近全帧时不精读，直接走快路径。
     *
     * 这是「拒绝比凑合更诚实」的体现：如果为了塞进上限而把 scale 压到 1 以下，
     * 就等于主动丢掉细节，换来的还是一次和全帧等价的识别——不如不做。
     */
    @Test
    fun `过大的候选框直接放弃精读`() {
        // 整个缓冲区就是一个候选框
        assertNull(
            RoiPlanner.plan(
                boxLeft = 0, boxTop = 0, boxRight = 1920, boxBottom = 1080,
                bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
            )
        )
        // 外扩后必然越过上限的宽扁框
        assertNull(
            RoiPlanner.plan(
                boxLeft = 40, boxTop = 400, boxRight = 1880, boxBottom = 700,
                bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
            )
        )
    }

    @Test
    fun `任何通过的方案输出长边都不超过上限`() {
        // 扫一批大小不同的框，凡是没被拒绝的都必须落在上限内
        val sizes = listOf(40, 80, 120, 200, 400, 700, 1000, 1400, 1800)
        sizes.forEach { size ->
            val plan = RoiPlanner.plan(
                boxLeft = 100, boxTop = 100, boxRight = 100 + size, boxBottom = 100 + size,
                bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
            ) ?: return@forEach
            val outLong = maxOf(plan.outputWidth, plan.outputHeight)
            assertTrue("size=$size 的输出长边 $outLong 超过上限", outLong <= RoiPlanner.MAX_LONG_SIDE_PX)
            assertTrue("size=$size 的放大倍率 ${plan.scale} 小于 1", plan.scale >= 1f)
            assertTrue("size=$size 的放大倍率 ${plan.scale} 超过上限", plan.scale <= RoiPlanner.MAX_UPSCALE + 0.001f)
        }
    }

    // ══════════════════════════ 拒绝输入 ══════════════════════════

    @Test
    fun `候选框太小则放弃精读`() {
        // 10×10 的框，放大 6 倍也才 60px，达不到 EAN-13 需要的 190px，白费一次识别
        assertNull(
            RoiPlanner.plan(
                boxLeft = 900, boxTop = 500, boxRight = 910, boxBottom = 510,
                bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
            )
        )
    }

    @Test
    fun `空的或反向的候选框被拒绝`() {
        assertNull(
            RoiPlanner.plan(
                boxLeft = 1000, boxTop = 500, boxRight = 1000, boxBottom = 560,
                bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
            )
        )
        assertNull(
            RoiPlanner.plan(
                boxLeft = 1000, boxTop = 560, boxRight = 900, boxBottom = 500,
                bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 0
            )
        )
    }

    @Test
    fun `非法角度被拒绝而不是就近猜一个`() {
        // 角度不认识 = 我们对坐标系的理解是错的，此时宁可退回快路径
        assertNull(
            RoiPlanner.plan(
                boxLeft = 900, boxTop = 500, boxRight = 1000, boxBottom = 560,
                bufferWidth = 1920, bufferHeight = 1080, rotationDegrees = 45
            )
        )
    }

    @Test
    fun `缓冲区尺寸非法时被拒绝`() {
        assertNull(
            RoiPlanner.plan(
                boxLeft = 10, boxTop = 10, boxRight = 100, boxBottom = 60,
                bufferWidth = 0, bufferHeight = 1080, rotationDegrees = 0
            )
        )
        assertNull(
            RoiPlanner.plan(
                boxLeft = 10, boxTop = 10, boxRight = 100, boxBottom = 60,
                bufferWidth = 1920, bufferHeight = -1, rotationDegrees = 0
            )
        )
    }

    /**
     * 四个角度对同一个物理位置规划出来的结果，摆正后应当**尺寸一致**。
     *
     * 这是对「宽高对调」逻辑的交叉验证：如果只在某一个角度上写对，
     * 这条会失败。
     */
    @Test
    fun `四个角度下摆正后的裁剪尺寸一致`() {
        // 摆正坐标系里都取一个 100×60 的框，位置选在各角度的安全区中央
        val cases = listOf(
            Triple(0, 900 to 500, 1920 to 1080),
            Triple(90, 400 to 800, 1920 to 1080),
            Triple(180, 900 to 500, 1920 to 1080),
            Triple(270, 400 to 800, 1920 to 1080)
        )
        cases.forEach { (rotation, box, buffer) ->
            val (bx, by) = box
            val (bw, bh) = buffer
            val plan = RoiPlanner.plan(
                boxLeft = bx, boxTop = by, boxRight = bx + 100, boxBottom = by + 60,
                bufferWidth = bw, bufferHeight = bh, rotationDegrees = rotation
            )
            assertNotNull("r=$rotation 应当能规划出方案", plan)
            plan!!
            val uprightW = if (rotation % 180 == 0) plan.crop.width else plan.crop.height
            val uprightH = if (rotation % 180 == 0) plan.crop.height else plan.crop.width
            assertEquals("r=$rotation 摆正后宽应为 151", 151, uprightW)
            assertEquals("r=$rotation 摆正后高应为 91", 91, uprightH)
        }
    }
}
