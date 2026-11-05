package com.example.scanstock.util

/**
 * 相册图片解码尺寸策略。
 *
 * 为什么需要它：`InputImage.fromFilePath()` 会把整张原图解码进内存。
 * 现在手机一张照片动辄 4000×3000（1200 万像素），ARGB_8888 下就是
 * **约 46MB** 的 Bitmap，再加上 ML Kit 内部的中间缓冲，很容易在低端机上 OOM，
 * 而且解码耗时明显。条码识别并不需要 1200 万像素——把最长边压到 [MAX_EDGE]
 * 以内，识别率基本不变，内存降到 1/6 左右。
 *
 * 之所以抽成独立 object：它是纯函数，可以用纯 JVM 单测覆盖（BitmapFactory 本身测不了）。
 */
object ImageSampling {

    /** 解码后允许的最长边（像素）。1600 足够解 1D 码与常见二维码 */
    const val MAX_EDGE = 1600

    /**
     * 计算 BitmapFactory 的 inSampleSize。
     *
     * 只取 2 的幂（BitmapFactory 的约定：非 2 的幂会被向下取整到最近的 2 的幂），
     * 因此这里也按 2 的倍数递增，保证「算出来的」和「实际生效的」一致。
     *
     * @param width  原图宽（来自 inJustDecodeBounds）
     * @param height 原图高
     * @param maxEdge 允许的最长边
     * @return 采样率，最小为 1（不缩放）
     */
    fun inSampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (width <= 0 || height <= 0) return 1
        val longest = kotlin.math.max(width, height)
        var sample = 1
        // 上限保护：极端畸形尺寸下最多缩到 1/32，避免死循环与「缩到啥也看不见」
        while (longest / sample > maxEdge && sample < 32) {
            sample *= 2
        }
        return sample
    }
}
