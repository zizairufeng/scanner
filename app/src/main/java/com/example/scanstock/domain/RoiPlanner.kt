package com.example.scanstock.domain

/**
 * 一块待裁剪的矩形（**原始相机缓冲区坐标系**，左上角为原点）。
 */
data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/**
 * 一次 ROI 精读的执行计划。
 *
 * @param crop            在原始缓冲区上裁哪一块
 * @param scale           裁出来之后放大多少倍（≥1）
 * @param rotationDegrees 裁剪块仍处于原始方向，交给 ML Kit 时把相机旋转角一并传进去，
 *                        由库负责摆正——**我们自己不旋图**，少一次全帧旋转开销，
 *                        也少一处最容易写错的坐标变换
 */
data class RoiPlan(
    val crop: CropRect,
    val scale: Float,
    val rotationDegrees: Int
) {
    /** 放大后的输出尺寸，供调用方预分配 Bitmap 用 */
    val outputWidth: Int get() = (crop.width * scale).toInt().coerceAtLeast(1)
    val outputHeight: Int get() = (crop.height * scale).toInt().coerceAtLeast(1)
}

/**
 * 「ROI 二次精读」的纯逻辑规划器。
 *
 * ## 为什么这条路比继续抬全帧分辨率有效
 *
 * ML Kit 官方对输入图的要求，除了「最小单元 ≥2px」，还有一句关键的话：
 *
 * > …you can improve performance by capturing images at a lower resolution,
 * > **but requiring that the barcode make up the majority of the input image.**
 *
 * 也就是说，**码在输入图里占的比例**本身就是识别条件之一。
 * 一个 EAN-13 在 1080p 全帧里只占宽度 5% 时，它整码只有 96px ——
 * 而规范要求至少 190px。此时就算把全帧提到 2560，也只是把它顶到 128px，**仍然不够**；
 * 而把它所在的那一小块裁出来放大，码立刻在输入图里占据主导，同时解码器要处理的
 * 像素量只有全帧的几十分之一。
 *
 * 这就是本类存在的理由：**同样的像素，花在目标上比摊在整帧上值。**
 *
 * ## 最容易写错的地方：坐标系不是同一个
 *
 * ML Kit 返回的 `boundingBox` 是**按旋转角摆正之后**的图像坐标；
 * 而我们能从 `ImageProxy` 的 Y plane 上裁的，是**原始缓冲区**坐标。
 * 两者在 `rotationDegrees = 90 / 270` 时不仅原点不同，**宽高还是互换的**。
 * 于是所有坐标都必须先做一次逆变换，见 [toBufferedCoords]。
 *
 * 这是整条链路上唯一无法靠"看起来对"来验证的一步，因此它被拆成纯函数、单独测。
 */
object RoiPlanner {

    /**
     * 候选框外扩比例。ML Kit 的潜在条码框往往贴得很紧，甚至比真实码略小，
     * 不外扩的话容易把码的静区（quiet zone）切掉——而静区丢了，1D 码基本就解不出来。
     * 25% 是在「留够静区」与「别把无关内容也框进来」之间的取值。
     */
    const val PADDING_RATIO = 0.25f

    /**
     * 放大后的目标长边（px）。
     *
     * ML Kit 推荐的典型输入是 1280×720 / 1920×1080；ROI 显然不需要那么大，
     * 但太小也会掉进「库内部按固定尺寸处理」的坑。800 约等于 720p 的长边，
     * 是一个「够库用、又不浪费」的档位。
     */
    const val TARGET_LONG_SIDE_PX = 800

    /** 放大倍率上限。放大不创造细节，超过这个倍数纯属插值糊弄，还费内存。 */
    const val MAX_UPSCALE = 6f

    /** 输出长边上限，防止畸形输入（如整帧就是一个码）把内存顶爆 */
    const val MAX_LONG_SIDE_PX = 1600

    /**
     * 低于这个边长就不值得精读了：这么小的候选框放大 6 倍也到不了 190px，
     * 白花一次二次识别的时间。
     */
    const val MIN_VIABLE_SIDE_PX = 24

    // ─────────────────────── 中心区补扫（v1.7）───────────────────────

