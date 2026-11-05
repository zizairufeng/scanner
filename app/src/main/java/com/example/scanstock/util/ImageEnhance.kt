package com.example.scanstock.util

import kotlin.math.roundToInt

/**
 * 裁块图像增强（v1.7，纯 Kotlin，零 Android 依赖 → 可 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * 硬盘标签、金属壳、塑封膜这类表面在补光下经常出现**局部反光**：
 * 整块裁图的直方图被压在很窄的一段灰阶里（比如 90~150），
 * 二值化算法找不到合适的阈值，于是「像素明明够，却怎么也解不出」。
 *
 * 这一步只做一件事：**把灰阶线性拉伸到 0~255**，按 2% / 98% 分位截断以忽略
 * 少量高光点和噪点。这是 CLAHE 的极简替代——不做分块、不做双线性插值，
 * 收益却覆盖了大部分「对比度不足」的场景，而且一次遍历就够。
 *
 * ## 边界（写清楚，不吹）
 *
 * - 它**不创造细节**：已经模糊的图拉完还是模糊的（失焦要靠对焦，不靠算法）；
 * - 它**不能救强反光把码吃掉的那一块**：那是信息已经没了。
 *
 * 所以它只用在「候选框精读失败后的第二次尝试」，全帧快路径完全不做增强——
 * 那是纯浪费，还可能把本来能解的图改坏。
 */
object ImageEnhance {

    /** 低分位截断：忽略最暗的 2%，避开传感器噪点 */
    const val LOW_PERCENTILE = 0.02f

    /** 高分位截断：忽略最亮的 2%，避开反光高光 */
    const val HIGH_PERCENTILE = 0.98f

    /**
     * 拉伸后灰阶跨度小于这个值就认为「本来就没有可用信息」，直接放弃。
     * 8 级灰阶（约 3%）连码的黑白都分不开，再拉也只是把噪点放大。
     */
    const val MIN_SPAN = 8

    /**
     * 原地线性拉伸 [pixels]（ARGB_8888 像素数组）的灰阶。
     *
     * 只改 R/G/B，不动 A。灰度图（R=G=B）拉完仍是灰度图，
     * 彩色图会被压成近似灰度——这在裁块里无所谓，条码识别本来不用颜色。
     *
     * @return 实际作用的像素数；返回 0 表示没有改动（跨度太小，或输入为空）
     */
    fun contrastStretchInPlace(
        pixels: IntArray,
        lowPercentile: Float = LOW_PERCENTILE,
        highPercentile: Float = HIGH_PERCENTILE
    ): Int {
        val n = pixels.size
        if (n == 0) return 0

        val histogram = IntArray(256)
        for (px in pixels) {
            histogram[lumaOf(px)]++
        }

        val lowTarget = (n * lowPercentile.coerceIn(0f, 0.49f)).toInt()
        val highTarget = (n * highPercentile.coerceIn(0.51f, 1f)).toInt()

        var low = 0
        var acc = 0
        while (low < 255 && acc + histogram[low] <= lowTarget) {
            acc += histogram[low]
            low++
        }
        var high = 255
        acc = 0
        while (high > 0 && acc + histogram[high] <= n - highTarget) {
            acc += histogram[high]
            high--
        }

        val span = high - low
        if (span < MIN_SPAN) return 0

        // 预先把 256 个映射值算好，逐像素只查表，避免在内层做除法
        val lut = IntArray(256) { value ->
            (((value - low) * 255f) / span).roundToInt().coerceIn(0, 255)
        }

        for (i in 0 until n) {
            val px = pixels[i]
            val gray = lut[lumaOf(px)]
            // 灰度图三通道本来就相同，直接写同一份值；彩色图也被压平，
            // 这在裁块里没有损失（条码解码只用亮度）
            pixels[i] = (px and 0xFF000000.toInt()) or (gray shl 16) or (gray shl 8) or gray
        }
        return n
    }

    /** 灰度直方图的「有效跨度」，用于判断这张图值不值得拉伸（调试与单测用） */
    fun spanOf(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0
        var min = 255
        var max = 0
        for (px in pixels) {
            val g = lumaOf(px)
            if (g < min) min = g
            if (g > max) max = g
        }
        return max - min
    }

    /** 整数近似的 Rec.601 亮度，避免浮点运算拖慢逐像素循环 */
    private fun lumaOf(px: Int): Int {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        // (299R + 587G + 114B) / 1000
        return (299 * r + 587 * g + 114 * b) / 1000
    }
}