    /**
     * 中心区补扫的裁剪比例（占全帧宽高的比例，0.6 = 中央 60%）。
     *
     * ## 为什么这条路值得单独做一次
     *
     * ML Kit 官方文档有一句很容易被忽略的硬约束：
     *
     * > For a Data Matrix code to be recognized, the code must intersect
     * > the center point of the input image.
     *
     * 也就是说，**Data Matrix 码必须压住图像中心点才会被识别**，
     * 而且一张图只能识别一个。画面里的小码偏到边角，粗检连候选框都不会给，
     * 这时「按候选框精读」这条路根本不会被触发——它不是失败，而是压根没开始。
     *
     * 中心区补扫把「取景框中央」这块单独裁出来放大再解一次，同时满足两件事：
     *  1. 码落在**新的**图像中心上，满足上面的硬约束；
     *  2. 码在这张图里占的比例大幅上升，满足「条码应占据输入图的大部分」。
     *
     * 代价是每次多解一张约 0.1~0.2M 像素的小图，只在「全帧一张码都没解出来」时才走，
     * 正常扫码的快路径完全不受影响。
     */
    const val CENTER_CROP_FRACTION = 0.6f

    /** 中心区补扫的放大目标长边。比候选框精读大一些：这里没有候选框给的紧边界 */
    const val CENTER_TARGET_LONG_SIDE_PX = 1200

    /**
     * 中心区补扫的放大上限。
     *
     * 中心区本来就不小（1080p 下约 1152×648），放大只是把长边顶到 1200，
     * 真要 2 倍以上说明分析分辨率本身太低——那种情况应该由用户去调档位，
     * 而不是让这里插值糊出一张假高清图。
     */
    const val CENTER_MAX_UPSCALE = 2f

    // ─────────────────── 金属蚀刻码 DPM 阶梯（v1.8）───────────────────

    /**
     * DPM 阶梯的各级裁剪比例（占**画面短边**的比例），从大到小排列。
     *
     * ## 这组数字是被实测逼出来的
     *
     * 拿一颗海光 CPU 顶盖上的激光蚀刻 Data Matrix（约 2.5mm，26×26 模块）做实验，
     * 裁块边长 ÷ 码边长 的比值与能否解出的关系是：
     *
     * | 1.2× | 1.5× | 1.8× | 2.1× | 3.0×+ |
     * |------|------|------|------|-------|
     * | ✓    | ✓    | ✓    | ✗    | 全 ✗  |
     *
     * 即 **分析区域只能比码大 1.2~1.8 倍，超过 2 倍就彻底解不出**。
     * 原因见 [ZxingDecoder]：DPM 码是「浅色蚀刻、深色金属」的低对比度反色码，
     * 周围一旦混进大片同色背景，全局直方图二值化的阈值就被背景拉偏，
     * 整张图被二值化成一片纯色，定位图案随之消失。
     *
     * 而用户并不知道自己的码在画面里有多大，所以只能**按比例扫一遍阶梯**：
     * 相邻两级相差 1.3 倍，而黄金窗口宽 1.5 倍（1.8/1.2），
     * 数学上保证了「任意尺寸的码，一定至少有一级落在 1.2~1.8 倍内」。
     *
     * 最细一级 0.12 × 720 ≈ 86px，对应的最小可识码约 48px；
     * 再小的码在 720p 分析流里每个模块不足 2px，本来就无解，靠调分辨率档位解决。
     */
    val DPM_LADDER_RATIOS: FloatArray = floatArrayOf(
        1.00f, 0.77f, 0.59f, 0.45f, 0.35f, 0.27f, 0.21f, 0.16f, 0.12f
    )

    /**
     * DPM 阶梯的放大目标长边。
     *
     * 实测 400 / 700 / 1000 三档都能解出，说明这条管线对放大倍数不敏感；
     * 取 800 与 [TARGET_LONG_SIDE_PX] 一致，省得再多一个魔数。
     */
    const val DPM_TARGET_LONG_SIDE_PX = 800

    /**
     * DPM 阶梯逐级裁剪的最大放大上限。
     *
     * 最细一级只有 86px，放大到 800 是 9.3 倍——看上去很夸张，但这一级存在的意义
     * 恰恰是「码本来就很小」，插值放大虽然不创造细节，却能让 ZXing 的模板块采样
     * 落得更准（实测必需）。上限仍压到 10 倍以内，避免内存与耗时失控。
     */
    const val DPM_MAX_UPSCALE = 10f

    /**
     * 规划「金属蚀刻码」阶梯中的**一级**。
     *
     * 裁剪中心恒为画面中心——旋转只绕中心转，所以这里同样不需要坐标逆变换。
     *
     * @param level [DPM_LADDER_RATIOS] 的下标，越界或尺寸非法时返回 `null`
     */
    fun dpmPlan(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        level: Int
    ): RoiPlan? {
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        if (level !in DPM_LADDER_RATIOS.indices) return null
        val rotation = normalizeRotation(rotationDegrees) ?: return null

        val shortSide = minOf(bufferWidth, bufferHeight)
        val side = (shortSide * DPM_LADDER_RATIOS[level]).toInt()
        if (side < MIN_VIABLE_SIDE_PX) return null

        // 方形裁块：短边定边长，居中；再各自夹到缓冲区之内（极端比例屏才可能触发）
        val w = side.coerceAtMost(bufferWidth)
        val h = side.coerceAtMost(bufferHeight)
        val left = (bufferWidth - w) / 2
        val top = (bufferHeight - h) / 2

        // 裁块是方形，摆正与否长边都取两者较大值即可
        val longSide = maxOf(w, h)
        if (longSide <= 0) return null
        val scale = (DPM_TARGET_LONG_SIDE_PX.toFloat() / longSide)
            .coerceIn(1f, DPM_MAX_UPSCALE)

        return RoiPlan(
            crop = CropRect(left, top, w, h),
            scale = scale,
            rotationDegrees = rotation
        )
    }

    /**
     * 规划整条 DPM 阶梯。
     *
     * @param order 优先尝试的级别下标顺序。调用方可以把「上次命中的那一级」提到最前，
     *              连续扫同类货时第一级就命中。为 `null` 时按默认从大到小。
     * @return 阶梯；尺寸非法时返回空列表（调用方直接跳过，不报错）
     */
    fun dpmLadder(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        order: IntArray? = null
    ): List<RoiPlan> {
        val levels = order?.filter { it in DPM_LADDER_RATIOS.indices }
            ?: DPM_LADDER_RATIOS.indices.toList()
        return levels.mapNotNull { dpmPlan(bufferWidth, bufferHeight, rotationDegrees, it) }
    }

    /**
     * 阶梯的尝试**顺序**：从 [start] 开始环形遍历，不重不漏。
     *
     * ## 为什么需要「环形」而不是固定从大到小
     *
     * v1.8.2 给整条补救链加了总预算，阶梯因此**不再保证一次能扫完九级**：
     * 90ms 的预留大约只够 3~4 级。如果每次都从 0 号（最大）开始，
     * 只有小码（需要靠后的级别）的场景会**永远扫不到那一级**——
     * 帧帧都从头开始，帧帧都在同一个地方被预算截断。
     *
     * 所以调用方每次从上一轮停下的位置继续，绕完一圈再回到起点：
     * 9 级、每轮 3 级、间隔 400ms，最迟 1.2 秒覆盖全部尺寸。
     *
     * @param start 起始级别下标（负数或越界会被规范化到区间内）
     */
    fun levelOrder(start: Int): IntArray {
        val total = DPM_LADDER_RATIOS.size
        val s = ((start % total) + total) % total
        return IntArray(total) { (s + it) % total }
    }

    /**
     * 这一轮从 [start] 开始、实际试了 [triedCount] 级之后，下一轮该从哪一级开始。
     *
     * 单独抽出来是因为它是「小码迟早会被扫到」这条性质的**全部**实现：
     * 写成 `(start + tried) % n` 散落在调用处，很容易在某一处写成 `tried` 而不是
     * `tried + 1`，结果每轮少走一格，导致某几级永远轮不到——而且不报错、只是扫不出。
     */
    fun cursorAfter(start: Int, triedCount: Int): Int {
        val total = DPM_LADDER_RATIOS.size
        val s = ((start % total) + total) % total
        return (((s + triedCount) % total) + total) % total
    }

    /**
     * 规划一次「中心区补扫」。
     *
     * 与 [plan] 的区别：这里没有候选框可用，直接按比例取图像中央的一块。
     * 裁剪矩形在**原始缓冲区坐标**里计算——图像中心在「原始坐标」与「摆正坐标」
     * 里是同一个点（旋转只绕中心转），宽高对调不影响以中心为基准的比例裁剪，
     * 所以这里不需要做坐标逆变换。
     *
     * @return 计划；尺寸非法时返回 `null`
     */
    fun centerPlan(
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        fraction: Float = CENTER_CROP_FRACTION
    ): RoiPlan? {
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        val rotation = normalizeRotation(rotationDegrees) ?: return null
        val ratio = fraction.coerceIn(0.2f, 1f)

        val cropW = (bufferWidth * ratio).toInt().coerceAtLeast(MIN_VIABLE_SIDE_PX)
        val cropH = (bufferHeight * ratio).toInt().coerceAtLeast(MIN_VIABLE_SIDE_PX)
        if (cropW > bufferWidth || cropH > bufferHeight) return null

        val left = (bufferWidth - cropW) / 2
        val top = (bufferHeight - cropH) / 2

        // 摆正后的长边：90/270 度时宽高互换，放大目标按摆正后的尺寸算才准
        val uprightW = if (rotation == 90 || rotation == 270) cropH else cropW
        val uprightH = if (rotation == 90 || rotation == 270) cropW else cropH
        val longSide = maxOf(uprightW, uprightH)
        if (longSide <= 0) return null

        val scale = (CENTER_TARGET_LONG_SIDE_PX.toFloat() / longSide)
            .coerceIn(1f, CENTER_MAX_UPSCALE)

        return RoiPlan(
            crop = CropRect(left, top, cropW, cropH),
            scale = scale,
            rotationDegrees = rotation
        )
    }

    /**
     * 规划一次 ROI 精读。
     *
     * @param boxLeft   ML Kit `boundingBox.left`（摆正坐标系）
     * @param boxTop    ML Kit `boundingBox.top`
     * @param boxRight  ML Kit `boundingBox.right`
     * @param boxBottom ML Kit `boundingBox.bottom`
     * @param bufferWidth  **原始缓冲区**宽度（未旋转前），取自 `ImageProxy.width`
     * @param bufferHeight **原始缓冲区**高度（未旋转前），取自 `ImageProxy.height`
     * @param rotationDegrees `ImageProxy.imageInfo.rotationDegrees`
     * @return 计划；输入不可用时返回 `null`（调用方应直接走快路径，不要报错）
     */
    fun plan(
        boxLeft: Int,
        boxTop: Int,
        boxRight: Int,
        boxBottom: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int
    ): RoiPlan? {
        if (bufferWidth <= 0 || bufferHeight <= 0) return null
        val rotation = normalizeRotation(rotationDegrees) ?: return null

        // ── 1. 在摆正坐标系里外扩 ──
        val boxW = boxRight - boxLeft
        val boxH = boxBottom - boxTop
        if (boxW <= 0 || boxH <= 0) return null

        val padW = (boxW * PADDING_RATIO).toInt()
        val padH = (boxH * PADDING_RATIO).toInt()
        val paddedLeft = boxLeft - padW
        val paddedTop = boxTop - padH
        val paddedRight = boxRight + padW
        val paddedBottom = boxBottom + padH

        // ── 2. 四个角逆变换回原始坐标系，取包围盒 ──
        // 90/270 度时这一步会把宽高对调，所以必须逐点变换后重新求包围盒，
        // 不能直接拿摆正坐标系的 w/h 去裁。
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (corner in CORNERS) {
            val x = if (corner[0] == 0) paddedLeft else paddedRight
            val y = if (corner[1] == 0) paddedTop else paddedBottom
            val (ox, oy) = toBufferedCoords(x, y, bufferWidth, bufferHeight, rotation)
            if (ox < minX) minX = ox
            if (oy < minY) minY = oy
            if (ox > maxX) maxX = ox
            if (oy > maxY) maxY = oy
        }

        // ── 3. 夹进缓冲区范围 ──
        val left = minX.coerceIn(0, bufferWidth - 1)
        val top = minY.coerceIn(0, bufferHeight - 1)
        val right = (maxX + 1).coerceIn(left + 1, bufferWidth)
        val bottom = (maxY + 1).coerceIn(top + 1, bufferHeight)
        val cropW = right - left
        val cropH = bottom - top

        // ── 4. 太小就别费事了 ──
        // 这里判的是「摆正后」的短边：90/270 度下裁剪块的宽高会交换，
        // 真正在识别时起作用的是摆正后的尺寸。
        val uprightW = if (rotation == 90 || rotation == 270) cropH else cropW
        val uprightH = if (rotation == 90 || rotation == 270) cropW else cropH
        if (uprightW < MIN_VIABLE_SIDE_PX || uprightH < MIN_VIABLE_SIDE_PX) return null

        // ── 5. 算放大倍率 ──
        // 只放大、不缩小：缩小会真的丢细节，而本来就已经够大的框并不需要精读。
        val longSide = maxOf(uprightW, uprightH)
        val scale = (TARGET_LONG_SIDE_PX.toFloat() / longSide).coerceIn(1f, MAX_UPSCALE)

        // 摆正后的输出长边超上限 → 这个框已经大到接近全帧了，精读没有意义
        // （全帧本身就在走快路径），直接放弃。
        //
        // 这里**不能**改成「先放大再缩回去」：那样 scale 会被压到 1 以下，
        // 等于把细节主动丢掉，还会让 [MAX_UPSCALE] 的语义变得含糊。
        // 拒绝比凑合更诚实。
        if (longSide * scale > MAX_LONG_SIDE_PX) return null

        return RoiPlan(
            crop = CropRect(left, top, cropW, cropH),
            scale = scale,
            rotationDegrees = rotation
        )
    }

    /**
     * 「摆正后坐标」→「原始缓冲区坐标」的逆变换。
     *
     * ## 推导
     *
     * CameraX 的 `rotationDegrees` 表示「为了把原始画面摆正，需要顺时针旋转多少度」。
     * 设原始缓冲区宽 `W`、高 `H`，对原始点 `(xo, yo)` 顺时针转 [rotationDegrees] 度得到摆正点 `(x, y)`：
     *
     * ```
     * r = 0   : (x, y) = (xo, yo)
     * r = 90  : (x, y) = (H-1-yo, xo)
     * r = 180 : (x, y) = (W-1-xo, H-1-yo)
     * r = 270 : (x, y) = (yo, W-1-xo)
     * ```
     *
     * 我们要的是反过来，于是解出：
     *
     * ```
     * r = 0   : (xo, yo) = (x, y)
     * r = 90  : (xo, yo) = (y, H-1-x)
     * r = 180 : (xo, yo) = (W-1-x, H-1-y)
     * r = 270 : (xo, yo) = (W-1-y, x)
     * ```
     *
     * 拿 90 度验证一下取值范围：摆正后尺寸是 `H×W`，所以 `x ∈ [0,H)`、`y ∈ [0,W)`；
     * 代进去得 `xo = y ∈ [0,W)` ✓、`yo = H-1-x ∈ [0,H)` ✓ —— 正好落回原始缓冲区的范围内。
     *
     * 这是整个 ROI 方案里唯一「错了也不报错、只是识别率莫名其妙变差」的地方，
     * 所以它必须能被单独测试。
     */
    internal fun toBufferedCoords(
        x: Int,
        y: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int
    ): Pair<Int, Int> = when (normalizeRotation(rotationDegrees)) {
        90 -> y to (bufferHeight - 1 - x)
        180 -> (bufferWidth - 1 - x) to (bufferHeight - 1 - y)
        270 -> (bufferWidth - 1 - y) to x
        // 0 与「未知角度」都按不旋转处理
        else -> x to y
    }

    /**
     * 把任意角度规范到 {0, 90, 180, 270}；不是这四个值就返回 `null`。
     *
     * 返回 `null` 而不是「就近取一个」：角度不认识意味着我们对坐标系的理解是错的，
     * 此时硬猜一个只会把错误藏起来，不如让调用方退回快路径。
     */
    internal fun normalizeRotation(degrees: Int): Int? {
        val normalized = ((degrees % 360) + 360) % 360
        return if (normalized % 90 == 0) normalized else null
    }

    /** 四个角：(0,0) 左上、(1,0) 右上、(0,1) 左下、(1,1) 右下 */
    private val CORNERS = arrayOf(
        intArrayOf(0, 0),
        intArrayOf(1, 0),
        intArrayOf(0, 1),
        intArrayOf(1, 1)
    )
}
